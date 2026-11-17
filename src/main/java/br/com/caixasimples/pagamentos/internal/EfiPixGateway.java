package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.pagamentos.domain.ConsultaPix;
import br.com.caixasimples.pagamentos.domain.PixGateway;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Adapter Efí. Segredos e certificado são resolvidos por Conta somente no ambiente. */
@Component
class EfiPixGateway implements PixGateway {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PRODUCAO = "https://pix.api.efipay.com.br";
    private static final String HOMOLOGACAO = "https://pix-h.api.efipay.com.br";
    private static final Pattern PARTE_DA_URL = Pattern.compile("[A-Za-z0-9_-]+");

    /** Lê as mesmas variáveis que a cobrança, sem abrir o certificado nem chamar a Efí. */
    @Override
    public boolean configurada() {
        try {
            configuracao();
            return true;
        } catch (IllegalStateException faltando) {
            return false;
        }
    }

    @Override
    public String chaveRecebedora() {
        return configuracao().chave();
    }

    @Override
    public String criarCobranca(CobrancaPix cobranca, Money valor) {
        Configuracao config = configuracao();
        if (!config.chave().equals(cobranca.chaveRecebedora())) {
            throw new IllegalStateException(
                    "A chave Pix da Conta mudou durante a cobrança; tente de novo.");
        }
        try {
            HttpClient http = cliente(config);
            String token = token(http, config);
            return CobrancaImediataPadrao.criarOuRecuperar(http, config.base(), token, Map.of(),
                    cobranca, valor, config.chave());
        } catch (PixIndisponivelException erro) {
            throw erro;
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new PixIndisponivelException("consulta Pix interrompida", erro);
        } catch (Exception erro) {
            throw new PixIndisponivelException("resultado da cobranca Pix incerto", erro);
        }
    }

    @Override
    public ConsultaPix consultar(CobrancaPix cobranca) {
        Configuracao config = configuracao();
        if (!config.chave().equals(cobranca.chaveRecebedora())) {
            throw new PixIndisponivelException("chave Pix da Conta mudou durante a tentativa");
        }
        try {
            HttpClient http = cliente(config);
            HttpResponse<String> resposta = CobrancaImediataPadrao.enviar(http, config.base(), "GET",
                    "/v2/cob/" + cobranca.txid(), token(http, config), Map.of(), null);
            if (resposta.statusCode() != 200) {
                throw new PixIndisponivelException("consulta da cobranca Pix indisponivel");
            }
            return interpretarConsulta(JSON.readTree(resposta.body()));
        } catch (PixIndisponivelException erro) {
            throw erro;
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new PixIndisponivelException("consulta Pix interrompida", erro);
        } catch (Exception erro) {
            throw new PixIndisponivelException("consulta Pix incerta", erro);
        }
    }

    @Override
    public void removerCobranca(CobrancaPix cobranca) {
        Configuracao config = configuracao();
        if (!config.chave().equals(cobranca.chaveRecebedora())) {
            throw new PixIndisponivelException("chave Pix da Conta mudou durante a tentativa");
        }
        try {
            HttpClient http = cliente(config);
            CobrancaImediataPadrao.remover(http, config.base(), token(http, config), Map.of(),
                    cobranca);
        } catch (PixIndisponivelException erro) {
            throw erro;
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new PixIndisponivelException("remocao Pix interrompida", erro);
        } catch (Exception erro) {
            throw new PixIndisponivelException("remocao Pix incerta", erro);
        }
    }

    /**
     * Cadastra na Efí o webhook da chave Pix da Conta do contexto, apontando para o domínio só do
     * aviso.
     *
     * <p>Fica fora da porta PixGateway: o cadastro com o segredo na URL é da Efí, e só a execução
     * avulsa do mantenedor o chama. A Efí testa a URL antes de responder, então o domínio precisa
     * estar no ar, com o proxy exigindo o certificado de cliente dela.
     */
    void cadastrarWebhook(String dominio) {
        Configuracao config = configuracao();
        String prefixo = prefixoDaConta();
        String url = urlDoWebhook(dominio, obrigatoria(prefixo + "WEBHOOK_ID"),
                obrigatoria(prefixo + "WEBHOOK_SECRET"));
        try {
            HttpClient http = cliente(config);
            cadastrar(http, config.base(), token(http, config), config.chave(), url);
        } catch (IllegalStateException | PixIndisponivelException erro) {
            throw erro;
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("O cadastro do webhook foi interrompido.", erro);
        } catch (Exception erro) {
            throw new IllegalStateException("O cadastro do webhook não teve resposta da Efí.", erro);
        }
    }

    /**
     * A URL que a Efí chama. O identificador e o segredo só têm letras, números, hífen e sublinhado,
     * para irem como estão, sem codificação que a Efí e a rota pudessem ler de jeitos diferentes.
     */
    static String urlDoWebhook(String dominio, String identificador, String segredo) {
        if (!PARTE_DA_URL.matcher(identificador).matches()
                || !PARTE_DA_URL.matcher(segredo).matches()) {
            throw new IllegalStateException("O identificador e o segredo do webhook da Conta só "
                    + "podem ter letras, números, hífen e sublinhado.");
        }
        // A Efí acrescenta /pix ao fim da URL cadastrada, que cairia colado no segredo. Com o
        // ignorar= por último, o /pix vira o valor desse parâmetro, que a rota não lê.
        return "https://" + dominio + "/api/webhooks/pix/" + identificador + "?hmac=" + segredo
                + "&ignorar=";
    }

