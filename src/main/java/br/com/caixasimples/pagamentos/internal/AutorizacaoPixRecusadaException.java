package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import java.net.http.HttpResponse;

/**
 * O PSP recusou o token com 401. Para quem chama, é um Pix indisponível como outro; o adapter que
 * guarda o token o descarta ao ver esta recusa, para a próxima operação pedir um novo.
 */
class AutorizacaoPixRecusadaException extends PixIndisponivelException {

    AutorizacaoPixRecusadaException() {
        super("autorizacao Pix recusada");
    }

    static void seRecusada(HttpResponse<String> resposta) {
        if (resposta.statusCode() == 401) {
            throw new AutorizacaoPixRecusadaException();
        }
    }
}
