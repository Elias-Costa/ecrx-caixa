package br.com.caixasimples.relatorios.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.FormatoDeTela;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Pagamento;
import br.com.caixasimples.vendas.domain.Venda;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * O contrato HTTP do resumo mensal para o contador (RF67): o arquivo inteiro, byte a byte, os dois
 * cabeçalhos que fazem o navegador salvá-lo, o isolamento entre Contas pela rota (RNF05) e as
 * recusas, que continuam saindo como Problem Details.
 */
class ResumoMensalHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final YearMonth MES = YearMonth.of(2026, 9);
    private static final String FIM_DE_LINHA = "\r\n";

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired SessaoCaixaService sessoes;
    @Autowired ProdutoService produtos;
    @Autowired CriadorDeVendaDeTeste vendas;

    @Test
    void administradorBaixaOArquivoDoMesSemLerOutraConta() throws Exception {
        ContaCriada contaA = criador.criar("Salão do Resumo A", SENHA);
        criador.contratar(contaA.contaId(), Plano.COMPLETO);
        ContaCriada contaB = criador.criar("Salão do Resumo B", SENHA);
        criador.contratar(contaB.contaId(), Plano.COMPLETO);
        // Em A, mercadoria de 30 e serviço de 10 com 8 de desconto na venda, tudo em dinheiro.
        vendaMista(contaA, "30.00", "10.00", "8.00", FormaPagamento.DINHEIRO);
        // Em B, um valor com milhar, para provar que o número sai sem o ponto.
        vendaMista(contaB, "1234.56", "0.00", "0.00", FormaPagamento.PIX);

        byte[] deA = resumo(contaA, MES.toString())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"resumo-das-vendas-2026-09.csv\""))
                .andReturn().getResponse().getContentAsByteArray();
        byte[] deB = resumo(contaB, MES.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(Arrays.copyOf(deA, 3))
                .as("a marca de ordem dos bytes do UTF-8 abre o arquivo")
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(semAMarca(deA)).isEqualTo(linhas(
                "Resumo das vendas;09/2026",
                "Período;01/09/2026 a 30/09/2026",
                "",
                "Faturamento;Valor;Vendas",
                "Total;32,00;1",
                "Mercadoria;24,00;",
                "Serviço;8,00;",
                "",
                "Por forma de pagamento;Valor;Vendas",
                "Dinheiro;32,00;1",
                "Pix;0,00;0",
                "Cartão;0,00;0",
                "Fiado;0,00;0",
                "",
                "Fora do faturamento;Valor;Quantidade",
                "Fiado recebido no mês;0,00;0",
                "Vendas canceladas;0,00;0",
                "",
                "A venda paga em mais de uma forma conta em cada uma delas."));

        String textoDeB = semAMarca(deB);
        assertThat(textoDeB).contains("Total;1234,56;1" + FIM_DE_LINHA);
        assertThat(textoDeB).contains("Pix;1234,56;1" + FIM_DE_LINHA);
        assertThat(textoDeB).contains("Dinheiro;0,00;0" + FIM_DE_LINHA);
    }

    @Test
    void operadorRecebe403EPlanoGratuito409() throws Exception {
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        resumo(operador, MES.toString()).andExpect(status().isForbidden());

        ContaCriada gratis = criador.criar("Banca do Resumo", SENHA);
        resumo(gratis, MES.toString()).andExpect(status().isConflict());
    }

    @Test
    void mesQueNaoTerminouOuMalEscritoRecebe400() throws Exception {
        ContaCriada admin = criador.criar("Loja do Mês", SENHA);
        criador.contratar(admin.contaId(), Plano.COMPLETO);
        YearMonth atual = YearMonth.now(FusoDeReferencia.DO_BALCAO);

        resumo(admin, atual.toString())
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.detail").value("O mês " + FormatoDeTela.mes(atual)
                        + " ainda não terminou. Escolha um mês anterior."));
        resumo(admin, "setembro").andExpect(status().isBadRequest());
        http.perform(get("/api/relatorios/resumo-mensal").with(autenticador.como(admin)))
                .andExpect(status().isBadRequest());
    }

    private ResultActions resumo(ContaCriada conta, String mes) throws Exception {
        return http.perform(get("/api/relatorios/resumo-mensal").param("mes", mes)
                .with(autenticador.como(conta)));
    }

    /**
     * Uma venda concluída no meio do mês, com uma mercadoria e um serviço nos preços dados, o
     * desconto da venda inteira e uma parcela só; o serviço de preço zero fica de fora da comanda.
     */
    private void vendaMista(ContaCriada conta, String mercadoria, String servico, String desconto,
            FormaPagamento forma) {
        Instant concluidaEm = MES.atDay(15).atTime(LocalTime.NOON)
                .atZone(FusoDeReferencia.DO_BALCAO).toInstant();
        UUID caixaId = conta.comoUsuario(() -> sessoes.abrir(Money.ZERO));
        UUID mercadoriaId = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Pomada", Money.de(mercadoria), null, null, "un", null)));
        UUID servicoId = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.SERVICO,
                new DadosDoProduto("Corte", Money.de("10.00"), null, null, null, null)));
        List<ItemVenda> itens = Money.de(servico).valor().signum() == 0
                ? List.of(item(mercadoriaId, mercadoria, concluidaEm))
                : List.of(item(mercadoriaId, mercadoria, concluidaEm),
                        item(servicoId, servico, concluidaEm));
        Money total = Money.de(mercadoria).somar(Money.de(servico)).subtrair(Money.de(desconto));
        Pagamento parcela = new Pagamento(UUID.randomUUID(), forma, total,
                StatusPagamento.CONFIRMADO, Money.ZERO, concluidaEm);
        Venda venda = Venda.reconstituir(UUID.randomUUID(), caixaId, conta.usuarioId(), null,
                StatusVenda.CONCLUIDA, total, Money.de(desconto), concluidaEm.minusSeconds(300),
                concluidaEm, itens, List.of(parcela));
        vendas.gravar(conta.contaId(), venda);
    }

    private static ItemVenda item(UUID produtoId, String preco, Instant concluidaEm) {
        return new ItemVenda(UUID.randomUUID(), produtoId, BigDecimal.ONE, Money.de(preco),
                Money.ZERO, concluidaEm.minusSeconds(60));
    }

    /** O texto do arquivo depois dos três bytes da marca de ordem. */
    private static String semAMarca(byte[] arquivo) {
        return new String(arquivo, 3, arquivo.length - 3, StandardCharsets.UTF_8);
    }

    private static String linhas(String... linhas) {
        return String.join(FIM_DE_LINHA, linhas) + FIM_DE_LINHA;
    }
}
