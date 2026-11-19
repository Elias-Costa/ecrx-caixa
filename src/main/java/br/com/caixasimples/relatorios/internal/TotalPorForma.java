package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import java.math.BigDecimal;

/** A soma das parcelas de uma forma de pagamento nas vendas de uma sessão. */
public record TotalPorForma(FormaPagamento forma, BigDecimal total) {
}
