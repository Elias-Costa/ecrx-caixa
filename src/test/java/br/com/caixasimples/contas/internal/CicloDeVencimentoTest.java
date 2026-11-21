package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.caixasimples.contas.SituacaoDoPlano;
import br.com.caixasimples.contas.internal.CicloDeVencimento.Periodo;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As datas do teste e do ciclo do plano, com datas fixas: a classe não lê relógio, e quem chama informa o
 * dia de hoje.
 */
class CicloDeVencimentoTest {

    @Test
    @DisplayName("mês sem o dia do ciclo vence no último dia, inclusive em fevereiro bissexto")
    void mesCurtoVenceNoUltimoDia() {
        assertThat(CicloDeVencimento.vencimentoNoMes(YearMonth.of(2026, 2), 31))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(CicloDeVencimento.vencimentoNoMes(YearMonth.of(2028, 2), 31))
                .isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(CicloDeVencimento.vencimentoNoMes(YearMonth.of(2026, 4), 31))
                .isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(CicloDeVencimento.vencimentoNoMes(YearMonth.of(2026, 4), 15))
                .isEqualTo(LocalDate.of(2026, 4, 15));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CicloDeVencimento.vencimentoNoMes(YearMonth.of(2026, 4), 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CicloDeVencimento.vencimentoNoMes(YearMonth.of(2026, 4), 32));
    }

