package br.com.caixasimples.contas;

/**
 * Os recursos que a identidade lista para o aplicativo decidir o que mostrar no menu e na venda.
 *
 * <p>Com um plano só, todos valem, no teste e nos períodos pagos. No só leitura sai só o Pix
 * integrado, porque a cobrança nova é recusada; relatórios, estoque e usuários continuam, porque não
 * põem nem tiram dinheiro do balcão. A lista existe porque o aplicativo instalado antes a lê para
 * montar o menu.
 */
public enum RecursoDoPlano {

    /** Faturamento, mais vendidos e fluxo de caixa (RF21 a RF24). */
    RELATORIOS,

    /** Ajuste, estoque mínimo e consulta de saldo, e ligar o controle de estoque (RF17, RF19, RF20). */
    ESTOQUE,

    /** Criar mais usuários na Conta (RF29). */
    MULTIUSUARIO,

    /**
     * Cobrar pelo Pix integrado ao provedor, confirmado sem o operador conferir o extrato (RF27). A
     * cobrança já reservada continua sendo consultada e confirmada no só leitura, porque o cliente
     * pode estar pagando.
     */
    PIX_INTEGRADO
}
