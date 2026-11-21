package br.com.caixasimples.contas.application;

import br.com.caixasimples.shared.FormatoDeTela;
import java.time.LocalDate;

/**
 * A Conta está só leitura: a tolerância depois do vencimento acabou sem o pagamento, e o gesto que
 * começa algo no balcão é recusado. Fechar o caixa aberto, consultar e cadastrar continuam; o código
 * do pagamento aplicado na tela do plano devolve o resto.
 *
 * <p>Estende {@link IllegalStateException}, que responde 409 com a mensagem na rota e deixa o gesto
 * do lote não aplicado, com a mesma frase na lista de revisões do administrador.
 */
public class ContaSomenteLeituraException extends IllegalStateException {

    public ContaSomenteLeituraException(LocalDate inicioDoSomenteLeitura) {
        super("A Conta está só leitura desde " + FormatoDeTela.data(inicioDoSomenteLeitura)
                + ", sem o pagamento do plano. Para vender e mexer no caixa de novo, o administrador"
                + " aplica o código do pagamento na tela Plano.");
    }
}
