package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.contas.SituacaoDoPlano;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O teste, o ciclo do plano, o só leitura e a suspensão do acesso na raiz {@link Conta}: o que cada
 * mudança recusa, e que a recusa não deixa nada pela metade.
 *
 * <p>Teste de unidade puro, com datas fixas: a raiz não lê relógio, e quem chama informa o dia de
 * hoje. O caso de uso recusa antes boa parte destes casos, então a raiz é a segunda linha; a
 * terceira são as restrições do ciclo no banco, provadas na migração do plano único.
 */
class ContaTest {

    /** Meio-dia de 01/12/2026 no balcão: o teste vence em 15/12, uma terça. */
    private static final Instant CRIACAO = Instant.parse("2026-12-01T15:00:00Z");

    private static final LocalDate FIM_DO_TESTE = LocalDate.of(2026, 12, 15);

    /** O sábado depois do terceiro dia útil da tolerância: quarta, quinta e sexta. */
    private static final LocalDate INICIO_DO_SOMENTE_LEITURA = LocalDate.of(2026, 12, 19);

    @Test
    @DisplayName("a Conta nasce no teste do plano Completo, que vence no décimo quinto dia, contado no fuso do balcão")
    void nasceNoTeste() {
        Conta conta = nova(CRIACAO);
        conferirCiclo(conta, 15, FIM_DO_TESTE, true);
        assertThat(conta.getPlano()).isEqualTo(Plano.COMPLETO);
        assertThat(conta.inicioDoSomenteLeitura()).isEqualTo(INICIO_DO_SOMENTE_LEITURA);

        // 23h30 de 01/12 no balcão já é 02/12 em UTC; o teste conta do dia do balcão.
        Conta tarde = nova(Instant.parse("2026-12-02T02:30:00Z"));
        conferirCiclo(tarde, 15, FIM_DO_TESTE, true);
    }

    @Test
    @DisplayName("no teste, o aviso começa três dias antes do fim, e o só leitura depois de três dias úteis")
    void situacoesDoTeste() {
        Conta conta = nova(CRIACAO);

        assertThat(conta.situacao(LocalDate.of(2026, 12, 11))).isEqualTo(SituacaoDoPlano.EM_DIA);
        assertThat(conta.situacao(LocalDate.of(2026, 12, 12))).isEqualTo(SituacaoDoPlano.A_VENCER);
        assertThat(conta.situacao(FIM_DO_TESTE)).isEqualTo(SituacaoDoPlano.VENCIDO);
        assertThat(conta.situacao(LocalDate.of(2026, 12, 18))).isEqualTo(SituacaoDoPlano.VENCIDO);
        assertThat(conta.somenteLeitura(LocalDate.of(2026, 12, 18))).isFalse();
        assertThat(conta.somenteLeitura(INICIO_DO_SOMENTE_LEITURA)).isTrue();
    }

    @Test
    @DisplayName("os recursos valem todos, no teste e no período pago; no só leitura sai só o Pix integrado")
    void recursosNoSomenteLeitura() {
        Conta conta = nova(CRIACAO);

        assertThat(conta.recursos(LocalDate.of(2026, 12, 18)))
                .containsExactlyInAnyOrder(RecursoDoPlano.values());
        assertThat(conta.recursos(INICIO_DO_SOMENTE_LEITURA)).containsExactlyInAnyOrder(
                RecursoDoPlano.RELATORIOS, RecursoDoPlano.ESTOQUE, RecursoDoPlano.MULTIUSUARIO);
    }

