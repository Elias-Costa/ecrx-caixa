package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O tipo de negócio do seed chega do ambiente, e na VPS pelo prompt do {@code read}: o Enter sem
 * resposta exporta a variável vazia, e o compose a repassa assim mesmo.
 */
class PropriedadesDoSeedTest {

    @Test
    @DisplayName("o tipo de negócio vazio ou só com espaços vale como ausente")
    void tipoEmBrancoValeComoAusente() {
        assertThat(comTipo(null).tipoNegocio()).isNull();
        assertThat(comTipo("").tipoNegocio()).isNull();
        assertThat(comTipo("   ").tipoNegocio()).isNull();
    }

    @Test
    @DisplayName("o tipo de negócio preenchido chega sem os espaços das pontas")
    void tipoPreenchidoChegaSemEspacosNasPontas() {
        assertThat(comTipo(" cafeteria ").tipoNegocio()).isEqualTo("cafeteria");
    }

    private static PropriedadesDoSeed comTipo(String tipoNegocio) {
        return new PropriedadesDoSeed("Cafeteria Aurora", tipoNegocio, null,
                "seed.aurora@exemplo.test", "uma senha longa de seed");
    }
}
