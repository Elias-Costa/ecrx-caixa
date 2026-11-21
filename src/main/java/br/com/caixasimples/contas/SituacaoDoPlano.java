package br.com.caixasimples.contas;

/**
 * Onde a Conta está no dia de hoje do balcão, no teste ou num período pago.
 *
 * <p>O aviso começa sete dias antes do vencimento de um período pago e três antes do fim do teste.
 * Depois do vencimento há três dias úteis de tolerância, com tudo funcionando; no dia seguinte ao
 * terceiro, a Conta fica só leitura até o pagamento. Com vencimento numa sexta, a tolerância vai até
 * a quarta, e o só leitura começa na quinta.
 *
 * <p>O aplicativo instalado também conhece dois valores que o servidor não manda mais, o do plano
 * sem mensalidade e o da suspensão dos recursos pagos, porque guarda a identidade que leu antes.
 */
public enum SituacaoDoPlano {

    /** Antes do aviso. */
    EM_DIA,

    /** Do primeiro dia do aviso até a véspera do vencimento: o administrador vê o aviso. */
    A_VENCER,

    /** Do dia do vencimento ao terceiro dia útil depois dele, com tudo ainda funcionando. */
    VENCIDO,

    /**
     * A partir do dia seguinte ao terceiro dia útil depois do vencimento, até o pagamento: a Conta não
     * começa venda, não abre caixa nem mexe no dinheiro dele, mas fecha o caixa aberto, consulta tudo
     * e mantém o cadastro.
     */
    SOMENTE_LEITURA
}
