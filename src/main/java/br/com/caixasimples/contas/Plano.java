package br.com.caixasimples.contas;

/**
 * Plano contratado pela conta.
 *
 * <p>O plano é um só, o completo, com mensalidade, e toda Conta começa num teste dele. O valor da
 * mensalidade vem do ambiente, não do código.
 */
public enum Plano {

    /**
     * Não é plano de Conta nenhuma, e o banco recusa a Conta nele. Continua aqui porque o aplicativo
     * instalado antes pode mandá-lo num pedido de plano, que é recusado com uma frase, e não com um
     * erro de leitura do pedido.
     */
    GRATIS,

    /**
     * Cadastro, vendas, caixa, relatórios, estoque, multiusuário e Pix integrado. A emissão fiscal
     * ainda não existe, e a tela do plano avisa isso.
     */
    COMPLETO;

    /** Como o plano aparece nas mensagens a quem usa o sistema. */
    public String nomeExibido() {
        return switch (this) {
            case GRATIS -> "Gratuito";
            case COMPLETO -> "Completo";
        };
    }
}
