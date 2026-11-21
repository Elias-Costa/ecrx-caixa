package br.com.caixasimples.relatorios.application;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.relatorios.application.FaturamentoService.Faturamento;
import br.com.caixasimples.relatorios.application.FaturamentoService.Filtros;
import br.com.caixasimples.relatorios.internal.ItemParaRateio;
import br.com.caixasimples.relatorios.internal.ItemVendaParaRelatorioRepository;
import br.com.caixasimples.relatorios.internal.TotaisDeVendas;
import br.com.caixasimples.relatorios.internal.VendaParaRelatorioRepository;
import br.com.caixasimples.shared.FormatoDeTela;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.vendas.StatusVenda;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * O resumo das vendas de um mês fechado, que o administrador baixa e manda ao contador (RF67): o
 * faturamento, separado em mercadoria e serviço e por forma de pagamento, o fiado recebido e as
 * vendas canceladas. Sem nome de cliente nem de operador.
 *
 * <p><strong>Um resumo só, igual para toda Conta.</strong> O sistema não sabe se o negócio apura
 * pela venda ou pelo recebimento, e não pergunta: o fiado vendido, que é a linha Fiado das formas, e
 * o fiado recebido saem lado a lado, e cada contador usa o que o regime do cliente pede.
 *
 * <p><strong>Só mês que já terminou.</strong> O mês é a unidade em que a receita se apura, e um mês
 * pela metade mandado ao contador por engano vira erro de apuração. O mês corrente e os seguintes
 * são recusados com uma frase de tela.
 *
 * <p><strong>Os números são os da tela de relatórios.</strong> O total e as formas vêm do
 * {@link FaturamentoService}, com as mesmas regras: a venda conta no dia da conclusão, no fuso do
 * balcão, e a forma soma a parcela, e não a venda. As vendas canceladas são as que tinham concluído
 * no mês, porque a comanda cancelada antes de concluir nunca foi venda. O fiado recebido conta pelo
 * dia do recebimento.
 *
 * <p><strong>Mercadoria e serviço fecham com o total.</strong> O tipo vem do produto de cada item,
 * que o cadastro não troca depois de criado, e o desconto da venda inteira se reparte entre os itens
 * pelo subtotal, em {@link RateioDoDescontoDaVenda}.
 *
 * <p><strong>Uma transação em leitura repetível.</strong> São várias consultas, e no isolamento
 * padrão do Postgres cada uma veria o banco do próprio instante: um cancelamento entre duas delas,
 * possível porque o caixa aberto no mês anterior ainda aceita cancelar, faria as formas não somarem
 * o total. Em leitura repetível todas leem o mesmo retrato, e só leem.
 *
 * <p>É relatório do administrador, como os outros (RF30): a pergunta vem antes de qualquer
 * consulta. A conta vem sempre do contexto (RNF05).
 */
@Service
public class ResumoMensalService {

    private final FaturamentoService faturamento;
    private final VendaParaRelatorioRepository vendas;
    private final ItemVendaParaRelatorioRepository itens;

    ResumoMensalService(FaturamentoService faturamento, VendaParaRelatorioRepository vendas,
            ItemVendaParaRelatorioRepository itens) {
        this.faturamento = faturamento;
        this.vendas = vendas;
        this.itens = itens;
    }

