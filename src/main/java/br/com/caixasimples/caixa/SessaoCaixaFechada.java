package br.com.caixasimples.caixa;

import br.com.caixasimples.shared.ContaId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento de domínio: uma sessão de caixa foi fechada com conferência (RF15). É o fato que o resumo
 * do caixa por e-mail ouve, sem que o fechamento chame o envio: fechar o caixa é operação de
 * dinheiro e não pode falhar porque um e-mail não saiu.
 *
 * <p><strong>Ao contrário dos eventos da venda, é ouvido depois da transação</strong>, numa thread
 * própria, e passa pelo registro de publicação, a tabela {@code event_publication}: a linha nasce na
 * transação do fechamento e só é concluída quando o ouvinte termina, o que permite reenviar o que
 * falhou ou ficou no meio de um reinício.
 *
 * <p><strong>Leva só o que o resto da sessão não diz.</strong> Abertura, fechamento, valores e
 * diferença estão na sessão, que não muda mais depois de FECHADA, e quem ouve os lê de lá. O instante
 * em que o servidor registrou o fechamento não está em lugar nenhum: o {@code fechada_em} da sessão é
 * o do balcão, que pode ser de dias antes quando o fechamento chega pelo lote do aparelho, e é deste
 * instante que conta o prazo da reentrega.
 *
 * <p>Carrega a conta, lida do contexto autenticado no momento da publicação, porque o ouvinte roda
 * sem requisição, numa thread que não tem tenant, e uma reentrega parte da linha gravada (RNF05).
 *
 * @param contaId       a conta a que a sessão pertence
 * @param sessaoCaixaId a sessão fechada
 * @param registradoEm  o instante do servidor em que o fechamento foi gravado
 */
public record SessaoCaixaFechada(ContaId contaId, UUID sessaoCaixaId, Instant registradoEm) {

    public SessaoCaixaFechada {
        Objects.requireNonNull(contaId, "contaId nao pode ser nulo");
        Objects.requireNonNull(sessaoCaixaId, "sessaoCaixaId nao pode ser nulo");
        Objects.requireNonNull(registradoEm, "registradoEm nao pode ser nulo");
    }
}
