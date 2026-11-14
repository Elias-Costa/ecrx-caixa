package br.com.caixasimples.vendas.web;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.WebhookPixAutenticador;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.EventoDeSeguranca;
import br.com.caixasimples.vendas.application.VendaPixService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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
 * Callback sem JWT: o certificado de cliente da Efí, conferido pelo proxy, e o segredo da URL
 * identificam a Conta.
 *
 * <p>Quem termina o TLS é o proxy da máquina. Num domínio só do aviso, ele exige um certificado de
 * cliente emitido pelas autoridades do webhook da Efí e escreve a impressão digital dele no
 * cabeçalho {@code X-Ecrx-Caixa-Certificado-Efi}; no domínio do aplicativo, ele apaga esse
 * cabeçalho antes de encaminhar. A aplicação confia no cabeçalho como confia no endereço de origem
 * que o proxy repassa: a porta dela não é publicada, e só o proxy, pela rede interna, chega a ela.
 * Sem o cabeçalho, o aviso não passou pela conferência e é recusado.
 *
 * <p>O corpo só é lido depois das duas conferências. Como argumento do método, ele viraria árvore na
 * memória antes de qualquer recusa, porque o Spring resolve os argumentos antes de chamar o método,
 * e o aviso anônimo custaria o mesmo que o legítimo. Só JSON é aceito, já na escolha da rota: um
 * formulário faria o servidor ler o corpo para achar o parâmetro do segredo.
 *
 * <p>As duas recusas vão ao log como evento de segurança, com o motivo e a origem, e entram no alerta
 * de rajada: nada limita quantos avisos falsos chegam. Nem o identificador nem o segredo da URL vão,
 * porque o par é o que autentica o aviso.
 *
 * <p>O aviso autenticado sem a lista pix responde 204 sem efeito. É a notificação de teste que a
 * Efí manda ao cadastrar o webhook, e o cadastro só passa com uma resposta de sucesso; o que ela
 * manda com outra chave não diz respeito a cobrança imediata.
 */
@RestController
@RequestMapping("/api/webhooks/pix")
class WebhookPixController {
    static final String CERTIFICADO_CONFERIDO = "X-Ecrx-Caixa-Certificado-Efi";

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
        String certificado = requisicao.getHeader(CERTIFICADO_CONFERIDO);
        if (certificado == null || certificado.isBlank()) {
            registrarRecusa("sem_certificado", requisicao);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ProblemDetail.forStatus(HttpStatus.FORBIDDEN));
        }
        ContaId conta = autenticador.autenticar(identificador, hmac);
        if (conta == null) {
            registrarRecusa("hmac", requisicao);
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
                pix.receberNotificacao(conta, recebido.path("txid").asText());
            }
            return ResponseEntity.noContent().build();
        } catch (PixIndisponivelException erro) {
            // A Efí pode reentregar; resultado incerto nunca vira confirmação local.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
    }

    private static void registrarRecusa(String motivo, HttpServletRequest requisicao) {
        EventoDeSeguranca.de("webhook.recusado")
                .com("motivo", motivo)
                .daOrigem(requisicao.getRemoteAddr())
                .registrarRecusa();
    }
}
