package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.SituacaoDoPlano;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * As datas do teste e do ciclo mensal do plano.
 *
 * <p>Só data e aritmética, sem relógio: quem chama informa o dia de hoje no fuso do balcão, o que
 * deixa cada regra verificável com uma data fixa.
 *
 * <p>Toda Conta começa num teste de quatorze dias, que vence como um período pago: o fim do teste é
 * o primeiro vencimento, e o dia dele passa a ser o dia do vencimento de todos os meses. Num mês que
 * não tem esse dia, vence no último, e o mês seguinte volta ao dia original: com vencimento em 31 de
 * janeiro, vence em 28 de fevereiro e em 31 de março. Cada período vai de um vencimento, inclusive,
 * ao seguinte, exclusive.
 *
 * <p>Depois de cada vencimento há três dias úteis de tolerância, de segunda a sexta, e o feriado
 * conta como dia útil: o calendário de feriados muda de cidade para cidade, e a regra precisa ser a
 * mesma para todas. No dia seguinte ao terceiro, a Conta fica só leitura até pagar.
 */
public final class CicloDeVencimento {

    /** A duração do teste, contada do dia em que a Conta é criada. */
    public static final int DIAS_DO_TESTE = 14;

    /** Dias antes do vencimento de um período pago em que o aviso ao administrador começa. */
    public static final int DIAS_DE_AVISO = 7;

    /**
     * Dias antes do fim do teste em que o aviso começa. É mais curto que o do período pago porque o
     * teste inteiro tem duas semanas: com sete dias, metade dele seria de aviso.
     */
    public static final int DIAS_DE_AVISO_DO_TESTE = 3;

    /** Dias úteis depois do vencimento em que a Conta ainda vende sem o pagamento. */
    public static final int DIAS_UTEIS_DE_TOLERANCIA = 3;

    private CicloDeVencimento() {
        // so datas
    }

    /**
     * O fim do teste de uma Conta criada no dia informado: o primeiro dia sem ele, que é o primeiro
     * vencimento. Criada em 1º de dezembro, o teste vai até o dia 14 e vence no dia 15.
     */
    public static LocalDate fimDoTeste(LocalDate diaDaCriacao) {
        Objects.requireNonNull(diaDaCriacao, "dia da criacao nao pode ser nulo");
        return diaDaCriacao.plusDays(DIAS_DO_TESTE);
    }

    /** O vencimento de um mês: o dia do ciclo, ou o último dia do mês, se o mês for mais curto. */
    public static LocalDate vencimentoNoMes(YearMonth mes, int diaDoCiclo) {
        Objects.requireNonNull(mes, "mes nao pode ser nulo");
        if (diaDoCiclo < 1 || diaDoCiclo > 31) {
            throw new IllegalArgumentException("dia do ciclo fora do mes: " + diaDoCiclo);
        }
        return mes.atDay(Math.min(diaDoCiclo, mes.lengthOfMonth()));
    }

    /**
     * O vencimento do mês seguinte ao de uma data do ciclo. Do dia em que o ciclo recomeça, dá o
     * primeiro vencimento; de um vencimento, o fim do período que começa nele.
     */
    public static LocalDate seguinte(LocalDate dataDoCiclo, int diaDoCiclo) {
        return vencimentoNoMes(YearMonth.from(dataDoCiclo).plusMonths(1), diaDoCiclo);
    }

    /**
     * O primeiro dia em só leitura, se o pagamento não chegar: o dia seguinte ao terceiro dia útil
     * depois do vencimento. Com vencimento numa sexta, a tolerância vai até a quarta, e o só leitura
     * começa na quinta.
     */
    public static LocalDate inicioDoSomenteLeitura(LocalDate vencimento) {
        Objects.requireNonNull(vencimento, "vencimento nao pode ser nulo");
        LocalDate dia = vencimento;
        int uteis = 0;
        while (uteis < DIAS_UTEIS_DE_TOLERANCIA) {
            dia = dia.plusDays(1);
            if (diaUtil(dia)) {
                uteis++;
            }
        }
        return dia.plusDays(1);
    }

    private static boolean diaUtil(LocalDate dia) {
        DayOfWeek semana = dia.getDayOfWeek();
        return semana != DayOfWeek.SATURDAY && semana != DayOfWeek.SUNDAY;
    }

    /**
     * Onde a Conta está no dia de hoje.
     *
     * @param vencimento o fim, exclusive, do teste ou do último período pago
     * @param emTeste    se nenhum período foi pago ainda, o que encurta o aviso
     */
    public static SituacaoDoPlano situacao(LocalDate vencimento, boolean emTeste, LocalDate hoje) {
        Objects.requireNonNull(vencimento, "vencimento nao pode ser nulo");
        Objects.requireNonNull(hoje, "hoje nao pode ser nulo");
        int diasDeAviso = emTeste ? DIAS_DE_AVISO_DO_TESTE : DIAS_DE_AVISO;
        if (hoje.isBefore(vencimento.minusDays(diasDeAviso))) {
            return SituacaoDoPlano.EM_DIA;
        }
        if (hoje.isBefore(vencimento)) {
            return SituacaoDoPlano.A_VENCER;
        }
        if (hoje.isBefore(inicioDoSomenteLeitura(vencimento))) {
            return SituacaoDoPlano.VENCIDO;
        }
        return SituacaoDoPlano.SOMENTE_LEITURA;
    }

    /**
     * O período que uma renovação paga: o que começa no vencimento ainda não pago, mesmo antes dele,
     * para quem renova adiantado. Quem paga na tolerância paga o mês que venceu e fica com o dia do
     * vencimento, porque usou a Conta nesses dias. No só leitura o pagamento não é renovação: o
     * ciclo recomeça no dia em que o código é aplicado.
     *
     * @param vencimento o fim, exclusive, do teste ou do último período pago
     */
    public static Periodo periodoDaRenovacao(LocalDate vencimento, int diaDoCiclo) {
        Objects.requireNonNull(vencimento, "vencimento nao pode ser nulo");
        return new Periodo(vencimento, seguinte(vencimento, diaDoCiclo));
    }

    /**
     * Um período do ciclo.
     *
     * @param inicio o primeiro dia
     * @param fim    o dia seguinte ao último, que é o vencimento
     */
    public record Periodo(LocalDate inicio, LocalDate fim) {

        public Periodo {
            Objects.requireNonNull(inicio, "inicio nao pode ser nulo");
            Objects.requireNonNull(fim, "fim nao pode ser nulo");
            if (!inicio.isBefore(fim)) {
                throw new IllegalArgumentException("periodo vazio: " + inicio + " a " + fim);
            }
        }
    }
}
