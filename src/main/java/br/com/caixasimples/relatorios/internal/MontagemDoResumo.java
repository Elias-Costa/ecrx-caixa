package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Lê do banco o resumo de uma sessão, já sob o tenant e a transação que o ouvinte abriu.
 *
 * <p>As regras de quais vendas e parcelas contam são as do faturamento da tela: só a venda
 * CONCLUIDA, só a parcela CONFIRMADO, e a parcela FIADO pendente na forma dela. Moram aqui, e não no
 * repositório, pelo mesmo motivo de lá: o repositório recebe o que contar, e quem decide é quem
 * monta o relatório.
 */
@Component
class MontagemDoResumo {

    private static final int MAIS_VENDIDOS = 3;

    private final ResumoDaSessaoRepository consultas;

    MontagemDoResumo(ResumoDaSessaoRepository consultas) {
        this.consultas = consultas;
    }

    /**
     * O resumo da sessão, ou vazio quando ela não existe nesta Conta ou ainda não está FECHADA. Uma
     * sessão encerrada junto com a Conta antes do envio também dá vazio.
     */
    Optional<ResumoDoFechamento> daSessao(UUID sessaoId) {
        Optional<SessaoParaResumo> encontrada =
                consultas.sessao(sessaoId, StatusSessaoCaixa.FECHADA);
        if (encontrada.isEmpty()) {
            return Optional.empty();
        }
        SessaoParaResumo sessao = encontrada.get();

        TotaisDeVendas vendas = consultas.vendas(sessaoId, StatusVenda.CONCLUIDA);
        List<ResumoDoFechamento.Forma> porForma = new ArrayList<>();
        consultas.porForma(sessaoId, StatusVenda.CONCLUIDA, StatusPagamento.CONFIRMADO,
                        FormaPagamento.FIADO, StatusPagamento.PENDENTE).stream()
                .sorted(Comparator.comparing(TotalPorForma::forma))
                .forEach(linha -> porForma.add(
                        new ResumoDoFechamento.Forma(linha.forma(), Money.de(linha.total()))));
        TotalDeSangrias sangrias = consultas.movimentos(sessaoId, TipoMovimentoCaixa.SANGRIA);
        List<ResumoDoFechamento.Produto> maisVendidos = consultas
                .maisVendidos(sessaoId, StatusVenda.CONCLUIDA, Limit.of(MAIS_VENDIDOS)).stream()
                .map(produto -> new ResumoDoFechamento.Produto(produto.nome(), produto.unidade(),
                        produto.quantidade(), Money.de(produto.valor())))
                .toList();

        return Optional.of(new ResumoDoFechamento(sessao.abertaEm(), sessao.fechadaEm(),
                zeroSeNulo(vendas.total()), vendas.quantidade(), porForma,
                zeroSeNulo(sangrias.total()), sangrias.quantidade(),
                zeroSeNulo(sessao.diferenca()), maisVendidos));
    }

    // A soma de zero linhas é nula em SQL; para quem lê o resumo, um caixa sem venda é zero.
    private static Money zeroSeNulo(BigDecimal valor) {
        return valor == null ? Money.ZERO : Money.de(valor);
    }
}
