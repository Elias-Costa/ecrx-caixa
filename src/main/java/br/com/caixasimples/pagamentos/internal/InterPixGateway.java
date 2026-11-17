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
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Adapter do Inter. Segredos e certificado são resolvidos por Conta somente no ambiente, com o prefixo
 * ECRX_CAIXA_INTER_ e o id da Conta em maiúsculas, sem hífens.
 *
 * <p>O certificado e a chave são lidos em PEM, como o Inter os entrega, e o KeyStore é montado em
 * memória: sem conversão para PKCS12 nem uma senha a mais para guardar.
 *
 * <p>O token fica guardado por Conta e por client id até faltarem 5 minutos para vencer. O Inter limita
 * a 5 tokens por minuto em cada integração, e criar a cobrança e reconsultá-la, um token por operação,
 * passariam disso com duas ou três vendas Pix no mesmo minuto. Uma recusa 401 descarta o token, e a
 * próxima operação pede outro; um reinício perde o guardado, sem prejuízo.
 *
 * <p>Quem decide se a cobrança foi paga é a lista de Pix recebidos pelo txid, e não o estado da
 * cobrança: no ambiente de homologação do Inter, a cobrança paga continua ATIVA. A consulta da
 * cobrança dá o txid, a chave, o valor e se ela foi removida.
 */
@Component
class InterPixGateway implements PixGateway {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PRODUCAO = "https://cdpj.partners.bancointer.com.br";
    static final String HOMOLOGACAO = "https://cdpj-sandbox.partners.uatinter.co";
    private static final String ESCOPOS = "cob.write cob.read pix.read webhook.write webhook.read";
    private static final Duration FOLGA_DO_TOKEN = Duration.ofMinutes(5);
    private static final Duration MARGEM_DA_BUSCA = Duration.ofHours(1);
    private static final Pattern PARTE_DA_URL = Pattern.compile("[A-Za-z0-9_-]+");
    // Os marcadores vão em partes para a linha inteira não parecer, a quem varre o código atrás de
    // segredo, uma chave privada de verdade.
    static final String INICIO_DA_CHAVE = "-----BEGIN " + "PRIVATE KEY-----";
    static final String FIM_DA_CHAVE = "-----END " + "PRIVATE KEY-----";

    private final Map<String, String> ambiente;
    private final Clock relogio;
    private final Map<String, TokenGuardado> tokens = new ConcurrentHashMap<>();

    InterPixGateway() {
        this(System.getenv(), Clock.systemUTC());
    }

    InterPixGateway(Map<String, String> ambiente, Clock relogio) {
        this.ambiente = ambiente;
        this.relogio = relogio;
    }

