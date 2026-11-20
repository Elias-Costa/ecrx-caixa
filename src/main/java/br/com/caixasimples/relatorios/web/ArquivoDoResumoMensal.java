package br.com.caixasimples.relatorios.web;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.relatorios.application.ResumoMensalService.ResumoMensal;
import br.com.caixasimples.relatorios.application.ResumoMensalService.TotalDaForma;
import br.com.caixasimples.shared.FormatoDeTela;
import br.com.caixasimples.shared.Money;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;

/**
 * O resumo mensal escrito como planilha CSV, no formato que o Excel em português abre com duplo
 * clique (RF67): ponto e vírgula entre as colunas, vírgula nos centavos e UTF-8 com a marca de
 * ordem dos bytes no começo.
 *
 * <p><strong>Por que cada escolha.</strong> Com vírgula entre as colunas, o Excel em português põe a
 * linha inteira numa coluna só; sem a marca no começo, ele lê o arquivo em outra codificação e
 * estraga os acentos. O número sai sem símbolo de real e sem ponto de milhar, só com a vírgula dos
 * centavos, para a célula ser número e o contador poder somá-la. As linhas terminam em CR LF, o fim
 * de linha que a especificação do CSV pede.
 *
 * <p><strong>Nada digitado por alguém entra no arquivo</strong>: só rótulos fixos e números. Por
 * isso não há o que pôr entre aspas, e nenhum texto pode ser lido pela planilha como fórmula. O
 * nome do negócio fica de fora por esse motivo; quem recebe o arquivo sabe de quem ele é pela
 * mensagem em que chega.
 *
 * <p>Os blocos repetem a tela de relatórios: o faturamento com mercadoria e serviço, as formas de
 * pagamento, e o que fica fora do faturamento, o fiado recebido e as vendas canceladas.
 */
final class ArquivoDoResumoMensal {

    private static final String MARCA_DE_ORDEM_DOS_BYTES = "\uFEFF";
    private static final String FIM_DE_LINHA = "\r\n";

    private ArquivoDoResumoMensal() {
    }

    /** O nome com que o navegador salva o arquivo, com o ano antes do mês para a pasta ordenar. */
    static String nome(YearMonth mes) {
        return "resumo-das-vendas-" + mes + ".csv";
    }

    /** O arquivo inteiro, já em bytes UTF-8, com a marca de ordem no começo. */
    static byte[] conteudo(ResumoMensal resumo) {
        StringBuilder csv = new StringBuilder(MARCA_DE_ORDEM_DOS_BYTES);
        linha(csv, "Resumo das vendas", FormatoDeTela.mes(resumo.mes()));
        linha(csv, "Período", FormatoDeTela.data(resumo.inicio()) + " a "
                + FormatoDeTela.data(resumo.fim()));
        csv.append(FIM_DE_LINHA);

        linha(csv, "Faturamento", "Valor", "Vendas");
        linha(csv, "Total", numero(resumo.faturamento()), String.valueOf(resumo.quantidadeDeVendas()));
        linha(csv, "Mercadoria", numero(resumo.mercadoria()), "");
        linha(csv, "Serviço", numero(resumo.servico()), "");
        csv.append(FIM_DE_LINHA);

        linha(csv, "Por forma de pagamento", "Valor", "Vendas");
        for (TotalDaForma daForma : resumo.porForma()) {
            linha(csv, rotulo(daForma.forma()), numero(daForma.total()),
                    String.valueOf(daForma.quantidadeDeVendas()));
        }
        csv.append(FIM_DE_LINHA);

        linha(csv, "Fora do faturamento", "Valor", "Quantidade");
        linha(csv, "Fiado recebido no mês", numero(resumo.fiadoRecebido()),
                String.valueOf(resumo.quantidadeDeRecebimentos()));
        linha(csv, "Vendas canceladas", numero(resumo.canceladas()),
                String.valueOf(resumo.quantidadeDeCanceladas()));
        csv.append(FIM_DE_LINHA);

        linha(csv, "A venda paga em mais de uma forma conta em cada uma delas.");
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void linha(StringBuilder csv, String... colunas) {
        csv.append(String.join(";", colunas)).append(FIM_DE_LINHA);
    }

    /**
     * O valor com a vírgula nos centavos e nada mais. Sem formatador de número, que poria o ponto de
     * milhar: o valor já tem duas casas, e basta trocar o ponto decimal.
     */
    private static String numero(Money valor) {
        return valor.valor().toPlainString().replace('.', ',');
    }

    /** Exaustivo de propósito: uma forma nova no enum vira erro de compilação aqui. */
    private static String rotulo(FormaPagamento forma) {
        return switch (forma) {
            case DINHEIRO -> "Dinheiro";
            case PIX -> "Pix";
            case CARTAO -> "Cartão";
            case FIADO -> "Fiado";
        };
    }
}