    @Test
    @DisplayName("o ciclo do dia 31 vence em 28 de fevereiro e volta ao dia 31 em março")
    void voltaAoDiaOriginal() {
        LocalDate janeiro = LocalDate.of(2026, 1, 31);
        int dia = janeiro.getDayOfMonth();

        LocalDate fevereiro = CicloDeVencimento.seguinte(janeiro, dia);
        LocalDate marco = CicloDeVencimento.seguinte(fevereiro, dia);
        LocalDate abril = CicloDeVencimento.seguinte(marco, dia);
        LocalDate maio = CicloDeVencimento.seguinte(abril, dia);

        assertThat(fevereiro).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(marco).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(abril).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(maio).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("o ciclo do dia 29 vence no dia 28 do fevereiro seguinte e volta ao 29")
    void adesaoNoDiaBissexto() {
        int dia = 29;

        assertThat(CicloDeVencimento.seguinte(LocalDate.of(2028, 2, 29), dia))
                .isEqualTo(LocalDate.of(2028, 3, 29));
        assertThat(CicloDeVencimento.seguinte(LocalDate.of(2029, 1, 29), dia))
                .isEqualTo(LocalDate.of(2029, 2, 28));
        assertThat(CicloDeVencimento.seguinte(LocalDate.of(2029, 2, 28), dia))
                .isEqualTo(LocalDate.of(2029, 3, 29));
    }

    @Test
    @DisplayName("o teste vai até o décimo quarto dia e vence no décimo quinto, também na virada do mês")
    void fimDoTeste() {
        assertThat(CicloDeVencimento.fimDoTeste(LocalDate.of(2026, 12, 1)))
                .isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(CicloDeVencimento.fimDoTeste(LocalDate.of(2026, 12, 20)))
                .isEqualTo(LocalDate.of(2027, 1, 3));
    }

    @Test
    @DisplayName("vencimento numa sexta: a tolerância vai até a quarta, e o só leitura começa na quinta")
    void vencimentoNumaSexta() {
        LocalDate sexta = LocalDate.of(2026, 11, 20);

        assertThat(CicloDeVencimento.inicioDoSomenteLeitura(sexta))
                .isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(CicloDeVencimento.situacao(sexta, false, LocalDate.of(2026, 11, 25)))
                .isEqualTo(SituacaoDoPlano.VENCIDO);
        assertThat(CicloDeVencimento.situacao(sexta, false, LocalDate.of(2026, 11, 26)))
                .isEqualTo(SituacaoDoPlano.SOMENTE_LEITURA);
    }

    @Test
    @DisplayName("o feriado conta como dia útil: vencimento na véspera de Natal, só leitura no dia 30")
    void feriadoContaComoDiaUtil() {
        LocalDate quinta = LocalDate.of(2026, 12, 24);

        assertThat(CicloDeVencimento.inicioDoSomenteLeitura(quinta))
                .isEqualTo(LocalDate.of(2026, 12, 30));
        assertThat(CicloDeVencimento.situacao(quinta, true, LocalDate.of(2026, 12, 29)))
                .isEqualTo(SituacaoDoPlano.VENCIDO);
        assertThat(CicloDeVencimento.situacao(quinta, true, LocalDate.of(2026, 12, 30)))
                .isEqualTo(SituacaoDoPlano.SOMENTE_LEITURA);
    }

    @Test
    @DisplayName("vencimento no fim de semana conta a tolerância a partir da segunda")
    void vencimentoNoFimDeSemana() {
        assertThat(CicloDeVencimento.inicioDoSomenteLeitura(LocalDate.of(2026, 11, 21)))
                .as("sábado")
                .isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(CicloDeVencimento.inicioDoSomenteLeitura(LocalDate.of(2026, 11, 22)))
                .as("domingo")
                .isEqualTo(LocalDate.of(2026, 11, 26));
        assertThat(CicloDeVencimento.inicioDoSomenteLeitura(LocalDate.of(2026, 11, 23)))
                .as("segunda")
                .isEqualTo(LocalDate.of(2026, 11, 27));
    }

    @Test
    @DisplayName("num período pago, o aviso começa sete dias antes; no teste, três")
    void avisoDoPagoEDoTeste() {
        LocalDate terca = LocalDate.of(2026, 3, 10);

        assertThat(CicloDeVencimento.situacao(terca, false, LocalDate.of(2026, 3, 2)))
                .isEqualTo(SituacaoDoPlano.EM_DIA);
        assertThat(CicloDeVencimento.situacao(terca, false, LocalDate.of(2026, 3, 3)))
                .isEqualTo(SituacaoDoPlano.A_VENCER);
        assertThat(CicloDeVencimento.situacao(terca, false, LocalDate.of(2026, 3, 9)))
                .isEqualTo(SituacaoDoPlano.A_VENCER);
        assertThat(CicloDeVencimento.situacao(terca, false, LocalDate.of(2026, 3, 10)))
                .isEqualTo(SituacaoDoPlano.VENCIDO);
        assertThat(CicloDeVencimento.situacao(terca, false, LocalDate.of(2026, 3, 13)))
                .isEqualTo(SituacaoDoPlano.VENCIDO);
        assertThat(CicloDeVencimento.situacao(terca, false, LocalDate.of(2026, 3, 14)))
                .isEqualTo(SituacaoDoPlano.SOMENTE_LEITURA);
        assertThat(CicloDeVencimento.situacao(terca, false, LocalDate.of(2026, 9, 1)))
                .isEqualTo(SituacaoDoPlano.SOMENTE_LEITURA);

        assertThat(CicloDeVencimento.situacao(terca, true, LocalDate.of(2026, 3, 6)))
                .isEqualTo(SituacaoDoPlano.EM_DIA);
        assertThat(CicloDeVencimento.situacao(terca, true, LocalDate.of(2026, 3, 7)))
                .isEqualTo(SituacaoDoPlano.A_VENCER);
        assertThat(CicloDeVencimento.situacao(terca, true, LocalDate.of(2026, 3, 9)))
                .isEqualTo(SituacaoDoPlano.A_VENCER);
        assertThat(CicloDeVencimento.situacao(terca, true, LocalDate.of(2026, 3, 10)))
                .isEqualTo(SituacaoDoPlano.VENCIDO);
    }

    @Test
    @DisplayName("a renovação paga o mês que começa no vencimento, mesmo no ciclo do dia 31")
    void renovacaoPagaOMesDoVencimento() {
        assertThat(CicloDeVencimento.periodoDaRenovacao(LocalDate.of(2026, 3, 10), 10))
                .isEqualTo(new Periodo(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 4, 10)));
        assertThat(CicloDeVencimento.periodoDaRenovacao(LocalDate.of(2026, 2, 28), 31))
                .isEqualTo(new Periodo(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)));
    }

    @Test
    @DisplayName("período vazio ou invertido não existe")
    void periodoVazio() {
        LocalDate dia = LocalDate.of(2026, 3, 10);

        assertThatIllegalArgumentException().isThrownBy(() -> new Periodo(dia, dia));
        assertThatIllegalArgumentException().isThrownBy(() -> new Periodo(dia, dia.minusDays(1)));
    }
}
