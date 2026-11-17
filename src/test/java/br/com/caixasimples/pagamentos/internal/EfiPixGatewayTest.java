package br.com.caixasimples.pagamentos.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.shared.Money;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exercita o contrato HTTP da Efí sem credenciais de uma Conta real. */
class EfiPixGatewayTest {
    @Test
    void putRepetidoConsultaMesmoTxidEConfereValorEChave() throws Exception {
        UUID pagamentoId = UUID.randomUUID();
        CobrancaPix cobranca = CobrancaPix.aguardando(pagamentoId, "chave-teste",
                Instant.now().plusSeconds(900));
        AtomicInteger put = new AtomicInteger();
        AtomicInteger get = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v2/cob/" + cobranca.txid(), troca -> {
            int status;
            String resposta;
            if ("PUT".equals(troca.getRequestMethod())) {
                corpo.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                status = put.incrementAndGet() == 1 ? 201 : 409;
            } else {
                get.incrementAndGet();
                status = 200;
            }
            resposta = status == 409 ? "{\"nome\":\"txid_duplicado\"}"
                    : "{\"txid\":\"" + cobranca.txid() + "\",\"chave\":\"chave-teste\","
                    + "\"valor\":{\"original\":\"12.50\"},\"status\":\"ATIVA\","
                    + "\"pixCopiaECola\":\"codigo-pix\"}";
            byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(status, bytes.length);
            try (var saida = troca.getResponseBody()) { saida.write(bytes); }
        });
        servidor.start();
        try {
            String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
            HttpClient http = HttpClient.newHttpClient();
            for (int i = 0; i < 2; i++) {
                assertThat(CobrancaImediataPadrao.criarOuRecuperar(http, base, "token", Map.of(),
                        cobranca, Money.de("12.50"), "chave-teste")).isEqualTo("codigo-pix");
            }
            assertThat(put).hasValue(2);
            assertThat(get).hasValue(1);
            assertThat(corpo.get()).contains("\"expiracao\":900", "\"original\":\"12.50\"");
            assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                    CobrancaImediataPadrao.criarOuRecuperar(http, base, "token", Map.of(),
                            cobranca, Money.de("12.00"), "chave-teste"));
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void patchDeRemocaoUsaStatusDocumentadoEAceitaRepeticaoJaResolvida() throws Exception {
        CobrancaPix cobranca = CobrancaPix.aguardando(UUID.randomUUID(), "chave-teste",
                Instant.now().plusSeconds(900));
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v2/cob/" + cobranca.txid(), troca -> {
            corpo.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = chamadas.incrementAndGet() == 1 ? 200 : 400;
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(status, bytes.length);
            try (var saida = troca.getResponseBody()) { saida.write(bytes); }
        });
        servidor.start();
        try {
            String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
            HttpClient http = HttpClient.newHttpClient();
            CobrancaImediataPadrao.remover(http, base, "token", Map.of(), cobranca);
            CobrancaImediataPadrao.remover(http, base, "token", Map.of(), cobranca);
            assertThat(chamadas).hasValue(2);
            assertThat(corpo.get()).contains("REMOVIDA_PELO_USUARIO_RECEBEDOR");
        } finally {
            servidor.stop(0);
        }
    }

    /**
     * O cadastro do webhook vai na chave Pix da Conta, codificada no caminho, e a recusa da Efí chega
     * ao mantenedor com o nome e a mensagem dela, sem o segredo da URL.
     */
    @Test
    void cadastroDoWebhookFazPutNaChaveEDevolveARecusaSemOSegredo() throws Exception {
        String url = EfiPixGateway.urlDoWebhook("pix.loja-da-esquina.com.br", "config-a",
                "segredo_de_teste-1");
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> metodo = new AtomicReference<>();
        AtomicReference<String> caminho = new AtomicReference<>();
        AtomicReference<String> autorizacao = new AtomicReference<>();
        AtomicReference<String> corpo = new AtomicReference<>();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v2/webhook/", troca -> {
            metodo.set(troca.getRequestMethod());
            caminho.set(troca.getRequestURI().getRawPath());
            autorizacao.set(troca.getRequestHeaders().getFirst("Authorization"));
            corpo.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status;
            String resposta;
            switch (chamadas.incrementAndGet()) {
                case 1 -> {
                    status = 201;
                    resposta = "{\"webhookUrl\":\"" + url + "\"}";
                }
                case 2 -> {
                    status = 400;
                    resposta = "{\"nome\":\"webhook_invalido\",\"mensagem\":\"A autenticação de "
                            + "TLS mútuo não está configurada na URL informada\"}";
                }
                default -> {
                    status = 400;
                    resposta = "{\"nome\":\"webhook_invalido\",\"mensagem\":\"Falhou em " + url
                            + "\"}";
                }
            }
            byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(status, bytes.length);
            try (var saida = troca.getResponseBody()) { saida.write(bytes); }
        });
        servidor.start();
        try {
            String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
            HttpClient http = HttpClient.newHttpClient();

            EfiPixGateway.cadastrar(http, base, "token", "+5571999990000", url);
            assertThat(metodo).hasValue("PUT");
            assertThat(caminho).hasValue("/v2/webhook/%2B5571999990000");
            assertThat(autorizacao).hasValue("Bearer token");
            assertThat(corpo.get()).isEqualTo("{\"webhookUrl\":\"https://pix.loja-da-esquina.com.br"
                    + "/api/webhooks/pix/config-a?hmac=segredo_de_teste-1&ignorar=\"}");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> EfiPixGateway.cadastrar(http, base, "token", "chave", url))
                    .withMessageContaining("400")
                    .withMessageContaining("webhook_invalido A autenticação de TLS mútuo")
                    .withMessageNotContaining("segredo_de_teste-1");
            // Se a Efí um dia repetir a URL na mensagem, o segredo sai antes de ir ao log.
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> EfiPixGateway.cadastrar(http, base, "token", "chave", url))
                    .withMessageContaining("hmac=(segredo)")
                    .withMessageNotContaining("segredo_de_teste-1");
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void urlDoWebhookSoAceitaIdentificadorESegredoSemCodificacao() {
        assertThat(EfiPixGateway.urlDoWebhook("webhook-pix.localhost", "a1_B-2", "f00d"))
                .isEqualTo("https://webhook-pix.localhost/api/webhooks/pix/a1_B-2?hmac=f00d&ignorar=");
        for (String ruim : new String[]{"com&e", "com espaço", "com/barra", "com?", "", "ç"}) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> EfiPixGateway.urlDoWebhook("webhook-pix.localhost", ruim,
                            "f00d"));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> EfiPixGateway.urlDoWebhook("webhook-pix.localhost", "a1",
                            ruim));
        }
        assertThat(EfiPixGateway.motivoDaRecusa("<html>erro</html>"))
                .isEqualTo("sem detalhe legível");
    }
}
