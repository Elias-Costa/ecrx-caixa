package br.com.caixasimples.vendas.web;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.AutenticacaoDoAviso;
import br.com.caixasimples.pagamentos.domain.WebhookPixAutenticador;
import br.com.caixasimples.shared.EventoDeSeguranca;
import br.com.caixasimples.vendas.application.VendaPixService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Callback sem JWT: o certificado de cliente do PSP, conferido pelo proxy, e o segredo da URL
 * identificam a Conta.
 *
 * <p>Quem termina o TLS é o proxy da máquina. Cada PSP chega por um domínio só do aviso dele, em que o
 * proxy exige um certificado de cliente emitido pela autoridade do PSP e escreve o que conferiu num
 * cabeçalho com o prefixo {@code X-Ecrx-Caixa-Certificado-}; os outros domínios apagam esses
 * cabeçalhos antes de encaminhar. A aplicação confia neles como confia no endereço de origem que o
 * proxy repassa: a porta dela não é publicada, e só o proxy, pela rede interna, chega a ela. Sem
 * nenhum, o aviso não passou pela conferência e é recusado. Qual certificado cada Conta exige é
 * decidido em pagamentos, que conhece o PSP dela; esta rota só junta os cabeçalhos.
 *
 * <p>O corpo só é lido depois das duas conferências. Como argumento do método, ele viraria árvore na
 * memória antes de qualquer recusa, porque o Spring resolve os argumentos antes de chamar o método,
 * e o aviso anônimo custaria o mesmo que o legítimo. Só JSON é aceito, já na escolha da rota: um
 * formulário faria o servidor ler o corpo para achar o parâmetro do segredo.
 *
 * <p>As recusas vão ao log como evento de segurança, com o motivo e a origem, e entram no alerta de
 * rajada: nada limita quantos avisos falsos chegam. Nem o identificador nem o segredo da URL vão,
 * porque o par é o que autentica o aviso.
 *
 * <p>O aviso autenticado sem a lista pix responde 204 sem efeito. É a notificação de teste que a
 * Efí manda ao cadastrar o webhook, e o cadastro só passa com uma resposta de sucesso; o que chega com
 * outra chave não diz respeito a cobrança imediata.
 */
@RestController
@RequestMapping("/api/webhooks/pix")
class WebhookPixController {
    static final String PREFIXO_DO_CERTIFICADO = "x-ecrx-caixa-certificado-";

    private final WebhookPixAutenticador autenticador;
    private final VendaPixService pix;
    private final ObjectMapper json;

    WebhookPixController(WebhookPixAutenticador autenticador, VendaPixService pix,
            ObjectMapper json) {
        this.autenticador = autenticador;
        this.pix = pix;
        this.json = json;
    }

    @PostMapping(path = {"/{identificador}", "/{identificador}/pix"},
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> receber(@PathVariable String identificador, @RequestParam String hmac,
            HttpServletRequest requisicao) throws IOException {
        Map<String, String> certificados = certificadosConferidos(requisicao);
        if (certificados.isEmpty()) {
            registrarRecusa("sem_certificado", requisicao);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ProblemDetail.forStatus(HttpStatus.FORBIDDEN));
        }
        AutenticacaoDoAviso autenticacao = autenticador.autenticar(identificador, hmac,
                certificados);
        if (!autenticacao.aceita()) {
            registrarRecusa(autenticacao.recusa(), requisicao);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ProblemDetail.forStatus(HttpStatus.FORBIDDEN));
        }
        JsonNode aviso;
        try {
            aviso = json.readTree(requisicao.getInputStream());
        } catch (JacksonException ilegivel) {
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
        }
        // O corpo vazio é lido como nó ausente, que também não tem a lista.
        JsonNode recebidos = aviso.path("pix");
        if (recebidos.isMissingNode()) {
            return ResponseEntity.noContent().build();
        }
        if (!recebidos.isArray() || recebidos.size() > 100) {
            return ResponseEntity.badRequest()
                    .body(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
        }
        for (JsonNode recebido : recebidos) {
            String txid = recebido.path("txid").asText("");
            if (!txid.matches("[a-zA-Z0-9]{26,35}")) {
                return ResponseEntity.badRequest()
                        .body(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
            }
        }
        try {
            for (JsonNode recebido : recebidos) {
                pix.receberNotificacao(autenticacao.conta(), recebido.path("txid").asText());
            }
            return ResponseEntity.noContent().build();
        } catch (PixIndisponivelException erro) {
            // O PSP pode reentregar; resultado incerto nunca vira confirmação local.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
    }

    /**
     * Os cabeçalhos de certificado que o proxy escreveu, com o nome em minúsculas, porque o nome de
     * cabeçalho não distingue maiúsculas. Um valor em branco não conta: o proxy só escreve depois de
     * conferir, e o que ele escreve nunca sai vazio.
     */
    private static Map<String, String> certificadosConferidos(HttpServletRequest requisicao) {
        Map<String, String> certificados = new HashMap<>();
        for (String nome : Collections.list(requisicao.getHeaderNames())) {
            String minusculo = nome.toLowerCase(Locale.ROOT);
            String valor = requisicao.getHeader(nome);
            if (minusculo.startsWith(PREFIXO_DO_CERTIFICADO) && valor != null && !valor.isBlank()) {
                certificados.put(minusculo, valor.trim());
            }
        }
        return certificados;
    }

    private static void registrarRecusa(String motivo, HttpServletRequest requisicao) {
        EventoDeSeguranca.de("webhook.recusado")
                .com("motivo", motivo)
                .daOrigem(requisicao.getRemoteAddr())
                .registrarRecusa();
    }
}
