package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.cadastro.TipoProduto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Um item de venda concluída, com o que o resumo mensal precisa para repartir o desconto da venda
 * inteira entre os itens e separar mercadoria de serviço, com os <strong>tipos da coluna</strong>.
 *
 * <p>É o alvo do {@code select new} em {@link ItemVendaParaRelatorioRepository}: o Hibernate
 * instancia este record por reflexão, pelo nome escrito na consulta, e por isso ele é público. O
 * desconto da venda se repete em cada item dela, porque a consulta é uma linha por item; quem
 * agrupa os itens da mesma venda é o caso de uso.
 *
 * <p>Sem {@code contaId}, pelo mesmo motivo de toda projeção do projeto: quem filtra é o tenant na
 * entidade (RNF05).
 *
 * @param vendaId          a venda do item
 * @param descontoDaVenda  o desconto dado na venda inteira, fora o de cada item; zero quando não há
 * @param itemId           o item, para o último desempate do rateio
 * @param criadoEm         quando o item entrou na comanda, para o primeiro desempate do rateio
 * @param subtotal         quantidade vezes preço, arredondado no item, menos o desconto do item
 * @param tipo             mercadoria ou serviço, pelo produto do item
 */
public record ItemParaRateio(UUID vendaId, BigDecimal descontoDaVenda, UUID itemId,
        Instant criadoEm, BigDecimal subtotal, TipoProduto tipo) {
}
