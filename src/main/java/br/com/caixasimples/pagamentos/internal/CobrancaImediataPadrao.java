package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Criar, recuperar e remover a cobrança imediata pela API Pix padrão do Banco Central, que os PSPs
 * integrados seguem do mesmo jeito. Cada adapter passa a própria base, que termina antes do /v2: a do
 * Inter traz o /pix na frente da versão, e a da Efí não.
 *
 * <p>Os cabeçalhos extras levam o que um PSP pede além do padrão, como a conta corrente do Inter
 * quando a integração tem mais de uma; sem eles, o pedido é o padrão puro.
 */
final class CobrancaImediataPadrao {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CobrancaImediataPadrao() {
    }

    static String criarOuRecuperar(HttpClient http, String base, String token,
            Map<String, String> extras, CobrancaPix cobranca, Money valor, String chave)
            throws Exception {
        String caminho = "/v2/cob/" + cobranca.txid();
        String corpo = JSON.writeValueAsString(Map.of(
                "calendario", Map.of("expiracao", 900),
                "valor", Map.of("original", valor.valor().toPlainString()),
                "chave", chave));
        HttpResponse<String> resposta = enviar(http, base, "PUT", caminho, token, extras, corpo);
        AutorizacaoPixRecusadaException.seRecusada(resposta);
        if (resposta.statusCode() == 409) {
            // O mesmo txid pode já ter sido criado antes da resposta anterior se perder.
            resposta = enviar(http, base, "GET", caminho, token, extras, null);
        }
        if (resposta.statusCode() < 200 || resposta.statusCode() >= 300) {
            throw new PixIndisponivelException("nao foi possivel verificar a cobranca Pix");
        }
        JsonNode dados = JSON.readTree(resposta.body());
        if (!cobranca.txid().equals(dados.path("txid").asText())
                || !chave.equals(dados.path("chave").asText())
                || valor.valor().compareTo(new BigDecimal(dados.path("valor")
                        .path("original").asText("-1"))) != 0
                || !"ATIVA".equals(dados.path("status").asText())) {
            throw new PixIndisponivelException("cobranca Pix divergente ou sem estado ativo");
        }
        String copiaECola = dados.path("pixCopiaECola").asText("");
        if (copiaECola.isBlank()) {
            long locationId = dados.path("loc").path("id").asLong(0);
            if (locationId <= 0) {
                throw new PixIndisponivelException("cobranca Pix sem QR verificavel");
            }
            HttpResponse<String> qr = enviar(http, base, "GET",
                    "/v2/loc/" + locationId + "/qrcode", token, extras, null);
            if (qr.statusCode() != 200) {
                throw new PixIndisponivelException("QR Pix indisponivel");
            }
            copiaECola = JSON.readTree(qr.body()).path("qrcode").asText("");
        }
        if (copiaECola.isBlank()) {
            throw new PixIndisponivelException("QR Pix vazio");
        }
        return copiaECola;
    }

    static void remover(HttpClient http, String base, String token, Map<String, String> extras,
            CobrancaPix cobranca) throws Exception {
        HttpResponse<String> resposta = enviar(http, base, "PATCH", "/v2/cob/" + cobranca.txid(),
                token, extras, "{\"status\":\"REMOVIDA_PELO_USUARIO_RECEBEDOR\"}");
        AutorizacaoPixRecusadaException.seRecusada(resposta);
        // Um PATCH repetido pode receber 400/409 se a cobrança já foi removida ou paga.
        // Em ambos os casos a decisão pertence ao GET que o caso de uso faz em seguida.
        if (resposta.statusCode() != 200 && resposta.statusCode() != 400
                && resposta.statusCode() != 409) {
            throw new PixIndisponivelException("remocao da cobranca Pix indisponivel");
        }
    }

    static HttpResponse<String> enviar(HttpClient http, String base, String metodo,
            String caminho, String token, Map<String, String> extras, String corpo)
            throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(base + caminho))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        extras.forEach(pedido::header);
        if ("PUT".equals(metodo) || "PATCH".equals(metodo)) {
            pedido.header("Content-Type", "application/json")
                    .method(metodo, HttpRequest.BodyPublishers.ofString(corpo));
        } else {
            pedido.GET();
        }
        return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }
}
