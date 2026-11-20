package br.com.caixasimples.relatorios.web;

import br.com.caixasimples.relatorios.application.ResumoMensalService;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada HTTP do resumo mensal das vendas para o contador (RF67); a autorização de ADMIN, o
 * plano e a recusa do mês que não terminou ficam no caso de uso.
 *
 * <p>Responde o arquivo, e não JSON: o aplicativo só o baixa. O tipo da resposta vai no cabeçalho
 * já escrito aqui, e não numa restrição do mapeamento, para a recusa continuar saindo como Problem
 * Details, que é o que a tela lê, qualquer que seja o tipo que o pedido aceite. O nome do arquivo
 * vai no cabeçalho para quem salvar a resposta direto.
 *
 * <p>O mês chega como {@code 2026-11}; outro formato é recusado com 400 pelo próprio Spring.
 */
@RestController
@RequestMapping("/api/relatorios/resumo-mensal")
class ResumoMensalController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ResumoMensalService resumo;

    ResumoMensalController(ResumoMensalService resumo) {
        this.resumo = resumo;
    }

    @GetMapping
    ResponseEntity<byte[]> doMes(@RequestParam YearMonth mes) {
        byte[] arquivo = ArquivoDoResumoMensal.conteudo(resumo.doMes(mes));
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(ArquivoDoResumoMensal.nome(mes))
                        .build()
                        .toString())
                .body(arquivo);
    }
}
