package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.vendas.StatusVenda;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * As consultas do resumo do caixa fechado, todas delimitadas por uma sessão, e não por um intervalo
 * de instantes como os relatórios da tela.
 *
 * <p><strong>Por que a sessão, e não o dia.</strong> O resumo sai quando a gaveta é contada, e o que
 * fica completo nesse instante é o expediente, não o dia do calendário: um caixa aberto às 18h e
 * fechado à 1h tem a noite inteira, e o dia do fechamento teria só uma hora. Por isso cada consulta
 * aqui filtra pelo id da sessão, e as regras de quais vendas e parcelas contam são as mesmas do
 * faturamento e do ranking, repetidas e não reaproveitadas, porque o filtro que muda está no meio
 * de cada consulta.
 *
 * <p>Estende {@code Repository}, sem método de escrita, como os outros repositórios deste módulo.
 * Toda consulta é filtrada por {@code conta_id} pelo tenant declarado em cada mapeamento, inclusive
 * nas junções (RNF05); a chave estrangeira só garante que a linha existe, não que é da mesma conta.
 * Os status e as formas entram como parâmetro, para a regra de quais contam ficar escrita em quem
 * monta o resumo.
 */
public interface ResumoDaSessaoRepository extends Repository<SessaoCaixaParaRelatorio, UUID> {

    /** A sessão, só se já estiver no status pedido; o resumo passa FECHADA. */
    @Query("""
            select new br.com.caixasimples.relatorios.internal.SessaoParaResumo(
                s.abertaEm, s.fechadaEm, s.diferenca)
            from SessaoCaixaParaRelatorio s
            where s.id = :sessaoId
              and s.status = :status
            """)
    Optional<SessaoParaResumo> sessao(@Param("sessaoId") UUID sessaoId,
            @Param("status") StatusSessaoCaixa status);

    /**
     * A soma e a contagem das vendas de um status feitas na sessão. Uma venda concluída só cancela
     * com a própria sessão aberta, então depois do fechamento este número não muda mais.
     */
    @Query("""
            select new br.com.caixasimples.relatorios.internal.TotaisDeVendas(
                sum(v.valorTotal), count(v))
            from VendaParaRelatorio v
            where v.status = :status
              and v.sessaoCaixaId = :sessaoId
            """)
    TotaisDeVendas vendas(@Param("sessaoId") UUID sessaoId, @Param("status") StatusVenda status);

    /**
     * A soma das parcelas de cada forma nas vendas de um status feitas na sessão, com a regra do
     * faturamento por forma: entra a parcela do status pedido, e a parcela FIADO ainda pendente, que
     * é a venda a prazo, e não dinheiro que deixou de entrar. Uma linha por forma que teve parcela.
     */
    @Query("""
            select new br.com.caixasimples.relatorios.internal.TotalPorForma(p.forma, sum(p.valor))
            from VendaParaRelatorio v
              join PagamentoParaRelatorio p on p.vendaId = v.id
            where v.status = :status
              and v.sessaoCaixaId = :sessaoId
              and (p.status = :statusDaParcela
                   or (p.forma = :fiado and p.status = :pendente))
            group by p.forma
            """)
    List<TotalPorForma> porForma(@Param("sessaoId") UUID sessaoId,
            @Param("status") StatusVenda status,
            @Param("statusDaParcela") StatusPagamento statusDaParcela,
            @Param("fiado") FormaPagamento fiado,
            @Param("pendente") StatusPagamento pendente);

    /** A soma e a contagem dos movimentos de um tipo na sessão; o resumo passa SANGRIA. */
    @Query("""
            select new br.com.caixasimples.relatorios.internal.TotalDeSangrias(
                sum(m.valor), count(m))
            from MovimentoCaixaParaRelatorio m
            where m.sessaoCaixaId = :sessaoId
              and m.tipo = :tipo
            """)
    TotalDeSangrias movimentos(@Param("sessaoId") UUID sessaoId,
            @Param("tipo") TipoMovimentoCaixa tipo);

    /**
     * Os produtos que mais renderam nas vendas de um status feitas na sessão, com o valor de cada
     * item arredondado como no domínio, na ordem do ranking da tela: pelo valor, depois pelo nome e
     * pelo id. O mesmo cálculo de {@link ItemVendaParaRelatorioRepository#maisVendidosPorValorEntre}.
     */
    @Query("""
            select new br.com.caixasimples.relatorios.internal.ProdutoVendido(
                p.id, p.nome, p.unidade,
                sum(i.quantidade),
                sum(round(i.quantidade * i.precoUnitario, 2) - i.desconto))
            from ItemVendaParaRelatorio i
              join VendaParaRelatorio v on v.id = i.vendaId
              join ProdutoParaRelatorio p on p.id = i.produtoId
            where v.status = :status
              and v.sessaoCaixaId = :sessaoId
            group by p.id, p.nome, p.unidade
            order by sum(round(i.quantidade * i.precoUnitario, 2) - i.desconto) desc,
              p.nome asc, p.id asc
            """)
    List<ProdutoVendido> maisVendidos(@Param("sessaoId") UUID sessaoId,
            @Param("status") StatusVenda status, Limit limite);
}
