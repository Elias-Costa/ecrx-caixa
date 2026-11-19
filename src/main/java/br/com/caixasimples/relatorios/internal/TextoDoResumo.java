package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.FormatoDeTela;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * O assunto e o corpo do e-mail do resumo, em texto puro, com valores, datas e nomes de forma de
 * pagamento escritos como a tela os escreve, e a diferença em palavras, como no fechamento.
 *
 * <p>Texto puro, e não HTML: o resumo é uma lista curta de números, e texto puro se lê igual em
 * qualquer programa de e-mail, sem modelo nem estilo para manter.
 *
 * <p>Nenhum nome de pessoa entra aqui, porque o resumo não os tem. O nome do negócio entra no
 * assunto, para o administrador de mais de uma Conta saber de qual é.
 */
final class TextoDoResumo {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private TextoDoResumo() {
    }

    static String assunto(String nomeNegocio, ResumoDoFechamento resumo) {
        return "Resumo do caixa de " + nomeNegocio + ", fechado em " + quando(resumo.fechadaEm());
    }

    static String corpo(String nomeNegocio, ResumoDoFechamento resumo) {
        StringBuilder texto = new StringBuilder();
        texto.append("Resumo do caixa de ").append(nomeNegocio).append("\n\n");
        texto.append("Aberto em ").append(quando(resumo.abertaEm()))
                .append(" e fechado em ").append(quando(resumo.fechadaEm())).append(".\n\n");

        if (resumo.quantidadeDeVendas() == 0) {
            texto.append("Faturamento: nenhuma venda concluída neste caixa.\n");
        } else {
            texto.append("Faturamento: ").append(FormatoDeTela.reais(resumo.faturamento()))
                    .append(" em ").append(contagem(resumo.quantidadeDeVendas(), "venda", "vendas"))
                    .append(".\n");
        }
        if (!resumo.porForma().isEmpty()) {
            texto.append("\nPor forma de pagamento:\n");
            for (ResumoDoFechamento.Forma forma : resumo.porForma()) {
                texto.append("- ").append(nomeDaForma(forma.forma())).append(": ")
                        .append(FormatoDeTela.reais(forma.total())).append("\n");
            }
        }

        texto.append("\n");
        if (resumo.quantidadeDeSangrias() == 0) {
            texto.append("Sangrias: nenhuma.\n");
        } else {
            texto.append("Sangrias: ").append(FormatoDeTela.reais(resumo.sangrias()))
                    .append(" em ")
                    .append(contagem(resumo.quantidadeDeSangrias(), "retirada", "retiradas"))
                    .append(".\n");
        }
        texto.append("Diferença no fechamento: ").append(diferenca(resumo.diferenca()))
                .append(".\n");

        texto.append("\nMais vendidos:\n");
        if (resumo.maisVendidos().isEmpty()) {
            texto.append("Nenhum produto vendido neste caixa.\n");
        }
        int posicao = 1;
        for (ResumoDoFechamento.Produto produto : resumo.maisVendidos()) {
            texto.append(posicao).append(". ").append(produto.nome()).append(": ")
                    .append(FormatoDeTela.quantidade(produto.quantidade()));
            if (produto.unidade() != null && !produto.unidade().isBlank()) {
                texto.append(" ").append(produto.unidade());
            }
            texto.append(", ").append(FormatoDeTela.reais(produto.valor())).append("\n");
            posicao++;
        }

        texto.append("\nEste resumo vai para o administrador da Conta a cada caixa fechado no ECRX"
                + " Caixa. Este endereço não recebe respostas.\n");
        return texto.toString();
    }

    /** O dia e a hora no fuso do balcão, como a tela os mostra: 19/11/2026 às 18:40. */
    private static String quando(Instant instante) {
        ZonedDateTime local = instante.atZone(FusoDeReferencia.DO_BALCAO);
        return FormatoDeTela.data(local.toLocalDate()) + " às " + HORA.format(local);
    }

    /** Positivo é falta na gaveta, negativo é sobra, as palavras do fechamento na tela. */
    private static String diferenca(Money diferenca) {
        int sinal = diferenca.valor().signum();
        if (sinal > 0) {
            return "falta de " + FormatoDeTela.reais(diferenca);
        }
        if (sinal < 0) {
            return "sobra de " + FormatoDeTela.reais(Money.de(diferenca.valor().negate()));
        }
        return "sem diferença";
    }

    /** O nome que a tela dá a cada forma. Exaustivo: uma forma nova é erro de compilação aqui. */
    private static String nomeDaForma(FormaPagamento forma) {
        return switch (forma) {
            case DINHEIRO -> "Dinheiro";
            case PIX -> "Pix";
            case CARTAO -> "Cartão";
            case FIADO -> "Fiado";
        };
    }

    private static String contagem(long quantidade, String singular, String plural) {
        return quantidade + " " + (quantidade == 1 ? singular : plural);
    }
}