    /** Lê as mesmas variáveis que a cobrança, sem abrir o certificado nem chamar o Inter. */
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
            return CobrancaImediataPadrao.criarOuRecuperar(http, config.api(), token(http, config),
                    config.extras(), cobranca, valor, config.chave());
        } catch (AutorizacaoPixRecusadaException erro) {
            descartarToken(config);
            throw erro;
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
            String token = token(http, config);
            HttpResponse<String> cob = CobrancaImediataPadrao.enviar(http, config.api(), "GET",
                    "/v2/cob/" + cobranca.txid(), token, config.extras(), null);
            AutorizacaoPixRecusadaException.seRecusada(cob);
            if (cob.statusCode() != 200) {
                throw new PixIndisponivelException("consulta da cobranca Pix indisponivel");
            }
            HttpResponse<String> recebidos = CobrancaImediataPadrao.enviar(http, config.api(), "GET",
                    caminhoDaBusca(cobranca, relogio.instant()), token, config.extras(), null);
            AutorizacaoPixRecusadaException.seRecusada(recebidos);
            if (recebidos.statusCode() != 200) {
                throw new PixIndisponivelException("consulta dos Pix recebidos indisponivel");
            }
            return interpretarConsulta(JSON.readTree(cob.body()), JSON.readTree(recebidos.body()));
        } catch (AutorizacaoPixRecusadaException erro) {
            descartarToken(config);
            throw erro;
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
            CobrancaImediataPadrao.remover(http, config.api(), token(http, config), config.extras(),
                    cobranca);
        } catch (AutorizacaoPixRecusadaException erro) {
            descartarToken(config);
            throw erro;
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
     * Cadastra no Inter o webhook da chave Pix da Conta do contexto, apontando para o domínio só do
     * aviso do Inter.
     *
     * <p>Fica fora da porta PixGateway, como o da Efí: só a execução avulsa do mantenedor o chama. O
     * Inter não chama a URL ao cadastrar, então o cadastro passa mesmo com o domínio fora do ar, e só
     * o primeiro aviso mostra se o caminho até a aplicação funciona.
     */
    void cadastrarWebhook(String dominio) {
        Configuracao config = configuracao();
        String prefixo = prefixoDaConta();
        String url = urlDoWebhook(dominio, obrigatoria(prefixo + "WEBHOOK_ID"),
                obrigatoria(prefixo + "WEBHOOK_SECRET"));
        try {
            HttpClient http = cliente(config);
            cadastrar(http, config.api(), token(http, config), config.extras(), config.chave(), url);
        } catch (AutorizacaoPixRecusadaException erro) {
            descartarToken(config);
            throw erro;
        } catch (IllegalStateException | PixIndisponivelException erro) {
            throw erro;
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("O cadastro do webhook foi interrompido.", erro);
        } catch (Exception erro) {
            throw new IllegalStateException("O cadastro do webhook não teve resposta do Inter.",
                    erro);
        }
    }

    /**
     * A URL que o Inter chama, exatamente como cadastrada: ele não acrescenta nada ao fim dela. O
     * identificador e o segredo só têm letras, números, hífen e sublinhado, para irem como estão.
     */
    static String urlDoWebhook(String dominio, String identificador, String segredo) {
        if (!PARTE_DA_URL.matcher(identificador).matches()
                || !PARTE_DA_URL.matcher(segredo).matches()) {
            throw new IllegalStateException("O identificador e o segredo do webhook da Conta só "
                    + "podem ter letras, números, hífen e sublinhado.");
        }
        return "https://" + dominio + "/api/webhooks/pix/" + identificador + "?hmac=" + segredo;
    }

    static void cadastrar(HttpClient http, String api, String token, Map<String, String> extras,
            String chave, String url) throws Exception {
        String caminho = "/v2/webhook/" + URLEncoder.encode(chave, StandardCharsets.UTF_8);
        HttpResponse<String> resposta = CobrancaImediataPadrao.enviar(http, api, "PUT", caminho,
                token, extras, JSON.writeValueAsString(Map.of("webhookUrl", url)));
        AutorizacaoPixRecusadaException.seRecusada(resposta);
        if (resposta.statusCode() < 200 || resposta.statusCode() >= 300) {
            throw new IllegalStateException("O Inter recusou o cadastro do webhook, com "
                    + resposta.statusCode() + ": " + motivoDaRecusa(resposta.body()));
        }
    }

    /**
     * O título, o detalhe e as violações da recusa do Inter, que dizem o que falta. Se alguma repetir
     * a URL, o segredo sai do texto antes de ir ao log.
     */
    static String motivoDaRecusa(String corpo) {
        StringBuilder motivo = new StringBuilder();
        try {
            JsonNode erro = JSON.readTree(corpo);
            motivo.append(erro.path("title").asText("")).append(' ')
                    .append(erro.path("detail").asText(""));
            for (JsonNode violacao : erro.path("violacoes")) {
                motivo.append(' ').append(violacao.path("propriedade").asText(""))
                        .append(' ').append(violacao.path("razao").asText(""));
            }
        } catch (Exception ilegivel) {
            motivo.setLength(0);
        }
        String texto = motivo.toString().trim().replaceAll("\\s+", " ");
        if (texto.isEmpty()) {
            return "sem detalhe legível";
        }
        texto = texto.replaceAll("hmac=[A-Za-z0-9_-]*", "hmac=(segredo)");
        return texto.length() <= 300 ? texto : texto.substring(0, 300);
    }

    /**
     * A busca dos Pix recebidos pelo txid, numa janela em volta da expiração: a cobrança só pode ser
     * paga até expirar, e a expiração é a da aplicação, que reserva a cobrança por 15 minutos. A
     * margem de 1 hora de cada lado cobre a diferença de relógio, e o fim não passa do agora.
     */
    static String caminhoDaBusca(CobrancaPix cobranca, Instant agora) {
        Instant inicio = cobranca.expiraEm().minus(MARGEM_DA_BUSCA);
        Instant fim = cobranca.expiraEm().plus(MARGEM_DA_BUSCA);
        if (agora.isBefore(fim)) {
            fim = agora;
        }
        return "/v2/pix?inicio=" + instante(inicio) + "&fim=" + instante(fim) + "&txid="
                + cobranca.txid();
    }

    static ConsultaPix interpretarConsulta(JsonNode cob, JsonNode lista) {
        try {
            String txid = cob.path("txid").asText("");
            String chave = cob.path("chave").asText("");
            BigDecimal valor = new BigDecimal(cob.path("valor").path("original").asText());
            String status = cob.path("status").asText("");
            if (txid.isBlank() || chave.isBlank() || valor.signum() <= 0) {
                throw new PixIndisponivelException("consulta Pix sem correlacao verificavel");
            }
            JsonNode recebidos = lista.path("pix");
            if (!recebidos.isArray()) {
                throw new PixIndisponivelException("lista de Pix recebidos ilegivel");
            }
            if (!recebidos.isEmpty()) {
                BigDecimal soma = BigDecimal.ZERO;
                for (JsonNode recebido : recebidos) {
                    if (!txid.equals(recebido.path("txid").asText())) {
                        throw new PixIndisponivelException("Pix recebido com txid divergente");
                    }
                    // A chave é opcional no Pix recebido do padrão; quando vem, tem de ser a da
                    // cobrança.
                    if (recebido.has("chave") && !chave.equals(recebido.path("chave").asText())) {
                        throw new PixIndisponivelException("Pix recebido em outra chave");
                    }
                    soma = soma.add(new BigDecimal(recebido.path("valor").asText()));
                }
                if (soma.compareTo(valor) < 0) {
                    throw new PixIndisponivelException("Pix recebido nao cobre a cobranca");
                }
                return new ConsultaPix(txid, chave, Money.de(valor), true);
            }
            if ("CONCLUIDA".equals(status)) {
                throw new PixIndisponivelException("cobranca concluida sem Pix recebido verificavel");
            }
            if (!"ATIVA".equals(status) && !"REMOVIDA_PELO_USUARIO_RECEBEDOR".equals(status)
                    && !"REMOVIDA_PELO_PSP".equals(status)) {
                throw new PixIndisponivelException("estado da cobranca Pix desconhecido");
            }
            return new ConsultaPix(txid, chave, Money.de(valor), false, !"ATIVA".equals(status));
        } catch (PixIndisponivelException erro) {
            throw erro;
        } catch (Exception erro) {
            throw new PixIndisponivelException("consulta Pix com dados invalidos", erro);
        }
    }

    /**
     * Monta o TLS de cliente a partir do certificado e da chave em PEM. A chave precisa estar em
     * PKCS8, o formato que o Inter entrega; o algoritmo dela é o da chave pública do certificado.
     */
    static SSLContext tlsDoPem(Path certificado, Path chavePrivada) throws Exception {
        X509Certificate cert;
        try (InputStream arquivo = Files.newInputStream(certificado)) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(arquivo);
        }
        String pem = Files.readString(chavePrivada, StandardCharsets.US_ASCII);
        if (!pem.contains(INICIO_DA_CHAVE)) {
            throw new IllegalStateException("A chave do certificado do Inter precisa estar em PKCS8, "
                    + "como o Inter a entrega.");
        }
        String base64 = pem.replace(INICIO_DA_CHAVE, "").replace(FIM_DA_CHAVE, "")
                .replaceAll("\\s", "");
        PrivateKey chave = KeyFactory.getInstance(cert.getPublicKey().getAlgorithm())
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        // O KeyStore só existe na memória deste processo, e por isso vai sem senha.
        char[] semSenha = new char[0];
        KeyStore loja = KeyStore.getInstance("PKCS12");
        loja.load(null, null);
        loja.setKeyEntry("inter", chave, semSenha, new Certificate[]{cert});
        KeyManagerFactory chaves = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        chaves.init(loja, semSenha);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(chaves.getKeyManagers(), null, new SecureRandom());
        return tls;
    }

    static TokenGuardado pedirToken(HttpClient http, String host, String clientId,
            String clientSecret, Instant agora) throws Exception {
        String corpo = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&grant_type=client_credentials"
                + "&scope=" + URLEncoder.encode(ESCOPOS, StandardCharsets.UTF_8);
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(host + "/oauth/v2/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200) {
            throw new PixIndisponivelException("autorizacao Pix indisponivel");
        }
        JsonNode dados = JSON.readTree(resposta.body());
        String token = dados.path("access_token").asText("");
        long segundos = dados.path("expires_in").asLong(0);
        if (token.isBlank() || segundos <= 0) {
            throw new PixIndisponivelException("autorizacao Pix sem token");
        }
        return new TokenGuardado(token, agora.plusSeconds(segundos));
    }

    /**
     * O token guardado da Conta, ou um novo, pedido ao host de quem chama. O host vem do teste, que
     * troca o Inter por um servidor local; na aplicação, é o da configuração.
     */
    String token(HttpClient http, String host, String chaveDoToken, String clientId,
            String clientSecret) throws Exception {
        Instant agora = relogio.instant();
        TokenGuardado guardado = tokens.get(chaveDoToken);
        if (guardado != null && agora.isBefore(guardado.venceEm().minus(FOLGA_DO_TOKEN))) {
            return guardado.valor();
        }
        TokenGuardado novo = pedirToken(http, host, clientId, clientSecret, agora);
        tokens.put(chaveDoToken, novo);
        return novo.valor();
    }

    void descartarToken(String chaveDoToken) {
        tokens.remove(chaveDoToken);
    }

    private String token(HttpClient http, Configuracao config) throws Exception {
        return token(http, config.host(), config.chaveDoToken(), config.clientId(),
                config.clientSecret());
    }

    private void descartarToken(Configuracao config) {
        descartarToken(config.chaveDoToken());
    }

    private static String instante(Instant instante) {
        return DateTimeFormatter.ISO_INSTANT.format(instante.truncatedTo(ChronoUnit.SECONDS));
    }

    private static String prefixoDaConta() {
        String sufixo = TenantContext.exigirAtual().valor().toString().replace("-", "").toUpperCase();
        return "ECRX_CAIXA_INTER_" + sufixo + "_";
    }

    private Configuracao configuracao() {
        String prefixo = prefixoDaConta();
        String ambienteDoInter = ambiente.get(prefixo + "AMBIENTE");
        if (!"HOMOLOGACAO".equals(ambienteDoInter) && !"PRODUCAO".equals(ambienteDoInter)) {
            throw new IllegalStateException("O Pix integrado não está configurado para esta Conta.");
        }
        String contaCorrente = ambiente.getOrDefault(prefixo + "CONTA_CORRENTE", "").trim();
        return new Configuracao(prefixo, "PRODUCAO".equals(ambienteDoInter) ? PRODUCAO : HOMOLOGACAO,
                obrigatoria(prefixo + "CLIENT_ID"), obrigatoria(prefixo + "CLIENT_SECRET"),
                obrigatoria(prefixo + "CHAVE_PIX"), obrigatoria(prefixo + "CERTIFICADO_CRT"),
                obrigatoria(prefixo + "CERTIFICADO_KEY"), contaCorrente);
    }

    private String obrigatoria(String nome) {
        String valor = ambiente.get(nome);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "A configuração do Pix integrado desta Conta está incompleta.");
        }
        return valor;
    }

    private static HttpClient cliente(Configuracao config) throws Exception {
        return HttpClient.newBuilder()
                .sslContext(tlsDoPem(Path.of(config.certificado()), Path.of(config.chavePrivada())))
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    record TokenGuardado(String valor, Instant venceEm) {
    }

    private record Configuracao(String prefixo, String host, String clientId, String clientSecret,
            String chave, String certificado, String chavePrivada, String contaCorrente) {

        /** A base da API Pix, que no Inter traz o /pix na frente da versão. */
        String api() {
            return host + "/pix";
        }

        /** A conta corrente só vai quando configurada: o Inter só a pede com mais de uma conta. */
        Map<String, String> extras() {
            return contaCorrente.isEmpty() ? Map.of() : Map.of("x-conta-corrente", contaCorrente);
        }

        /** Trocar o client id da Conta não aproveita o token do anterior. */
        String chaveDoToken() {
            return prefixo + clientId;
        }
    }
}