    /**
     * O resumo de um mês do calendário que já terminou no fuso do balcão.
     *
     * @param mes obrigatório; anterior ao mês corrente
     * @throws IllegalArgumentException se o mês ainda não terminou, com a frase que a tela mostra
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResumoMensal doMes(YearMonth mes) {
        UsuarioContext.exigirAdmin();
        Objects.requireNonNull(mes, "mes nao pode ser nulo");
        if (!mes.isBefore(YearMonth.now(FusoDeReferencia.DO_BALCAO))) {
            throw new IllegalArgumentException("O mês " + FormatoDeTela.mes(mes)
                    + " ainda não terminou. Escolha um mês anterior.");
        }
        Periodo periodo = new Periodo(mes.atDay(1), mes.atEndOfMonth());

        Faturamento total = faturamento.doPeriodo(periodo.inicio(), periodo.fim());
        List<TotalDaForma> porForma = new ArrayList<>();
        for (FormaPagamento forma : FormaPagamento.values()) {
            Faturamento daForma = faturamento.doPeriodo(periodo.inicio(), periodo.fim(),
                    Filtros.porForma(forma));
            porForma.add(new TotalDaForma(forma, daForma.total(), daForma.quantidadeDeVendas()));
        }

        RateioDoDescontoDaVenda.Liquido porTipo = porTipo(periodo);

        TotaisDeVendas recebidos = vendas.recebimentosEntre(periodo.inicioInclusivo(),
                periodo.fimExclusivo());
        TotaisDeVendas canceladas = vendas.totaisEntre(StatusVenda.CANCELADA,
                periodo.inicioInclusivo(), periodo.fimExclusivo(), null);

        return new ResumoMensal(mes, periodo.inicio(), periodo.fim(),
                total.total(), total.quantidadeDeVendas(),
                porTipo.mercadoria(), porTipo.servico(),
                List.copyOf(porForma),
                emReais(recebidos), recebidos.quantidade(),
                emReais(canceladas), canceladas.quantidade());
    }

    /**
     * Mercadoria e serviço do mês: os itens de cada venda concluída, agrupados pela venda na ordem
     * em que a consulta os traz, que é a da comanda, e o desconto da venda repartido entre eles.
     */
    private RateioDoDescontoDaVenda.Liquido porTipo(Periodo periodo) {
        Map<UUID, List<ItemParaRateio>> porVenda = itens.itensDasVendasEntre(StatusVenda.CONCLUIDA,
                        periodo.inicioInclusivo(), periodo.fimExclusivo())
                .stream()
                .collect(Collectors.groupingBy(ItemParaRateio::vendaId, LinkedHashMap::new,
                        Collectors.toList()));

        Money mercadoria = Money.ZERO;
        Money servico = Money.ZERO;
        for (List<ItemParaRateio> daVenda : porVenda.values()) {
            List<RateioDoDescontoDaVenda.Item> paraRatear = daVenda.stream()
                    .map(item -> new RateioDoDescontoDaVenda.Item(Money.de(item.subtotal()),
                            item.tipo()))
                    .toList();
            RateioDoDescontoDaVenda.Liquido liquidoDaVenda = RateioDoDescontoDaVenda.repartir(
                    paraRatear, Money.de(daVenda.get(0).descontoDaVenda()));
            mercadoria = mercadoria.somar(liquidoDaVenda.mercadoria());
            servico = servico.somar(liquidoDaVenda.servico());
        }
        return new RateioDoDescontoDaVenda.Liquido(mercadoria, servico);
    }

    /** A soma de zero linhas é nula em SQL; para quem lê o resumo, um mês sem nenhuma é zero. */
    private static Money emReais(TotaisDeVendas totais) {
        return totais.total() == null ? Money.ZERO : Money.de(totais.total());
    }

    /**
     * O faturamento de uma forma de pagamento no mês.
     *
     * @param forma              a forma
     * @param total              a soma das parcelas dessa forma
     * @param quantidadeDeVendas quantas vendas tiveram ao menos uma parcela nela; a venda paga em
     *                           duas formas conta nas duas
     */
    public record TotalDaForma(FormaPagamento forma, Money total, long quantidadeDeVendas) {
    }

    /**
     * Os números do resumo, sem formato de arquivo: quem o escreve é a camada web.
     *
     * @param mes                      o mês do calendário
     * @param inicio                   o primeiro dia dele
     * @param fim                      o último dia dele, incluído
     * @param faturamento              a soma das vendas concluídas no mês
     * @param quantidadeDeVendas       quantas vendas concluídas
     * @param mercadoria               a parte do faturamento em produtos, depois do desconto da venda
     * @param servico                  a parte em serviços; com a mercadoria, soma o faturamento
     * @param porForma                 uma linha por forma de pagamento, na ordem do enum; as formas
     *                                 somam o faturamento
     * @param fiadoRecebido            a soma dos recebimentos de fiado lançados no mês
     * @param quantidadeDeRecebimentos quantos recebimentos
     * @param canceladas               a soma das vendas canceladas que tinham concluído no mês
     * @param quantidadeDeCanceladas   quantas
     */
    public record ResumoMensal(YearMonth mes, LocalDate inicio, LocalDate fim, Money faturamento,
            long quantidadeDeVendas, Money mercadoria, Money servico, List<TotalDaForma> porForma,
            Money fiadoRecebido, long quantidadeDeRecebimentos, Money canceladas,
            long quantidadeDeCanceladas) {
    }
}
