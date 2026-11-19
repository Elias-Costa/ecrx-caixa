package br.com.caixasimples.shared;

/**
 * A falha de um ouvinte assíncrono que o próprio ouvinte já registrou no log, relançada só para o
 * registro de publicação dos eventos marcar a entrega como falha e ela voltar no reenvio.
 *
 * <p>O tratador dos métodos assíncronos registra como erro toda exceção que sobe deles, e cada linha
 * de erro dispara o alerta do mantenedor. Uma falha esperada e já anotada, como o provedor de e-mail
 * fora do ar, alertaria a cada nova tentativa; este tipo diz ao tratador que a linha já saiu.
 * Qualquer outra exceção continua erro.
 *
 * <p>A mensagem é fixa e a causa não é guardada, de propósito: quem a lança já anotou o que
 * importa, e o texto da causa pode trazer dado pessoal, como um endereço de e-mail.
 */
public class FalhaRegistradaParaReenvio extends RuntimeException {

    public FalhaRegistradaParaReenvio() {
        super("Falha já registrada pelo ouvinte; a entrega fica para o reenvio.", null, false, false);
    }
}
