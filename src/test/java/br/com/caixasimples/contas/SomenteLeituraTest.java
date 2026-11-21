package br.com.caixasimples.contas;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.contas.application.ContaSomenteLeituraException;
import br.com.caixasimples.contas.application.PlanoService;
import br.com.caixasimples.contas.application.PlanoService.EstadoDoPlano;
import br.com.caixasimples.contas.application.PlanoService.PedidoNaConta;
import br.com.caixasimples.contas.internal.AssinaturaDePedido;
import br.com.caixasimples.contas.internal.CicloDeVencimento;
import br.com.caixasimples.contas.internal.PedidoDePlano;
import br.com.caixasimples.estoque.application.EstoqueService;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.relatorios.application.FaturamentoService;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.FormatoDeTela;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.sincronizacao.GestoDeTeste;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import br.com.caixasimples.sincronizacao.application.RevisaoService;
import br.com.caixasimples.sincronizacao.application.RevisaoService.RevisaoDeOperacao;
import br.com.caixasimples.sincronizacao.application.SincronizacaoService;
import br.com.caixasimples.vendas.application.VendaPixService;
import br.com.caixasimples.vendas.application.VendaService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * O teste de quatorze dias, a tolerância de três dias úteis e o só leitura, perguntados aos módulos
 * que vendem e mexem no caixa.
 *
 * <p>No só leitura, o que começa algo no balcão é recusado, pela rota e pelo lote: começar venda,
 * abrir caixa, sangria, suprimento, receber fiado e cobrança Pix nova. O que já começou termina, e o
 * que não põe nem tira dinheiro do balcão continua. O gesto sem rede feito antes do início do só
 * leitura é aplicado mesmo chegando depois.
 */
class SomenteLeituraTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired private CriadorDeContaDeTeste criador;
    @Autowired private AutenticadorDeTeste autenticador;
    @Autowired private MockMvc http;
    @Autowired private ObjectMapper json;
    @Autowired private FaturamentoService faturamento;
    @Autowired private EstoqueService estoque;
    @Autowired private ProdutoService produtos;
    @Autowired private ClienteService clientes;
    @Autowired private SessaoCaixaService caixa;
    @Autowired private VendaService vendas;
    @Autowired private VendaPixService pix;
    @Autowired private ContaService contaService;
    @Autowired private PlanoService planos;
    @Autowired private AssinaturaDePedido assinatura;
    @Autowired private SincronizacaoService sincronizacao;
    @Autowired private RevisaoService revisoes;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
        UsuarioContext.limpar();
    }

    @Test
    @DisplayName("no teste, a Conta nova tem relatórios, estoque, usuários e caixa; o operador recebe a recusa de perfil")
    void contaNovaNoTesteTemTudo() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        criador.habilitarEstoque(conta.contaId());
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");

        conta.comoUsuario(() -> faturamento.doDia(hoje()));
        conta.comoUsuario(estoque::produtos);
        UUID sessaoId = conta.comoUsuario(() -> caixa.abrir(Money.ZERO));
        assertThat(conta.comoUsuario(() -> vendas.iniciar(sessaoId))).isNotNull();
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() -> faturamento.doDia(hoje())));

        LocalDate fim = CicloDeVencimento.fimDoTeste(hoje());
        http.perform(get("/api/auth/eu").with(autenticador.como(conta)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano").value("COMPLETO"))
                .andExpect(jsonPath("$.emTeste").value(true))
                .andExpect(jsonPath("$.situacaoDoPlano").value("EM_DIA"))
                .andExpect(jsonPath("$.vencimentoDoPlano").value(fim.toString()))
                .andExpect(jsonPath("$.inicioDaSuspensao")
                        .value(CicloDeVencimento.inicioDoSomenteLeitura(fim).toString()))
                .andExpect(jsonPath("$.recursos.length()").value(4));
    }

    @Test
    @DisplayName("três dias antes do fim do teste o administrador vê o aviso; na tolerância tudo segue")
    void avisoDoTesteETolerancia() throws Exception {
        ContaCriada conta = criador.criar("Mercearia Aurora", SENHA);

        criador.vencerPlano(conta.contaId(), -3);
        http.perform(get("/api/auth/eu").with(autenticador.como(conta)))
                .andExpect(jsonPath("$.situacaoDoPlano").value("A_VENCER"))
                .andExpect(jsonPath("$.emTeste").value(true));
        criador.vencerPlano(conta.contaId(), -4);
        http.perform(get("/api/auth/eu").with(autenticador.como(conta)))
                .andExpect(jsonPath("$.situacaoDoPlano").value("EM_DIA"));

        criador.vencerPlano(conta.contaId(), 3);
        http.perform(get("/api/auth/eu").with(autenticador.como(conta)))
                .andExpect(jsonPath("$.situacaoDoPlano").value("VENCIDO"));
        UUID sessaoId = conta.comoUsuario(() -> caixa.abrir(Money.ZERO));
        conta.comoUsuario(() -> vendas.iniciar(sessaoId));
        conta.comoUsuario(() -> caixa.registrarSuprimento(sessaoId, Money.de("10.00"), "Troco"));
    }

    @Test
    @DisplayName("no só leitura, a rota recusa venda nova, caixa, sangria, suprimento, fiado e Pix novo, e o que começou termina")
    void rotaRecusaOQueComecaAlgo() throws Exception {
        ContaCriada conta = criador.criar("Padaria Aurora", SENHA);
        UUID pao = cadastrar(conta, "Pão francês");
        UUID clienteId = conta.comoUsuario(() -> clientes.cadastrar(new DadosDoCliente("Lia", null)));
        UUID sessaoId = conta.comoUsuario(() -> caixa.abrir(Money.ZERO));
        UUID fiado = conta.comoUsuario(() -> vendaNoFiado(sessaoId, pao, clienteId));
        UUID comanda = conta.comoUsuario(() -> vendas.iniciar(sessaoId));

        criador.deixarSomenteLeitura(conta.contaId());
        String desde = FormatoDeTela.data(CicloDeVencimento.inicioDoSomenteLeitura(hoje().minusDays(7)));

        assertThatExceptionOfType(ContaSomenteLeituraException.class)
                .isThrownBy(() -> conta.comoUsuario(() -> vendas.iniciar(sessaoId)))
                .withMessage("A Conta está só leitura desde " + desde + ", sem o pagamento do plano."
                        + " Para vender e mexer no caixa de novo, o administrador aplica o código do"
                        + " pagamento na tela Plano.");
        assertThatExceptionOfType(ContaSomenteLeituraException.class)
                .isThrownBy(() -> conta.comoUsuario(() ->
                        caixa.registrarSangria(sessaoId, Money.de("1.00"), "Depósito")));
        assertThatExceptionOfType(ContaSomenteLeituraException.class)
                .isThrownBy(() -> conta.comoUsuario(() ->
                        caixa.registrarSuprimentoOnline(sessaoId, UUID.randomUUID(),
                                Money.de("1.00"), "Troco")));
        assertThatExceptionOfType(ContaSomenteLeituraException.class)
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendas.receber(fiado, Money.de("2.00"), FormaPagamento.DINHEIRO)));
        assertThatExceptionOfType(ContaSomenteLeituraException.class)
                .isThrownBy(() -> conta.comoUsuario(() ->
                        pix.cobrar(comanda, UUID.randomUUID(), Money.de("5.00"))));

        // A comanda começada antes termina, e o caixa aberto fecha.
        conta.comoUsuario(() -> {
            vendas.adicionarItem(comanda, pao, BigDecimal.ONE, Money.ZERO);
            vendas.registrarPagamento(comanda,
                    SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("5.00")));
            vendas.concluir(comanda);
        });
        conta.comoUsuario(() -> caixa.fechar(sessaoId, Money.ZERO));

        // Abrir de novo, pela rota, é 409 com a frase da tela.
        http.perform(post("/api/caixa/sessoes").with(autenticador.como(conta))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valorAbertura\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "A Conta está só leitura desde " + desde)));

        // Consultar, cadastrar e mexer no estoque continuam.
        conta.comoUsuario(() -> faturamento.doDia(hoje()));
        cadastrar(conta, "Broa");
        criador.habilitarEstoque(conta.contaId());
        conta.comoUsuario(() -> estoque.ajustar(pao, new BigDecimal("3"), "Contagem"));
    }

    @Test
    @DisplayName("o operador vê o mesmo só leitura, o administrador fecha o caixa dele, e o Pix integrado sai da identidade")
    void operadorNoSomenteLeitura() throws Exception {
        ContaCriada conta = criador.criar("Banca Aurora", SENHA, Perfil.OPERADOR, true);
        UsuarioCriado dona = criador.criarAdminEm(conta.contaId(), "Dona");
        UUID sessaoId = conta.comoUsuario(() -> caixa.abrir(Money.ZERO));

        criador.deixarSomenteLeitura(conta.contaId());

        assertThatExceptionOfType(ContaSomenteLeituraException.class)
                .isThrownBy(() -> conta.comoUsuario(() -> vendas.iniciar(sessaoId)));
        http.perform(get("/api/auth/eu").with(autenticador.como(conta)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacaoDoPlano").value("SOMENTE_LEITURA"))
                .andExpect(jsonPath("$.pixIntegrado").value(false))
                .andExpect(jsonPath("$.recursos").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("PIX_INTEGRADO"))));
        dona.comoUsuario(() -> caixa.fechar(sessaoId, Money.ZERO));
    }

    @Test
    @DisplayName("o lote aplica o gesto feito antes do só leitura e recusa o feito depois, com a frase na revisão do administrador")
    void loteAvaliaOInstanteDoGesto() {
        ContaCriada conta = criador.criar("Quitanda Aurora", SENHA);
        UUID pao = cadastrar(conta, "Pão");
        criador.deixarSomenteLeitura(conta.contaId());
        LocalDate vencimento = hoje().minusDays(7);
        Instant antes = vencimento.atTime(12, 0).atZone(FusoDeReferencia.DO_BALCAO).toInstant();

        UUID sessaoId = UUID.randomUUID();
        UUID vendaAntes = UUID.randomUUID();
        UUID vendaDepois = UUID.randomUUID();
        GestoDeTeste abertura = GestoDeTeste.de("caixa.abrir", sessaoId,
                conteudo("valorAbertura", BigDecimal.ZERO, "abertaEm", antes.toString()))
                .registradoEm(antes);
        GestoDeTeste inicioAntes = GestoDeTeste.de("venda.iniciar", vendaAntes,
                conteudo("sessaoCaixaId", sessaoId, "criadoEm", antes.toString()), abertura)
                .registradoEm(antes);
        GestoDeTeste inicioDepois = GestoDeTeste.inicio(vendaDepois, sessaoId, abertura);
        GestoDeTeste itemDepois = GestoDeTeste.item(vendaDepois, UUID.randomUUID(), pao, "1",
                "5.00", inicioDepois);
        GestoDeTeste sangriaDepois = GestoDeTeste.sangria(sessaoId, "1.00", abertura);

        List<ResultadoDaOperacao> resultados = conta.comoUsuario(() -> sincronizacao.sincronizar(
                List.of(abertura, inicioAntes, inicioDepois, itemDepois, sangriaDepois).stream()
                        .map(gesto -> gesto.recebida(json)).toList()));

        Map<UUID, ResultadoDaOperacao> porGesto = resultados.stream()
                .collect(Collectors.toMap(ResultadoDaOperacao::operacaoId, resultado -> resultado));
        assertThat(porGesto.get(abertura.operacaoId()).resultado()).isEqualTo(Resultado.APLICADA);
        assertThat(porGesto.get(inicioAntes.operacaoId()).resultado()).isEqualTo(Resultado.APLICADA);
        assertThat(porGesto.get(inicioDepois.operacaoId()).resultado())
                .isEqualTo(Resultado.NAO_APLICADA);
        assertThat(porGesto.get(inicioDepois.operacaoId()).detalhe())
                .startsWith("A Conta está só leitura desde");
        assertThat(porGesto.get(itemDepois.operacaoId()).detalhe())
                .isEqualTo("A venda parou no passo anterior, que foi recusado.");
        assertThat(porGesto.get(sangriaDepois.operacaoId()).resultado())
                .isEqualTo(Resultado.NAO_APLICADA);
        assertThat(porGesto.get(sangriaDepois.operacaoId()).detalhe())
                .startsWith("A Conta está só leitura desde");
        assertThat(conta.comoUsuario(revisoes::pendentes))
                .filteredOn(revisao -> revisao.registroId().equals(vendaDepois)
                        && revisao.tipo().equals("venda.iniciar"))
                .extracting(RevisaoDeOperacao::detalhe)
                .singleElement().asString().contains("só leitura");
    }

    @Test
    @DisplayName("no só leitura o pedido é de adesão, e o código aplicado devolve tudo com o ciclo recomeçando no dia")
    void pagamentoNoSomenteLeituraRecomecaOCiclo() {
        ContaCriada conta = criador.criar("Empório Aurora", SENHA);
        criador.deixarSomenteLeitura(conta.contaId());

        PedidoNaConta pedido = conta.comoUsuario(() -> planos.pedir(Plano.COMPLETO));
        assertThat(pedido.tipo()).isEqualTo(PedidoDePlano.Tipo.ADESAO);
        assertThat(pedido.periodoInicio()).isNull();

        EstadoDoPlano estado = conta.comoUsuario(() -> planos.aplicarCodigo(pedido.id(),
                assinatura.codigo(pedido.id(), Plano.COMPLETO, pedido.valor())));

        LocalDate hoje = hoje();
        assertThat(estado.situacao()).isEqualTo(SituacaoDoPlano.EM_DIA);
        assertThat(estado.emTeste()).isFalse();
        assertThat(estado.vencimento())
                .isEqualTo(CicloDeVencimento.seguinte(hoje, hoje.getDayOfMonth()));
        UUID sessaoId = conta.comoUsuario(() -> caixa.abrir(Money.ZERO));
        conta.comoUsuario(() -> vendas.iniciar(sessaoId));
        conta.comoUsuario(() -> contaService.exigirOperacao(Instant.now()));
    }

    @Test
    @DisplayName("a renovação pedida na tolerância e aplicada já no só leitura mantém o dia do vencimento")
    void renovacaoDaToleranciaAplicadaNoSomenteLeitura() {
        ContaCriada conta = criador.criar("Armazém Aurora", SENHA);
        criador.vencerPlano(conta.contaId(), 2);
        LocalDate vencimento = hoje().minusDays(2);
        PedidoNaConta pedido = conta.comoUsuario(() -> planos.pedir(Plano.COMPLETO));
        assertThat(pedido.tipo()).isEqualTo(PedidoDePlano.Tipo.RENOVACAO);
        assertThat(pedido.periodoInicio()).isEqualTo(vencimento);

        // O mantenedor demorou a conferir o Pix, e a Conta chegou ao só leitura antes do código.
        criador.deixarSomenteLeitura(conta.contaId());
        EstadoDoPlano estado = conta.comoUsuario(() -> planos.aplicarCodigo(pedido.id(),
                assinatura.codigo(pedido.id(), Plano.COMPLETO, pedido.valor())));

        assertThat(estado.vencimento()).isEqualTo(pedido.periodoFim())
                .isEqualTo(CicloDeVencimento.seguinte(vencimento, vencimento.getDayOfMonth()));
        assertThat(estado.situacao()).isEqualTo(SituacaoDoPlano.EM_DIA);
        assertThat(estado.emTeste()).isFalse();
    }

    private UUID vendaNoFiado(UUID sessaoId, UUID produtoId, UUID clienteId) {
        UUID vendaId = vendas.iniciar(sessaoId);
        vendas.adicionarItem(vendaId, produtoId, BigDecimal.ONE, Money.ZERO);
        vendas.vincularCliente(vendaId, clienteId);
        vendas.registrarPagamento(vendaId,
                SolicitacaoPagamento.de(FormaPagamento.FIADO, Money.de("5.00")));
        vendas.concluir(vendaId);
        return vendaId;
    }

    private UUID cadastrar(ContaCriada conta, String nome) {
        return conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto(nome, Money.de("5.00"), null, null, "un", null)));
    }

    private static LocalDate hoje() {
        return LocalDate.now(FusoDeReferencia.DO_BALCAO);
    }
}
