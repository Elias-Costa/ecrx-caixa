package br.com.caixasimples.pagamentos.application;

/** A tentativa permanece pendente quando o resultado do PSP não é verificável. */
public class PixIndisponivelException extends RuntimeException {
    public PixIndisponivelException(String mensagem) {
        super(mensagem);
    }

    public PixIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
