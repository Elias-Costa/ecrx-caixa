package br.com.caixasimples.pagamentos.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.pagamentos.domain.ConsultaPix;
import br.com.caixasimples.shared.Money;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * O contrato HTTP do Inter, medido no ambiente de homologação dele, sem credenciais de uma Conta real.
 * Os corpos de resposta seguem o que o Inter devolveu na medição.
 */
class InterPixGatewayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TXID = "ade7dc7cc3e24ca992578a513bdb5d53";

    @Test
    void tokenEGuardadoRenovadoPertoDeVencerEDescartadoNaRecusa() throws Exception {
        AtomicInteger pedidos = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        HttpServer servidor = servidor("/oauth/v2/token", troca -> {
            corpo.set(ler(troca));
            responder(troca, 200, "{\"access_token\":\"token-" + pedidos.incrementAndGet()
                    + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        try {
            RelogioDeTeste relogio = new RelogioDeTeste(Instant.parse("2026-11-17T12:00:00Z"));
            InterPixGateway inter = new InterPixGateway(Map.of(), relogio);
            HttpClient http = HttpClient.newHttpClient();
            String host = endereco(servidor);

            assertThat(inter.token(http, host, "conta-a", "id", "segredo")).isEqualTo("token-1");
            relogio.agora = relogio.agora.plus(Duration.ofMinutes(54));
            assertThat(inter.token(http, host, "conta-a", "id", "segredo")).isEqualTo("token-1");
            assertThat(pedidos).hasValue(1);

            // A menos de 5 minutos do fim, o guardado não serve mais.
            relogio.agora = relogio.agora.plus(Duration.ofMinutes(2));
            assertThat(inter.token(http, host, "conta-a", "id", "segredo")).isEqualTo("token-2");

            // Outra Conta, ou outro client id, não aproveita o token de ninguém.
            assertThat(inter.token(http, host, "conta-b", "id", "segredo")).isEqualTo("token-3");

            inter.descartarToken("conta-a");
            assertThat(inter.token(http, host, "conta-a", "id", "segredo")).isEqualTo("token-4");
            assertThat(corpo.get()).contains("grant_type=client_credentials",
                    "scope=cob.write+cob.read+pix.read+webhook.write+webhook.read");
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void tokenSemValidadeOuRecusadoNaoFicaGuardado() throws Exception {
        AtomicInteger pedidos = new AtomicInteger();
        HttpServer servidor = servidor("/oauth/v2/token", troca -> {
            if (pedidos.incrementAndGet() == 1) {
                responder(troca, 200, "{\"access_token\":\"token\"}");
            } else {
                responder(troca, 401, "requested scope is not registered for this client");
            }
        });
        try {
            InterPixGateway inter = new InterPixGateway(Map.of(), Clock.systemUTC());
            HttpClient http = HttpClient.newHttpClient();
            assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                    inter.token(http, endereco(servidor), "conta", "id", "segredo"));
            assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                    inter.token(http, endereco(servidor), "conta", "id", "segredo"));
            assertThat(pedidos).hasValue(2);
        } finally {
            servidor.stop(0);
        }
    }

    /** No ambiente de homologação, a cobrança paga continua ATIVA; o pago vem da lista. */
    @Test
    void pagoVemDaListaDePixRecebidosMesmoComACobrancaAtiva() {
        ConsultaPix consulta = InterPixGateway.interpretarConsulta(cob("ATIVA", "1.00"),
                lista("{\"endToEndId\":\"E1\",\"txid\":\"" + TXID + "\",\"valor\":\"1.00\","
                        + "\"chave\":\"chave-a\",\"horario\":\"2026-11-17T16:49:22.201Z\"}"));
        assertThat(consulta.pago()).isTrue();
        assertThat(consulta.valor()).isEqualTo(Money.de("1.00"));
        assertThat(consulta.chaveRecebedora()).isEqualTo("chave-a");

        // Dois Pix da mesma cobrança somam; a chave é opcional no Pix recebido.
        assertThat(InterPixGateway.interpretarConsulta(cob("ATIVA", "10.00"), lista(
                "{\"txid\":\"" + TXID + "\",\"valor\":\"4.00\"}",
                "{\"txid\":\"" + TXID + "\",\"valor\":\"6.00\",\"chave\":\"chave-a\"}")).pago())
                .isTrue();
    }

    @Test
    void semPixRecebidoDistingueAtivaDeRemovida() {
        ConsultaPix ativa = InterPixGateway.interpretarConsulta(cob("ATIVA", "1.00"), lista());
        assertThat(ativa.pago()).isFalse();
        assertThat(ativa.removida()).isFalse();
        ConsultaPix removida = InterPixGateway.interpretarConsulta(
                cob("REMOVIDA_PELO_USUARIO_RECEBEDOR", "1.00"), lista());
        assertThat(removida.pago()).isFalse();
        assertThat(removida.removida()).isTrue();
        assertThat(InterPixGateway.interpretarConsulta(cob("REMOVIDA_PELO_PSP", "1.00"), lista())
                .removida()).isTrue();
    }

    @Test
    void resultadoIncertoNuncaViraPago() {
        // Concluída no Inter, mas sem o Pix na lista: incerta, e não paga nem livre.
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                InterPixGateway.interpretarConsulta(cob("CONCLUIDA", "1.00"), lista()));
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                InterPixGateway.interpretarConsulta(cob("ATIVA", "1.00"),
                        lista("{\"txid\":\"outro0000000000000000000000000\",\"valor\":\"1.00\"}")));
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                InterPixGateway.interpretarConsulta(cob("ATIVA", "1.00"),
                        lista("{\"txid\":\"" + TXID + "\",\"valor\":\"1.00\",\"chave\":\"outra\"}")));
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                InterPixGateway.interpretarConsulta(cob("ATIVA", "10.00"),
                        lista("{\"txid\":\"" + TXID + "\",\"valor\":\"9.99\"}")));
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                InterPixGateway.interpretarConsulta(cob("STATUS_NOVO", "1.00"), lista()));
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                InterPixGateway.interpretarConsulta(cob("ATIVA", "1.00"),
                        JSON.readTree("{\"pix\":null}")));
    }

    @Test
    void buscaDosPixRecebidosFicaEmVoltaDaExpiracaoSemPassarDoAgora() {
        Instant expiraEm = Instant.parse("2026-11-17T17:04:21.381Z");
        CobrancaPix cobranca = new CobrancaPix(TXID, "chave-a", expiraEm, null,
                CobrancaPix.Estado.AGUARDANDO);

        assertThat(InterPixGateway.caminhoDaBusca(cobranca, Instant.parse("2026-11-17T16:50:24.9Z")))
                .isEqualTo("/v2/pix?inicio=2026-11-17T16:04:21Z&fim=2026-11-17T16:50:24Z&txid="
                        + TXID);
        assertThat(InterPixGateway.caminhoDaBusca(cobranca, Instant.parse("2026-11-20T09:00:00Z")))
                .isEqualTo("/v2/pix?inicio=2026-11-17T16:04:21Z&fim=2026-11-17T18:04:21Z&txid="
                        + TXID);
    }

    /** A base do Inter traz o /pix na frente da versão, e a conta corrente só vai quando configurada. */
    @Test
    void criarVaiNaBaseDoInterComAContaCorrenteERecusa401() throws Exception {
        CobrancaPix cobranca = CobrancaPix.aguardando(UUID.randomUUID(), "chave-a",
                Instant.now().plusSeconds(900));
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> conta = new AtomicReference<>();
        HttpServer servidor = servidor("/pix/v2/cob/" + cobranca.txid(), troca -> {
            conta.set(troca.getRequestHeaders().getFirst("x-conta-corrente"));
            ler(troca);
            if (chamadas.incrementAndGet() == 1) {
                responder(troca, 201, "{\"txid\":\"" + cobranca.txid() + "\",\"chave\":\"chave-a\","
                        + "\"valor\":{\"original\":\"1.00\",\"modalidadeAlteracao\":0},"
                        + "\"status\":\"ATIVA\",\"pixCopiaECola\":\"codigo-do-inter\"}");
            } else {
                responder(troca, 401, "");
            }
        });
        try {
            HttpClient http = HttpClient.newHttpClient();
            String api = endereco(servidor) + "/pix";
            assertThat(CobrancaImediataPadrao.criarOuRecuperar(http, api, "token",
                    Map.of("x-conta-corrente", "94948622"), cobranca, Money.de("1.00"), "chave-a"))
                    .isEqualTo("codigo-do-inter");
            assertThat(conta).hasValue("94948622");
            assertThatExceptionOfType(AutorizacaoPixRecusadaException.class).isThrownBy(() ->
                    CobrancaImediataPadrao.criarOuRecuperar(http, api, "token", Map.of(), cobranca,
                            Money.de("1.00"), "chave-a"));
            assertThat(conta.get()).isNull();
        } finally {
            servidor.stop(0);
        }
    }

    /** O Inter chama a URL exatamente como cadastrada; a recusa chega sem o segredo dela. */
    @Test
    void cadastroDoWebhookUsaAUrlSemSufixoEDevolveARecusaSemOSegredo() throws Exception {
        String url = InterPixGateway.urlDoWebhook("pix-inter.loja-da-esquina.com.br", "config-a",
                "segredo_de_teste-1");
        assertThat(url).isEqualTo("https://pix-inter.loja-da-esquina.com.br/api/webhooks/pix/"
                + "config-a?hmac=segredo_de_teste-1");
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> caminho = new AtomicReference<>();
        AtomicReference<String> corpo = new AtomicReference<>();
        HttpServer servidor = servidor("/pix/v2/webhook/", troca -> {
            caminho.set(troca.getRequestURI().getRawPath());
            corpo.set(ler(troca));
            if (chamadas.incrementAndGet() == 1) {
                responder(troca, 204, null);
            } else {
                responder(troca, 400, "{\"title\":\"Dados inválidos.\",\"detail\":\"Verifique os "
                        + "dados.\",\"violacoes\":[{\"razao\":\"URL inválida: " + url + "\","
                        + "\"propriedade\":\"webhookUrl\",\"valor\":\"" + url + "\"}]}");
            }
        });
        try {
            HttpClient http = HttpClient.newHttpClient();
            String api = endereco(servidor) + "/pix";
            InterPixGateway.cadastrar(http, api, "token", Map.of(),
                    "114bc975-663f-4a28-9338-4d925c09a52c", url);
            assertThat(caminho).hasValue("/pix/v2/webhook/114bc975-663f-4a28-9338-4d925c09a52c");
            assertThat(corpo.get()).isEqualTo("{\"webhookUrl\":\"" + url + "\"}");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> InterPixGateway.cadastrar(http, api, "token", Map.of(),
                            "chave", url))
                    .withMessageContaining("400")
                    .withMessageContaining("Dados inválidos.")
                    .withMessageContaining("webhookUrl")
                    .withMessageContaining("hmac=(segredo)")
                    .withMessageNotContaining("segredo_de_teste-1");
        } finally {
            servidor.stop(0);
        }
        for (String ruim : new String[]{"com&e", "com espaço", "com/barra", "com?", "", "ç"}) {
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                    InterPixGateway.urlDoWebhook("pix-inter.localhost", ruim, "f00d"));
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                    InterPixGateway.urlDoWebhook("pix-inter.localhost", "a1", ruim));
        }
        assertThat(InterPixGateway.motivoDaRecusa("<html>erro</html>"))
                .isEqualTo("sem detalhe legível");
    }

    /**
     * O certificado e a chave do Inter chegam em PEM, a chave em PKCS8. O par é gerado aqui pelo
     * keytool do JDK, sem chave nenhuma guardada no repositório.
     */
    @Test
    void montaOTlsDoCertificadoEDaChaveEmPem(@TempDir Path pasta) throws Exception {
        Path loja = pasta.resolve("par.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool")
                .toString();
        Process processo = new ProcessBuilder(keytool, "-genkeypair", "-alias", "par",
                "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Loja da Esquina",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", loja.toString(),
                "-storepass", "teste1").redirectErrorStream(true).start();
        assertThat(processo.waitFor()).isZero();
        KeyStore par = KeyStore.getInstance("PKCS12");
        try (FileInputStream arquivo = new FileInputStream(loja.toFile())) {
            par.load(arquivo, "teste1".toCharArray());
        }
        Base64.Encoder base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        Path crt = pasta.resolve("inter.crt");
        Files.writeString(crt, "-----BEGIN CERTIFICATE-----\n"
                + base64.encodeToString(par.getCertificate("par").getEncoded())
                + "\n-----END CERTIFICATE-----\n");
        PrivateKey privada = (PrivateKey) par.getKey("par", "teste1".toCharArray());
        Path key = pasta.resolve("inter.key");
        Files.writeString(key, InterPixGateway.INICIO_DA_CHAVE + "\n"
                + base64.encodeToString(privada.getEncoded()) + "\n" + InterPixGateway.FIM_DA_CHAVE
                + "\n");

        assertThat(InterPixGateway.tlsDoPem(crt, key)).isNotNull();

        // A chave no formato antigo, sem PKCS8, é recusada com a explicação.
        Path antiga = pasta.resolve("antiga.key");
        Files.writeString(antiga, "-----BEGIN RSA " + "PRIVATE KEY-----\nAAAA\n");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> InterPixGateway.tlsDoPem(crt, antiga))
                .withMessageContaining("PKCS8");
    }

    private static tools.jackson.databind.JsonNode cob(String status, String valor) {
        return JSON.readTree("{\"txid\":\"" + TXID + "\",\"chave\":\"chave-a\",\"status\":\"" + status
                + "\",\"valor\":{\"original\":\"" + valor + "\",\"modalidadeAlteracao\":0},"
                + "\"pix\":[]}");
    }

    private static tools.jackson.databind.JsonNode lista(String... recebidos) {
        return JSON.readTree("{\"parametros\":{},\"pix\":[" + String.join(",", recebidos) + "]}");
    }

    private interface Resposta {
        void responder(HttpExchange troca) throws java.io.IOException;
    }

    private static HttpServer servidor(String contexto, Resposta resposta) throws Exception {
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext(contexto, resposta::responder);
        servidor.start();
        return servidor;
    }

    private static String endereco(HttpServer servidor) {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    private static String ler(HttpExchange troca) throws java.io.IOException {
        return new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void responder(HttpExchange troca, int status, String corpo)
            throws java.io.IOException {
        if (corpo == null) {
            troca.sendResponseHeaders(status, -1);
            troca.close();
            return;
        }
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        troca.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var saida = troca.getResponseBody()) {
            saida.write(bytes);
        }
    }

    private static final class RelogioDeTeste extends Clock {
        Instant agora;

        RelogioDeTeste(Instant agora) {
            this.agora = agora;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
