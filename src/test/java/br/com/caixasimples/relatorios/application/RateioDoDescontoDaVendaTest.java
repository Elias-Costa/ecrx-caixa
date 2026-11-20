package br.com.caixasimples.relatorios.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.relatorios.application.RateioDoDescontoDaVenda.Item;
import br.com.caixasimples.relatorios.application.RateioDoDescontoDaVenda.Liquido;
import br.com.caixasimples.shared.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A regra que separa mercadoria de serviço numa venda com desconto na venda inteira, sem banco: a
 * parte de cada item é proporcional ao subtotal, e o centavo que sobra do arredondamento vai para o
 * maior subtotal, o primeiro da comanda no empate.
 */
class RateioDoDescontoDaVendaTest {

    private static Item mercadoria(String subtotal) {
        return new Item(Money.de(subtotal), TipoProduto.PRODUTO);
    }

    private static Item servico(String subtotal) {
        return new Item(Money.de(subtotal), TipoProduto.SERVICO);
    }

    @Test
    void semDescontoOLiquidoEOSubtotal() {
        Liquido liquido = RateioDoDescontoDaVenda.repartir(
                List.of(mercadoria("10.00"), servico("25.50"), mercadoria("4.50")), Money.ZERO);

        assertThat(liquido.mercadoria()).isEqualTo(Money.de("14.50"));
        assertThat(liquido.servico()).isEqualTo(Money.de("25.50"));
    }

    @Test
    void vendaDeUmTipoSoLevaODescontoInteiro() {
        Liquido liquido = RateioDoDescontoDaVenda.repartir(
                List.of(mercadoria("10.00"), mercadoria("3.33")), Money.de("1.00"));

        assertThat(liquido.mercadoria()).isEqualTo(Money.de("12.33"));
        assertThat(liquido.servico()).isEqualTo(Money.ZERO);
    }

    @Test
    void vendaMistaRepartePeloSubtotal() {
        // 30 e 10 de 40: o desconto de 8,00 se divide em 6,00 e 2,00, sem sobra.
        Liquido liquido = RateioDoDescontoDaVenda.repartir(
                List.of(mercadoria("30.00"), servico("10.00")), Money.de("8.00"));

        assertThat(liquido.mercadoria()).isEqualTo(Money.de("24.00"));
        assertThat(liquido.servico()).isEqualTo(Money.de("8.00"));
    }

    @Test
    void oCentavoQueSobraVaiParaOMaiorSubtotal() {
        // 0,10 de desconto sobre 10, 20 e 10: as partes 0,03, 0,05 e 0,03 somam 0,11, e o centavo
        // a mais volta do maior subtotal, a mercadoria, que fica com 0,04 do desconto.
        Liquido liquido = RateioDoDescontoDaVenda.repartir(
                List.of(servico("10.00"), mercadoria("20.00"), servico("10.00")), Money.de("0.10"));

        assertThat(liquido.mercadoria()).isEqualTo(Money.de("19.96"));
        assertThat(liquido.servico()).isEqualTo(Money.de("19.94"));
        assertThat(liquido.mercadoria().somar(liquido.servico())).isEqualTo(Money.de("39.90"));
    }

    @Test
    void noEmpateDoMaiorSubtotalOCentavoVaiParaOPrimeiroDaComanda() {
        // 0,01 sobre dois subtotais iguais: cada parte arredonda para 0,01, e o centavo a mais
        // volta do primeiro item lançado, aqui o serviço.
        Liquido primeiroServico = RateioDoDescontoDaVenda.repartir(
                List.of(servico("5.00"), mercadoria("5.00")), Money.de("0.01"));
        Liquido primeiraMercadoria = RateioDoDescontoDaVenda.repartir(
                List.of(mercadoria("5.00"), servico("5.00")), Money.de("0.01"));

        assertThat(primeiroServico.servico()).isEqualTo(Money.de("5.00"));
        assertThat(primeiroServico.mercadoria()).isEqualTo(Money.de("4.99"));
        assertThat(primeiraMercadoria.mercadoria()).isEqualTo(Money.de("5.00"));
        assertThat(primeiraMercadoria.servico()).isEqualTo(Money.de("4.99"));
    }

    @Test
    void descontoIgualASomaZeraOsDoisTipos() {
        Liquido liquido = RateioDoDescontoDaVenda.repartir(
                List.of(mercadoria("7.77"), servico("2.23")), Money.de("10.00"));

        assertThat(liquido.mercadoria()).isEqualTo(Money.ZERO);
        assertThat(liquido.servico()).isEqualTo(Money.ZERO);
    }

    @Test
    void vendaSemItemNaoFaturaNada() {
        Liquido liquido = RateioDoDescontoDaVenda.repartir(List.of(), Money.ZERO);

        assertThat(liquido.mercadoria()).isEqualTo(Money.ZERO);
        assertThat(liquido.servico()).isEqualTo(Money.ZERO);
    }

    @Test
    void descontoMaiorQueASomaEDefeito() {
        assertThatIllegalStateException().isThrownBy(() -> RateioDoDescontoDaVenda.repartir(
                List.of(mercadoria("1.00")), Money.de("1.01")));
    }
}