    static void cadastrar(HttpClient http, String base, String token, String chave, String url)
            throws Exception {
        String caminho = "/v2/webhook/" + URLEncoder.encode(chave, StandardCharsets.UTF_8);
        HttpResponse<String> resposta = CobrancaImediataPadrao.enviar(http, base, "PUT", caminho,
                token, Map.of(), JSON.writeValueAsString(Map.of("webhookUrl", url)));
        if (resposta.statusCode() < 200 || resposta.statusCode() >= 300) {
            throw new IllegalStateException("A Efí recusou o cadastro do webhook, com "
                    + resposta.statusCode() + ": " + motivoDaRecusa(resposta.body()));
        }
    }

    /**
     * O nome e a mensagem do erro da Efí, que dizem o que falta: o mTLS, a URL fora do ar, a resposta
     * de erro da rota. Nenhuma mensagem documentada repete a URL; se uma repetir, o segredo sai do
     * texto antes de ir ao log.
     */
    static String motivoDaRecusa(String corpo) {
        String motivo;
        try {
            JsonNode erro = JSON.readTree(corpo);
            motivo = (erro.path("nome").asText("") + " " + erro.path("mensagem").asText("")).trim();
        } catch (Exception ilegivel) {
            motivo = "";
        }
        if (motivo.isEmpty()) {
            return "sem detalhe legível";
        }
        motivo = motivo.replaceAll("hmac=[A-Za-z0-9_-]*", "hmac=(segredo)");
        return motivo.length() <= 300 ? motivo : motivo.substring(0, 300);
    }

    static ConsultaPix interpretarConsulta(JsonNode dados) {
        try {
            String txid = dados.path("txid").asText("");
            String chave = dados.path("chave").asText("");
            BigDecimal valor = new BigDecimal(dados.path("valor").path("original").asText());
            String status = dados.path("status").asText("");
            if (txid.isBlank() || chave.isBlank() || valor.signum() <= 0) {
                throw new PixIndisponivelException("consulta Pix sem correlacao verificavel");
            }
            if ("CONCLUIDA".equals(status)) {
                JsonNode recebidos = dados.path("pix");
                if (!recebidos.isArray() || recebidos.isEmpty()) {
                    throw new PixIndisponivelException("cobranca concluida sem Pix recebido verificavel");
                }
                BigDecimal soma = BigDecimal.ZERO;
                for (JsonNode recebido : recebidos) {
                    if (!txid.equals(recebido.path("txid").asText())) {
                        throw new PixIndisponivelException("Pix recebido com txid divergente");
                    }
                    soma = soma.add(new BigDecimal(recebido.path("valor").asText()));
                }
                if (soma.compareTo(valor) < 0) {
                    throw new PixIndisponivelException("Pix recebido nao cobre a cobranca");
                }
                return new ConsultaPix(txid, chave, Money.de(valor), true);
            }
            if (!"ATIVA".equals(status) && !"REMOVIDA_PELO_USUARIO_RECEBEDOR".equals(status)
                    && !"REMOVIDA_PELO_PSP".equals(status)) {
                throw new PixIndisponivelException("estado da cobranca Pix desconhecido");
            }
            if (!dados.path("pix").isMissingNode() && !dados.path("pix").isEmpty()) {
                throw new PixIndisponivelException("cobranca sem confirmacao contem Pix recebido");
            }
            return new ConsultaPix(txid, chave, Money.de(valor), false,
                    !"ATIVA".equals(status));
        } catch (PixIndisponivelException erro) {
            throw erro;
        } catch (Exception erro) {
            throw new PixIndisponivelException("consulta Pix com dados invalidos", erro);
        }
    }

    private static String prefixoDaConta() {
        String sufixo = TenantContext.exigirAtual().valor().toString().replace("-", "").toUpperCase();
        return "ECRX_CAIXA_EFI_" + sufixo + "_";
    }

    private static Configuracao configuracao() {
        String prefixo = prefixoDaConta();
        String ambiente = System.getenv(prefixo + "AMBIENTE");
        if (!"HOMOLOGACAO".equals(ambiente) && !"PRODUCAO".equals(ambiente)) {
            throw new IllegalStateException("O Pix integrado não está configurado para esta Conta.");
        }
        return new Configuracao("PRODUCAO".equals(ambiente) ? PRODUCAO : HOMOLOGACAO,
                obrigatoria(prefixo + "CLIENT_ID"), obrigatoria(prefixo + "CLIENT_SECRET"),
                obrigatoria(prefixo + "CHAVE_PIX"), obrigatoria(prefixo + "CERTIFICADO_P12"),
                System.getenv().getOrDefault(prefixo + "CERTIFICADO_SENHA", ""));
    }

    private static String obrigatoria(String nome) {
        String valor = System.getenv(nome);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "A configuração do Pix integrado desta Conta está incompleta.");
        }
        return valor;
    }

    private static HttpClient cliente(Configuracao config) throws Exception {
        KeyStore certificado = KeyStore.getInstance("PKCS12");
        char[] senha = config.senhaCertificado().toCharArray();
        try (InputStream arquivo = Files.newInputStream(Path.of(config.certificado()))) {
            certificado.load(arquivo, senha);
        }
        KeyManagerFactory chaves = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        chaves.init(certificado, senha);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(chaves.getKeyManagers(), null, new SecureRandom());
        return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static String token(HttpClient http, Configuracao config) throws Exception {
        String basico = Base64.getEncoder().encodeToString((config.clientId() + ":"
                + config.clientSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(config.base() + "/oauth/token"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Basic " + basico)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"grant_type\":\"client_credentials\"}"))
                .build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200) {
            throw new PixIndisponivelException("autorizacao Pix indisponivel");
        }
        String token = JSON.readTree(resposta.body()).path("access_token").asText("");
        if (token.isBlank()) {
            throw new PixIndisponivelException("autorizacao Pix sem token");
        }
        return token;
    }

    private record Configuracao(String base, String clientId, String clientSecret, String chave,
            String certificado, String senhaCertificado) {
    }
}
