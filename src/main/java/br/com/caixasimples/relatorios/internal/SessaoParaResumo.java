package br.com.caixasimples.relatorios.internal;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * O que o resumo do caixa fechado diz da própria sessão: quando abriu, quando a gaveta foi contada e
 * a diferença apurada. Sem o dono da sessão, de propósito: o resumo não leva nome de pessoa.
 *
 * @param diferenca positivo é falta na gaveta, negativo é sobra
 */
public record SessaoParaResumo(Instant abertaEm, Instant fechadaEm, BigDecimal diferenca) {
}