    @Test
    @DisplayName("a renovação no teste paga o mês que começa no fim dele, encerra o teste e mantém o dia")
    void renovacaoNoTeste() {
        Conta conta = nova(CRIACAO);
        CicloDeVencimento.Periodo primeiro = conta.periodoDaRenovacao(LocalDate.of(2026, 12, 12));
        assertThat(primeiro).isEqualTo(
                new CicloDeVencimento.Periodo(FIM_DO_TESTE, LocalDate.of(2027, 1, 15)));

        conta.renovar(primeiro.fim());
        conferirCiclo(conta, 15, LocalDate.of(2027, 1, 15), false);

        assertThatIllegalStateException()
                .isThrownBy(() -> conta.renovar(LocalDate.of(2027, 1, 15)))
                .withMessageContaining("já está pago");
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.renovar(LocalDate.of(2027, 1, 14)))
                .withMessageContaining("já está pago");
        conferirCiclo(conta, 15, LocalDate.of(2027, 1, 15), false);
    }

    @Test
    @DisplayName("pago na tolerância, o mês conta do vencimento; no só leitura não há renovação")
    void renovacaoNaToleranciaEReconsultaNoSomenteLeitura() {
        Conta conta = nova(CRIACAO);

        assertThat(conta.periodoDaRenovacao(LocalDate.of(2026, 12, 18)))
                .isEqualTo(new CicloDeVencimento.Periodo(FIM_DO_TESTE, LocalDate.of(2027, 1, 15)));
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.periodoDaRenovacao(INICIO_DO_SOMENTE_LEITURA));
    }

    @Test
    @DisplayName("pago no só leitura, o ciclo recomeça no dia do código, e só no só leitura")
    void retomadaRecomecaOCiclo() {
        Conta conta = nova(CRIACAO);
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.retomar(LocalDate.of(2026, 12, 18)));
        conferirCiclo(conta, 15, FIM_DO_TESTE, true);

        LocalDate segunda = LocalDate.of(2026, 12, 21);
        CicloDeVencimento.Periodo primeiro = conta.retomar(segunda);

        assertThat(primeiro).isEqualTo(new CicloDeVencimento.Periodo(segunda, LocalDate.of(2027, 1, 21)));
        conferirCiclo(conta, 21, LocalDate.of(2027, 1, 21), false);
        assertThat(conta.situacao(segunda)).isEqualTo(SituacaoDoPlano.EM_DIA);
    }

    @Test
    @DisplayName("o nome do negócio é corrigido sem os espaços das pontas; em branco ou acima de 120 caracteres, recusado, também na criação")
    void nomeDoNegocio() {
        Conta conta = nova(CRIACAO);
        conta.corrigirNomeDoNegocio("  Cafeteria Aurora do Centro  ");
        assertThat(conta.getNomeNegocio()).isEqualTo("Cafeteria Aurora do Centro");

        assertThatIllegalArgumentException().isThrownBy(() -> conta.corrigirNomeDoNegocio(" "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta.corrigirNomeDoNegocio("a".repeat(121)))
                .withMessage("nome do negócio com 121 caracteres; o limite é 120.");
        assertThat(conta.getNomeNegocio()).isEqualTo("Cafeteria Aurora do Centro");
        // A correção não mexe no ciclo do plano.
        conferirCiclo(conta, 15, FIM_DO_TESTE, true);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Conta("a".repeat(121), null, CRIACAO))
                .withMessageContaining("o limite é 120");
    }

    @Test
    @DisplayName("a suspensão do acesso começa uma vez e só se desfaz na Conta suspensa, sem mexer no plano")
    void suspensaoDoAcesso() {
        Conta conta = nova(CRIACAO);
        Instant inicio = Instant.parse("2026-12-05T15:00:00Z");
        assertThatIllegalStateException()
                .isThrownBy(conta::reativar)
                .withMessage("a Conta não está suspensa");

        conta.suspender(inicio);
        assertThat(conta.isSuspensa()).isTrue();
        // Suspender de novo trocaria o instante que o aviso ao administrador informa.
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.suspender(inicio.plusSeconds(60)))
                .withMessageContaining("já está suspensa");
        assertThat(conta.getSuspensaEm()).isEqualTo(inicio);
        conferirCiclo(conta, 15, FIM_DO_TESTE, true);

        conta.reativar();
        assertThat(conta.isSuspensa()).isFalse();
        assertThat(conta.getSuspensaEm()).isNull();
    }

    private static Conta nova(Instant criadoEm) {
        return new Conta("Cafeteria Aurora", null, criadoEm);
    }

    /** As duas datas do ciclo e a marca do teste. */
    private static void conferirCiclo(Conta conta, int diaDeVencimento, LocalDate proximoVencimento,
            boolean emTeste) {
        assertThat(conta)
                .extracting(Conta::getDiaDeVencimento, Conta::getProximoVencimento, Conta::isEmTeste)
                .containsExactly(diaDeVencimento, proximoVencimento, emTeste);
    }
}
