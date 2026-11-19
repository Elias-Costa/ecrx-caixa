package br.com.caixasimples.relatorios.internal;

import java.math.BigDecimal;

/**
 * A soma e a contagem das sangrias de uma sessão. Sem o motivo, de propósito: é texto livre, pode
 * citar uma pessoa, e o resumo não leva nome de pessoa.
 *
 * @param total nulo quando não houve sangria, porque a soma de zero linhas é nula em SQL
 */
public record TotalDeSangrias(BigDecimal total, Long quantidade) {
}
