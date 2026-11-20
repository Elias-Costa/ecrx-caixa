package br.com.caixasimples.relatorios.application;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reparte o desconto dado na venda inteira entre os itens dela e devolve quanto a venda faturou em
 * mercadoria e quanto em serviço, para o resumo mensal (RF67).
 *
 * <p><strong>A regra.</strong> Cada item leva uma parte do desconto proporcional ao subtotal dele,
 * que é o bruto menos o desconto do próprio item: desconto vezes subtotal, dividido pela soma dos
 * subtotais, em centavos. As partes arredondadas podem não somar o desconto; a diferença vai para o
 * item de maior subtotal e, no empate, para o que entrou primeiro na comanda. Assim mercadoria mais
 * serviço fecha com o total da venda, como na nota fiscal de consumidor, que também leva o desconto
 * item a item.
 *
 * <p><strong>A divisão arredonda uma vez só</strong>, direto em duas casas e no modo do
 * {@link Money}. O quociente pode ter infinitas casas, e arredondá-lo primeiro para mais casas e
 * depois para duas mudaria o centavo de um valor que fica rente à metade.
 *
 * <p>Classe de pacote e sem estado: é uma conta do caso de uso, separada para ser testada sozinha,
 * sem banco.
 */
final class RateioDoDescontoDaVenda {

    private RateioDoDescontoDaVenda() {
    }

    /**
     * Um item da venda, já com o desconto dele descontado.
     *
     * @param subtotal o bruto do item menos o desconto do item, nunca negativo
     * @param tipo     mercadoria ou serviço
     */
    record Item(Money subtotal, TipoProduto tipo) {

        Item {
            Objects.requireNonNull(subtotal, "subtotal nao pode ser nulo");
            Objects.requireNonNull(tipo, "tipo nao pode ser nulo");
        }
    }

    /**
     * O que a venda faturou de cada tipo, depois do desconto da venda.
     *
     * @param mercadoria a soma dos itens de produto
     * @param servico    a soma dos itens de serviço
     */
    record Liquido(Money mercadoria, Money servico) {
    }

    /**
     * Reparte o desconto da venda entre os itens e soma o que sobra de cada um pelo tipo.
     *
     * @param itens    os itens da venda <strong>na ordem da comanda</strong>, que é a ordem do
     *                 desempate; pode ser vazia
     * @param desconto o desconto dado na venda inteira, zero quando não há; nunca maior que a soma
     *                 dos subtotais, regra que a venda garante ao recebê-lo
     * @throws IllegalStateException se o desconto passa da soma dos subtotais, o que só um defeito
     *                               ou uma linha gravada por fora do sistema alcança
     */
    static Liquido repartir(List<Item> itens, Money desconto) {
        Objects.requireNonNull(itens, "itens nao podem ser nulos");
        Objects.requireNonNull(desconto, "desconto nao pode ser nulo");

        Money soma = Money.ZERO;
        for (Item item : itens) {
            soma = soma.somar(item.subtotal());
        }
        if (desconto.valor().compareTo(soma.valor()) > 0) {
            throw new IllegalStateException("desconto da venda " + desconto
                    + " maior que a soma dos itens " + soma);
        }

        List<Money> partes = new ArrayList<>();
        for (Item item : itens) {
            partes.add(parteDoDesconto(desconto, item.subtotal(), soma));
        }
        Money repartido = Money.ZERO;
        for (Money parte : partes) {
            repartido = repartido.somar(parte);
        }
        Money diferenca = desconto.subtrair(repartido);
        if (diferenca.valor().signum() != 0) {
            int maior = indiceDoMaiorSubtotal(itens);
            partes.set(maior, partes.get(maior).somar(diferenca));
        }

        Money mercadoria = Money.ZERO;
        Money servico = Money.ZERO;
        for (int i = 0; i < itens.size(); i++) {
            Item item = itens.get(i);
            Money liquido = item.subtotal().subtrair(partes.get(i));
            switch (item.tipo()) {
                case PRODUTO -> mercadoria = mercadoria.somar(liquido);
                case SERVICO -> servico = servico.somar(liquido);
            }
        }
        return new Liquido(mercadoria, servico);
    }

    private static Money parteDoDesconto(Money desconto, Money subtotal, Money soma) {
        // Soma zero só acontece com desconto zero, conferido acima; não há o que repartir.
        if (desconto.valor().signum() == 0 || soma.valor().signum() == 0) {
            return Money.ZERO;
        }
        BigDecimal parte = desconto.valor().multiply(subtotal.valor())
                .divide(soma.valor(), 2, Money.ARREDONDAMENTO);
        return Money.de(parte);
    }

    /** O primeiro item com o maior subtotal: a comparação estrita mantém o primeiro no empate. */
    private static int indiceDoMaiorSubtotal(List<Item> itens) {
        int maior = 0;
        for (int i = 1; i < itens.size(); i++) {
            if (itens.get(i).subtotal().valor().compareTo(itens.get(maior).subtotal().valor()) > 0) {
                maior = i;
            }
        }
        return maior;
    }
}
