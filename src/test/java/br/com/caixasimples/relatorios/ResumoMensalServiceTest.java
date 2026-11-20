package br.com.caixasimples.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.application.PlanoSuspensoException;
import br.com.caixasimples.contas.application.RecursoForaDoPlanoException;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.relatorios.application.ResumoMensalService;
import br.com.caixasimples.relatorios.application.ResumoMensalService.ResumoMensal;
import br.com.caixasimples.relatorios.application.ResumoMensalService.TotalDaForma;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.FormatoDeTela;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Pagamento;
import br.com.caixasimples.vendas.domain.Recebimento;
import br.com.caixasimples.vendas.domain.Venda;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O resumo mensal das vendas para o contador (RF67), pelo caso de uso e contra o Postgres real.
 *
 * <p>As vendas são montadas inteiras por {@code Venda.reconstituir} e gravadas por
 * {@code CriadorDeVendaDeTeste}: é o jeito de pôr a conclusão, os recebimentos e o desconto da venda
 * inteira em instantes escolhidos, inclusive o primeiro e o último segundo do mês no fuso do balcão.
 *
 * <p>O que se prova: mercadoria mais serviço e a soma das formas fecham com o faturamento, com o
 * desconto da venda repartido; o mês corta no fuso do balcão; a cancelada conta pela conclusão e o
 * recebimento pelo dia dele; só mês fechado, só administrador, só plano pago em dia; e cada Conta
 * lê só as próprias vendas (RNF05).
 */
class ResumoMensalServiceTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    /** Um mês que já terminou; o que importa são o primeiro e o último segundo dele. */
    private static final YearMonth MES = YearMonth.of(2026, 9);

    @Autowired
    private ResumoMensalService resumo;

    @Autowired
    private SessaoCaixaService caixas;

    @Autowired
    private ProdutoService produtos;

    @Autowired
    private ClienteService clientes;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private CriadorDeVendaDeTeste vendas;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("mercadoria e serviço e as formas fecham com o faturamento do mês, com o desconto da venda repartido")
    void numerosDoMesFecham() {
        Balcao balcao = abrirBalcao("Salão Aurora");
        preencherMes(balcao);

        ResumoMensal doMes = balcao.conta().comoUsuario(() -> resumo.doMes(MES));

        assertThat(doMes.mes()).isEqualTo(MES);
        assertThat(doMes.inicio()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(doMes.fim()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(doMes.faturamento()).isEqualTo(Money.de("67.50"));
        assertThat(doMes.quantidadeDeVendas()).isEqualTo(3);
        // 30 e 10 com 8 de desconto na venda viram 24 e 8; mais o cartão de 15,50 e o fiado de 20.
        assertThat(doMes.mercadoria()).isEqualTo(Money.de("39.50"));
        assertThat(doMes.servico()).isEqualTo(Money.de("28.00"));
        assertThat(doMes.mercadoria().somar(doMes.servico())).isEqualTo(doMes.faturamento());
        assertThat(doMes.porForma())
                .extracting(TotalDaForma::forma, TotalDaForma::total, TotalDaForma::quantidadeDeVendas)
                .containsExactly(
                        tuple(FormaPagamento.DINHEIRO, Money.de("20.00"), 1L),
                        tuple(FormaPagamento.PIX, Money.de("12.00"), 1L),
                        tuple(FormaPagamento.CARTAO, Money.de("15.50"), 1L),
                        tuple(FormaPagamento.FIADO, Money.de("20.00"), 1L));
        // Só o recebimento do dia 5: os dos dois lados do mês ficam fora, pelo dia em que entraram.
        assertThat(doMes.fiadoRecebido()).isEqualTo(Money.de("5.00"));
        assertThat(doMes.quantidadeDeRecebimentos()).isEqualTo(1);
        assertThat(doMes.canceladas()).isEqualTo(Money.de("7.00"));
        assertThat(doMes.quantidadeDeCanceladas()).isEqualTo(1);
    }

    @Test
    @DisplayName("no empate do maior subtotal, o centavo do rateio fica com o item lançado primeiro na comanda")
    void desempateDoRateioPelaOrdemDaComanda() {
        Balcao balcao = abrirBalcao("Barbearia da Esquina");
        Instant concluidaEm = noBalcao(MES.atDay(15), LocalTime.NOON);
        // O serviço entra na comanda um minuto antes da mercadoria, com o mesmo subtotal: as duas
        // partes de 0,005 arredondam para 0,01, e o centavo a mais volta do serviço.
        ItemVenda servico = new ItemVenda(UUID.randomUUID(), balcao.servicoId(), BigDecimal.ONE,
                Money.de("5.00"), Money.ZERO, concluidaEm.minusSeconds(120));
        ItemVenda mercadoria = new ItemVenda(UUID.randomUUID(), balcao.mercadoriaId(),
                BigDecimal.ONE, Money.de("5.00"), Money.ZERO, concluidaEm.minusSeconds(60));
        gravar(balcao, StatusVenda.CONCLUIDA, null, concluidaEm, "0.01", List.of(mercadoria, servico),
                List.of(parcela(FormaPagamento.DINHEIRO, "9.99", concluidaEm)), List.of());

        ResumoMensal doMes = balcao.conta().comoUsuario(() -> resumo.doMes(MES));

        assertThat(doMes.servico()).isEqualTo(Money.de("5.00"));
        assertThat(doMes.mercadoria()).isEqualTo(Money.de("4.99"));
        assertThat(doMes.faturamento()).isEqualTo(Money.de("9.99"));
    }

    @Test
    @DisplayName("o mês corrente e os seguintes são recusados com a frase da tela; mês sem venda dá zero")
    void soMesQueJaTerminou() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        YearMonth atual = YearMonth.now(FusoDeReferencia.DO_BALCAO);

        conta.comoUsuario(() -> {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> resumo.doMes(atual))
                    .withMessage("O mês " + FormatoDeTela.mes(atual)
                            + " ainda não terminou. Escolha um mês anterior.");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> resumo.doMes(atual.plusMonths(1)));
        });

        ResumoMensal anterior = conta.comoUsuario(() -> resumo.doMes(atual.minusMonths(1)));
        assertThat(anterior.faturamento()).isEqualTo(Money.ZERO);
        assertThat(anterior.mercadoria()).isEqualTo(Money.ZERO);
        assertThat(anterior.servico()).isEqualTo(Money.ZERO);
        assertThat(anterior.fiadoRecebido()).isEqualTo(Money.ZERO);
        assertThat(anterior.canceladas()).isEqualTo(Money.ZERO);
        assertThat(anterior.porForma()).extracting(TotalDaForma::total).containsOnly(Money.ZERO);
    }

    @Test
    @DisplayName("é do administrador e de plano pago em dia, como os outros relatórios (RF30)")
    void soAdministradorComPlanoPagoEmDia() {
        ContaCriada conta = criador.criar("Loja com Atendente", SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() -> resumo.doMes(MES)));

        ContaCriada gratis = criador.criar("Banca Aurora", SENHA);
        assertThatExceptionOfType(RecursoForaDoPlanoException.class)
                .isThrownBy(() -> gratis.comoUsuario(() -> resumo.doMes(MES)));

        ContaCriada suspensa = criador.criar("Loja Suspensa", SENHA);
        criador.contratar(suspensa.contaId(), Plano.COMPLETO);
        criador.vencerPlano(suspensa.contaId(), 8);
        assertThatExceptionOfType(PlanoSuspensoException.class)
                .isThrownBy(() -> suspensa.comoUsuario(() -> resumo.doMes(MES)));
    }

    @Test
    @DisplayName("cada Conta lê só as próprias vendas, recebimentos e cancelamentos (RNF05)")
    void contasComDadosEquivalentesRecebemResultadosDistintos() {
        Balcao balcaoA = abrirBalcao("Loja A");
        Balcao balcaoB = abrirBalcao("Loja B");
        ContaCriada contaC = criador.criar("Loja C", SENHA);
        criador.contratar(contaC.contaId(), Plano.COMPLETO);
        preencherMes(balcaoA);
        Instant dia20 = noBalcao(MES.atDay(20), LocalTime.NOON);
        ItemVenda daB = new ItemVenda(UUID.randomUUID(), balcaoB.servicoId(), BigDecimal.ONE,
                Money.de("11.00"), Money.ZERO, dia20);
        gravar(balcaoB, StatusVenda.CONCLUIDA, null, dia20, "0.00", List.of(daB),
                List.of(parcela(FormaPagamento.PIX, "11.00", dia20)), List.of());

        ResumoMensal deA = balcaoA.conta().comoUsuario(() -> resumo.doMes(MES));
        ResumoMensal deB = balcaoB.conta().comoUsuario(() -> resumo.doMes(MES));
        ResumoMensal deC = contaC.comoUsuario(() -> resumo.doMes(MES));

        assertThat(deA.faturamento()).isEqualTo(Money.de("67.50"));
        assertThat(deA.fiadoRecebido()).isEqualTo(Money.de("5.00"));
        assertThat(deA.canceladas()).isEqualTo(Money.de("7.00"));

        assertThat(deB.faturamento()).isEqualTo(Money.de("11.00"));
        assertThat(deB.quantidadeDeVendas()).isEqualTo(1);
        assertThat(deB.mercadoria()).isEqualTo(Money.ZERO);
        assertThat(deB.servico()).isEqualTo(Money.de("11.00"));
        assertThat(deB.porForma()).extracting(TotalDaForma::total).containsExactly(
                Money.ZERO, Money.de("11.00"), Money.ZERO, Money.ZERO);
        assertThat(deB.fiadoRecebido()).isEqualTo(Money.ZERO);
        assertThat(deB.quantidadeDeRecebimentos()).isZero();
        assertThat(deB.canceladas()).isEqualTo(Money.ZERO);
        assertThat(deB.quantidadeDeCanceladas()).isZero();

        assertThat(deC.faturamento()).isEqualTo(Money.ZERO);
        assertThat(deC.quantidadeDeVendas()).isZero();
        assertThat(deC.mercadoria()).isEqualTo(Money.ZERO);
        assertThat(deC.servico()).isEqualTo(Money.ZERO);
        assertThat(deC.fiadoRecebido()).isEqualTo(Money.ZERO);
        assertThat(deC.canceladas()).isEqualTo(Money.ZERO);
    }

    /**
     * O mês da Conta do balcão: três vendas concluídas dentro dele, uma de cada lado da borda, uma
     * cancelada e um fiado de agosto pago aos poucos, com um recebimento dentro do mês.
     */
    private void preencherMes(Balcao balcao) {
        // No primeiro segundo do mês: mercadoria de 30 e serviço de 10, com 8 de desconto na venda
        // inteira, pagos em dinheiro e Pix.
        Instant primeiro = noBalcao(MES.atDay(1), LocalTime.MIDNIGHT);
        gravar(balcao, StatusVenda.CONCLUIDA, null, primeiro, "8.00",
                List.of(item(balcao.mercadoriaId(), "30.00", primeiro),
                        item(balcao.servicoId(), "10.00", primeiro)),
                List.of(parcela(FormaPagamento.DINHEIRO, "20.00", primeiro),
                        parcela(FormaPagamento.PIX, "12.00", primeiro)),
                List.of());
        // No último segundo do mês: mercadoria no cartão.
        Instant ultimo = noBalcao(MES.atEndOfMonth(), LocalTime.of(23, 59, 59));
        gravar(balcao, StatusVenda.CONCLUIDA, null, ultimo, "0.00",
                List.of(item(balcao.mercadoriaId(), "15.50", ultimo)),
                List.of(parcela(FormaPagamento.CARTAO, "15.50", ultimo)), List.of());
        // No meio do mês: serviço fiado.
        Instant meio = noBalcao(MES.atDay(12), LocalTime.of(14, 0));
        gravar(balcao, StatusVenda.CONCLUIDA, balcao.clienteId(), meio, "0.00",
                List.of(item(balcao.servicoId(), "20.00", meio)),
                List.of(fiado("20.00", meio)), List.of());

        // Fora do mês, por um segundo de cada lado.
        Instant antes = noBalcao(MES.atDay(1).minusDays(1), LocalTime.of(23, 59, 59));
        gravar(balcao, StatusVenda.CONCLUIDA, null, antes, "0.00",
                List.of(item(balcao.mercadoriaId(), "40.00", antes)),
                List.of(parcela(FormaPagamento.DINHEIRO, "40.00", antes)), List.of());
        Instant depois = noBalcao(MES.plusMonths(1).atDay(1), LocalTime.MIDNIGHT);
        gravar(balcao, StatusVenda.CONCLUIDA, null, depois, "0.00",
                List.of(item(balcao.servicoId(), "50.00", depois)),
                List.of(parcela(FormaPagamento.PIX, "50.00", depois)), List.of());

        // Concluída no mês e cancelada depois: fora do faturamento, na linha das canceladas.
        Instant cancelada = noBalcao(MES.atDay(10), LocalTime.NOON);
        gravar(balcao, StatusVenda.CANCELADA, null, cancelada, "0.00",
                List.of(item(balcao.mercadoriaId(), "7.00", cancelada)),
                List.of(parcela(FormaPagamento.DINHEIRO, "7.00", cancelada)), List.of());

        // Fiado de agosto pago aos poucos: no último segundo de agosto, no dia 5 e no primeiro
        // segundo de outubro. Só o do dia 5 é recebimento do mês.
        Instant emAgosto = noBalcao(MES.atDay(1).minusDays(10), LocalTime.of(9, 0));
        gravar(balcao, StatusVenda.CONCLUIDA, balcao.clienteId(), emAgosto, "0.00",
                List.of(item(balcao.mercadoriaId(), "20.00", emAgosto)),
                List.of(fiado("20.00", emAgosto)),
                List.of(recebimento(balcao, "2.00", FormaPagamento.DINHEIRO, antes),
                        recebimento(balcao, "5.00", FormaPagamento.PIX,
                                noBalcao(MES.atDay(5), LocalTime.of(10, 0))),
                        recebimento(balcao, "3.00", FormaPagamento.DINHEIRO, depois)));
    }

    /**
     * Uma venda inteira, gravada como o banco a guardaria: o total é a soma dos subtotais menos o
     * desconto da venda, e a raiz recusa o que não fecha.
     */
    private void gravar(Balcao balcao, StatusVenda status, UUID clienteId, Instant concluidaEm,
            String descontoDaVenda, List<ItemVenda> itens, List<Pagamento> pagamentos,
            List<Recebimento> recebimentos) {
        Money desconto = Money.de(descontoDaVenda);
        Money soma = Money.ZERO;
        for (ItemVenda item : itens) {
            soma = soma.somar(item.subtotal());
        }
        Instant abertaEm = concluidaEm.minusSeconds(300);
        Venda venda = Venda.reconstituir(UUID.randomUUID(), balcao.caixaId(), balcao.conta().usuarioId(),
                clienteId, status, soma.subtrair(desconto), desconto, abertaEm, concluidaEm, itens,
                pagamentos, recebimentos);
        vendas.gravar(balcao.conta().contaId(), venda);
    }

    private static ItemVenda item(UUID produtoId, String preco, Instant lancadoEm) {
        return new ItemVenda(UUID.randomUUID(), produtoId, BigDecimal.ONE, Money.de(preco), Money.ZERO,
                lancadoEm.minusSeconds(60));
    }

    private static Pagamento parcela(FormaPagamento forma, String valor, Instant lancadaEm) {
        return new Pagamento(UUID.randomUUID(), forma, Money.de(valor), StatusPagamento.CONFIRMADO,
                Money.ZERO, lancadaEm);
    }

    private static Pagamento fiado(String valor, Instant lancadoEm) {
        return new Pagamento(UUID.randomUUID(), FormaPagamento.FIADO, Money.de(valor),
                StatusPagamento.PENDENTE, Money.ZERO, lancadoEm);
    }

    private static Recebimento recebimento(Balcao balcao, String valor, FormaPagamento forma,
            Instant recebidoEm) {
        return new Recebimento(UUID.randomUUID(), balcao.caixaId(), Money.de(valor), forma,
                recebidoEm);
    }

    /** Um horário local do balcão como o instante gravado no banco, em UTC. */
    private static Instant noBalcao(LocalDate dia, LocalTime hora) {
        return dia.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }

    /**
     * Uma Conta do Completo com o caixa do administrador aberto, uma mercadoria, um serviço e um
     * cliente, porque a venda aponta para todos eles por chave estrangeira.
     */
    private Balcao abrirBalcao(String nome) {
        ContaCriada conta = criador.criar(nome, SENHA);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        return conta.comoUsuario(() -> {
            UUID caixaId = caixas.abrir(Money.ZERO);
            UUID mercadoriaId = produtos.cadastrar(TipoProduto.PRODUTO, new DadosDoProduto(
                    "Pomada", Money.de("30.00"), null, null, "un", null));
            UUID servicoId = produtos.cadastrar(TipoProduto.SERVICO, new DadosDoProduto(
                    "Corte", Money.de("10.00"), null, null, null, null));
            UUID clienteId = clientes.cadastrar(new DadosDoCliente("Lia", null));
            return new Balcao(conta, caixaId, mercadoriaId, servicoId, clienteId);
        });
    }

    private record Balcao(ContaCriada conta, UUID caixaId, UUID mercadoriaId, UUID servicoId,
            UUID clienteId) {
    }
}
