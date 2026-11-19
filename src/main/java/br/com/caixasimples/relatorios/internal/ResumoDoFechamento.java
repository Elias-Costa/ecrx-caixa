package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * O conteúdo do resumo de um caixa fechado: faturamento das vendas concluídas na sessão, total por
 * forma de pagamento, sangrias, diferença apurada e os três produtos que mais renderam.
 *
 * <p>Nenhum nome de pessoa: o caixa é dito pelos instantes de abertura e de fechamento, e as
 * sangrias pela soma e pela quantidade. O e-mail pode parar numa caixa de correio fora do país,
 * escolhida pelo administrador, e sem nome de pessoa nenhum dado de funcionário ou de Cliente vai
 * junto.
 *
 * @param diferenca positivo é falta na gaveta, negativo é sobra
 * @param porForma  só as formas que tiveram parcela, na ordem do enum
 * @param maisVendidos até três, do que mais rendeu para o que menos rendeu
 */
public record ResumoDoFechamento(Instant abertaEm, Instant fechadaEm, Money faturamento,
        long quantidadeDeVendas, List<Forma> porForma, Money sangrias, long quantidadeDeSangrias,
        Money diferenca, List<Produto> maisVendidos) {

    public ResumoDoFechamento {
        Objects.requireNonNull(abertaEm, "abertaEm nao pode ser nulo");
        Objects.requireNonNull(fechadaEm, "fechadaEm nao pode ser nulo");
        Objects.requireNonNull(faturamento, "faturamento nao pode ser nulo");
        porForma = List.copyOf(porForma);
        Objects.requireNonNull(sangrias, "sangrias nao pode ser nulo");
        Objects.requireNonNull(diferenca, "diferenca nao pode ser nula");
        maisVendidos = List.copyOf(maisVendidos);
    }

    public record Forma(FormaPagamento forma, Money total) {
    }

    /** @param unidade nula quando o produto não tem unidade cadastrada */
    public record Produto(String nome, String unidade, BigDecimal quantidade, Money valor) {
    }
}
