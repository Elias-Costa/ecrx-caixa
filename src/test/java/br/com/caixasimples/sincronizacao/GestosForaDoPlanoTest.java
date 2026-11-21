package br.com.caixasimples.sincronizacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Os gestos que o aparelho registra sem rede, fixados um a um.
 *
 * <p>Com a Conta só leitura, o lote recusa os que começam algo no balcão, abrir caixa, sangria,
 * suprimento e iniciar venda, pelo instante em que o aparelho os fez; os outros passam, e os da venda
 * recusada caem junto com ela. Um gesto novo quebra este teste de propósito: antes de entrar na
 * lista, quem o cria decide se ele começa algo no balcão e, por isso, se pergunta pelo só leitura.
 */
class GestosForaDoPlanoTest extends TesteDeIntegracao {

    @Autowired
    private List<AplicadorDeOperacoes> aplicadores;

    @Test
    @DisplayName("os gestos sem rede são estes; gesto novo obriga a decidir o que o só leitura faz com ele")
    void gestosDeHoje() {
        Set<String> tipos = aplicadores.stream()
                .flatMap(aplicador -> aplicador.tipos().stream())
                .collect(Collectors.toSet());

        assertThat(tipos).containsExactlyInAnyOrder(
                "produto.criar", "produto.editar", "produto.inativar", "produto.reativar",
                "cliente.criar", "cliente.editar", "cliente.inativar", "cliente.reativar",
                "caixa.abrir", "caixa.sangrar", "caixa.suprir", "caixa.fechar",
                "venda.iniciar", "venda.adicionarItem", "venda.removerItem",
                "venda.aplicarDesconto", "venda.vincularCliente", "venda.desvincularCliente",
                "venda.registrarPagamento", "venda.concluir");
    }
}
