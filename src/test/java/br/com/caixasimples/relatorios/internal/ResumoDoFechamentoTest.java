package br.com.caixasimples.relatorios.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.RegistroDeRemocoesDeTeste;
import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.caixa.SessaoCaixaFechada;
import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.application.UsuarioService;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste.ItemDeTeste;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste.ParcelaDeTeste;
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Pagamento;
import br.com.caixasimples.vendas.domain.Venda;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

/**
 * O resumo do caixa fechado por e-mail, de ponta a ponta: o fechamento pelo caso de uso, o evento
 * pelo registro de publicação, o ouvinte assíncrono, as consultas por sessão contra o Postgres real
 * e o texto que sai, com o SES trocado por um falso que grava cada pedido e falha quando mandado.
 *
 * <p>O remetente é ligado só neste contexto; no resto da suíte ele fica em branco, e o resumo,
 * desligado, conclui cada entrega sem enviar nada.
 *
 * <p>Cada teste espera a entrega da própria sessão terminar, concluída ou marcada como falha, antes
 * de olhar o que o falso recebeu: o ouvinte roda noutra thread, depois do commit.
 */
@TestPropertySource(properties = "ecrx-caixa.resumo.remetente=" + ResumoDoFechamentoTest.REMETENTE)
@Import(ResumoDoFechamentoTest.ComSesFalso.class)
@ExtendWith(OutputCaptureExtension.class)
class ResumoDoFechamentoTest extends TesteDeIntegracao {

    static final String REMETENTE = "resumo@caixa.exemplo.test";

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    private static final LocalDate DIA = LocalDate.of(2026, 11, 18);

    @Autowired private SesFalso ses;
    @Autowired private SessaoCaixaService caixas;
    @Autowired private ProdutoService produtos;
    @Autowired private ClienteService clientes;
    @Autowired private UsuarioService usuarios;
    @Autowired private CriadorDeContaDeTeste criador;
    @Autowired private CriadorDeVendaDeTeste vendas;
    @Autowired private RegistroDeRemocoesDeTeste registroDeRemocoes;
    @Autowired private EventPublicationRegistry registroDePublicacoes;
    @Autowired private CompletedEventPublications publicacoesConcluidas;
    @Autowired private ReenvioDoResumo reenvio;
    @Autowired private ApplicationEventPublisher publicador;
    @Autowired private TransactionTemplate transacao;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void zerarOFalso() {
        ses.voltar();
        ses.limpar();
    }

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
        registroDeRemocoes.limpar();
        ses.voltar();
    }

    @Test
    @DisplayName("o resumo da sessão sai com os números só dela, sem nome de pessoa nem motivo de sangria")
    void conteudoDaSessao() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        Catalogo catalogo = cadastrarCatalogo(conta);
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(UUID.randomUUID(), Money.de("50.00"),
                noBalcao(LocalTime.of(8, 0))));

        // Dois cafés e um pão de queijo, pagos metade em dinheiro e metade em Pix.
        vendas.criarConcluidaComParcelasEm(conta.contaId(), sessaoId, conta.usuarioId(),
                List.of(new ItemDeTeste(catalogo.cafe(), new BigDecimal("2"), Money.de("4.50"),
                                Money.ZERO),
                        ItemDeTeste.unitario(catalogo.paoDeQueijo(), Money.de("6.00"))),
                List.of(ParcelaDeTeste.confirmada(FormaPagamento.DINHEIRO, Money.de("10.00")),
                        ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("5.00"))),
                noBalcao(LocalTime.of(9, 0)));
        // Queijo a peso e uma fatia de bolo, no fiado de uma cliente com nome.
        UUID cliente = conta.comoUsuario(() ->
                clientes.cadastrar(new DadosDoCliente("Lia Fernandes", null)));
        vendaFiada(conta, sessaoId, cliente, catalogo);
        // Fora do resumo: a venda cancelada, a comanda aberta e a venda de outro caixa.
        vendas.criarCanceladaComItensQueConcluiuEm(conta.contaId(), sessaoId, conta.usuarioId(),
                List.of(ItemDeTeste.unitario(catalogo.bolo(), Money.de("9.00"))),
                noBalcao(LocalTime.of(11, 0)));
        vendas.criarAbertaComItens(conta.contaId(), sessaoId, conta.usuarioId(),
                List.of(ItemDeTeste.unitario(catalogo.cafe(), Money.de("4.50"))));
        var colega = criador.criarOperadorEm(conta.contaId(), "Joana Colega");
        UUID caixaDaColega = colega.comoUsuario(() -> caixas.abrir(Money.ZERO));
        vendas.criarConcluidaComItensEm(conta.contaId(), caixaDaColega, colega.usuarioId(),
                List.of(new ItemDeTeste(catalogo.paoDeQueijo(), new BigDecimal("5"),
                        Money.de("6.00"), Money.ZERO)),
                noBalcao(LocalTime.of(12, 0)));

        conta.comoUsuario(() -> {
            caixas.registrarSangria(sessaoId, Money.de("8.00"), "Pagamento ao entregador Joaquim",
                    noBalcao(LocalTime.of(14, 0)));
            caixas.registrarSangria(sessaoId, Money.de("4.00"), "Gelo",
                    noBalcao(LocalTime.of(15, 0)));
            // Esperado de 38,00: abertura de 50,00 menos 12,00 de sangria. Contados 35,00.
            caixas.fechar(sessaoId, Money.de("35.00"), noBalcao(LocalTime.of(18, 40)));
        });

        esperarConcluida(sessaoId);
        assertThat(ses.pedidos()).hasSize(1);
        SendEmailRequest pedido = ses.pedidos().getFirst();
        assertThat(pedido.fromEmailAddress()).isEqualTo(REMETENTE);
        assertThat(pedido.destination().toAddresses()).containsExactly(conta.email());
        assertThat(assunto(pedido))
                .isEqualTo("Resumo do caixa de Cafeteria Aurora, fechado em 18/11/2026 às 18:40");
        assertThat(corpo(pedido)).isEqualTo("""
                Resumo do caixa de Cafeteria Aurora

                Aberto em 18/11/2026 às 08:00 e fechado em 18/11/2026 às 18:40.

                Faturamento: R$ 38,00 em 2 vendas.

                Por forma de pagamento:
                - Dinheiro: R$ 10,00
                - Pix: R$ 5,00
                - Fiado: R$ 23,00

                Sangrias: R$ 12,00 em 2 retiradas.
                Diferença no fechamento: falta de R$ 3,00.

                Mais vendidos:
                1. Queijo minas: 0,35 kg, R$ 14,00
                2. Bolo de fubá: 1 un, R$ 9,00
                3. Café coado: 2 un, R$ 9,00

                Este resumo vai para o administrador da Conta a cada caixa fechado no ECRX Caixa. \
                Este endereço não recebe respostas.
                """);
        assertThat(corpo(pedido)).doesNotContain("Pessoa de", "Joana", "Lia", "Joaquim", "Gelo");
    }

    @Test
    @DisplayName("recebem os ADMIN ativos, cada um num envio; o OPERADOR que fechou e o ADMIN bloqueado não")
    void soOsAdministradoresAtivos() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        String outroAdmin = "segundo-" + UUID.randomUUID() + "@exemplo.test";
        String bloqueado = "bloqueado-" + UUID.randomUUID() + "@exemplo.test";
        String operador = "operador-" + UUID.randomUUID() + "@exemplo.test";
        UUID operadorId = conta.comoUsuario(() -> {
            usuarios.criar("Segunda Administradora", Perfil.ADMIN, outroAdmin, SENHA_DE_TESTE);
            UUID aBloquear = usuarios.criar("Administrador Saindo", Perfil.ADMIN, bloqueado,
                    SENHA_DE_TESTE);
            // Com o registro de remoções fora do ar, a inativação bloqueia e guarda a credencial.
            registroDeRemocoes.ficarForaDoAr();
            usuarios.inativar(aBloquear);
            registroDeRemocoes.voltar();
            return usuarios.criar("Atendente da Tarde", Perfil.OPERADOR, operador, SENHA_DE_TESTE);
        });

        UUID sessaoId = comoOperador(conta.contaId(), operadorId, () -> {
            UUID aberta = caixas.abrir(Money.ZERO);
            caixas.fechar(aberta, Money.ZERO);
            return aberta;
        });

        esperarConcluida(sessaoId);
        assertThat(ses.pedidos()).extracting(pedido -> pedido.destination().toAddresses())
                .containsExactly(List.of(conta.email()), List.of(outroAdmin));
        assertThat(corpo(ses.pedidos().getFirst()))
                .contains("Faturamento: nenhuma venda concluída neste caixa.")
                .contains("Sangrias: nenhuma.")
                .contains("Diferença no fechamento: sem diferença.")
                .contains("Nenhum produto vendido neste caixa.");
    }

    @Test
    @DisplayName("o envio que falha não desfaz o fechamento, e o reenvio manda uma vez quando o SES volta")
    void falhaNaoDesfazOFechamentoEOReenvioManda(CapturedOutput saida) {
        ContaCriada conta = criador.criar("Mercearia Boa Vista", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        ses.ficarForaDoAr();

        UUID sessaoId = conta.comoUsuario(() -> {
            UUID aberta = caixas.abrir(Money.de("20.00"));
            caixas.fechar(aberta, Money.de("20.00"));
            return aberta;
        });

        assertThat(conta.comoUsuario(() -> caixas.consultar(sessaoId).status()))
                .isEqualTo(StatusSessaoCaixa.FECHADA);
        esperarStatus(sessaoId, EventPublication.Status.FAILED);
        assertThat(ses.pedidos()).isEmpty();
        assertThat(saida.getOut()).contains("resumo.falhou conta=" + conta.contaId())
                .doesNotContain("assincrono.falhou")
                .doesNotContain(conta.email());

        // Tentada agora, a entrega não volta na rodada seguinte: ainda podia estar em andamento.
        ses.voltar();
        reenvio.reenviar();
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> statusDe(sessaoId).orElse(null) == EventPublication.Status.FAILED);
        assertThat(ses.pedidos()).isEmpty();

        // Tentada há mais de 10 minutos, volta, e manda uma vez só.
        envelhecerAEntrega(sessaoId, Duration.ofMinutes(11));
        reenvio.reenviar();
        esperarConcluida(sessaoId);
        assertThat(ses.pedidos()).extracting(pedido -> pedido.destination().toAddresses())
                .containsExactly(List.of(conta.email()));
        reenvio.reenviar();
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> ses.pedidos().size() == 1);
    }

    @Test
    @DisplayName("passadas 24 horas do fechamento, a entrega desiste sem enviar e deixa uma linha de erro")
    void desisteDepoisDoPrazo(CapturedOutput saida) {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        UUID sessaoId = UUID.randomUUID();
        SessaoCaixaFechada antigo = new SessaoCaixaFechada(conta.contaId(), sessaoId,
                Instant.now().minus(ResumoDoFechamentoListener.PRAZO_DA_REENTREGA)
                        .minusSeconds(60));

        conta.comoUsuario(() ->
                transacao.executeWithoutResult(status -> publicador.publishEvent(antigo)));

        esperarConcluida(sessaoId);
        assertThat(ses.pedidos()).isEmpty();
        assertThat(saida.getOut()).containsPattern(
                "ERROR.*resumo\\.abandonado conta=" + conta.contaId() + " sessao=" + sessaoId);
    }

    @Test
    @DisplayName("o plano gratuito e o completo com os recursos suspensos não recebem o resumo")
    void soOCompletoEmDia() {
        ContaCriada gratuita = criador.criar("Banca da Feira", SENHA_DE_TESTE);
        ContaCriada suspensa = criador.criar("Loja da Esquina", SENHA_DE_TESTE);
        criador.contratar(suspensa.contaId(), Plano.COMPLETO);
        criador.vencerPlano(suspensa.contaId(), 8);

        UUID daGratuita = abrirEFechar(gratuita);
        UUID daSuspensa = abrirEFechar(suspensa);

        esperarConcluida(daGratuita);
        esperarConcluida(daSuspensa);
        assertThat(ses.pedidos()).isEmpty();
    }

    @Test
    @DisplayName("o resumo de uma Conta não chega ao administrador de outra nem leva os números dela")
    void isolamentoEntreContas() {
        ContaCriada contaA = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Padaria Central", SENHA_DE_TESTE);
        criador.contratar(contaA.contaId(), Plano.COMPLETO);
        criador.contratar(contaB.contaId(), Plano.COMPLETO);
        UUID sessaoA = abrirComVenda(contaA, "Croissant da Aurora", "7.00");
        UUID sessaoB = abrirComVenda(contaB, "Sonho da Central", "11.00");

        contaA.comoUsuario(() -> caixas.fechar(sessaoA, Money.ZERO));
        esperarConcluida(sessaoA);
        assertThat(ses.pedidos()).hasSize(1);
        SendEmailRequest paraA = ses.pedidos().getFirst();
        assertThat(paraA.destination().toAddresses()).containsExactly(contaA.email());
        assertThat(corpo(paraA)).contains("Croissant da Aurora", "R$ 7,00")
                .doesNotContain("Sonho da Central", "R$ 11,00", "Padaria Central");

        contaB.comoUsuario(() -> caixas.fechar(sessaoB, Money.ZERO));
        esperarConcluida(sessaoB);
        assertThat(ses.pedidos()).hasSize(2);
        SendEmailRequest paraB = ses.pedidos().get(1);
        assertThat(paraB.destination().toAddresses()).containsExactly(contaB.email());
        assertThat(corpo(paraB)).contains("Sonho da Central", "R$ 11,00")
                .doesNotContain("Croissant da Aurora", "R$ 7,00", "Cafeteria Aurora");
    }

    @Test
    @DisplayName("as consultas do resumo não leem linha de outra Conta, nem por id de sessão alheio")
    void consultasFiltradasPelaConta() {
        ContaCriada contaA = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Padaria Central", SENHA_DE_TESTE);
        criador.contratar(contaA.contaId(), Plano.COMPLETO);
        criador.contratar(contaB.contaId(), Plano.COMPLETO);
        UUID sessaoA = abrirComVenda(contaA, "Croissant da Aurora", "7.00");
        // Uma venda da Conta B que aponta para a sessão da Conta A: a chave estrangeira não exige a
        // mesma Conta, e só o filtro de cada mapeamento a deixa fora do resumo de A.
        UUID produtoB = contaB.comoUsuario(() -> produto("Sonho da Central", "50.00", "un"));
        vendas.criarConcluidaEm(contaB.contaId(), sessaoA, contaB.usuarioId(), produtoB,
                Money.de("50.00"), Instant.now());

        contaA.comoUsuario(() -> caixas.fechar(sessaoA, Money.ZERO));
        esperarConcluidas(sessaoA, 1);
        // O mesmo fato anunciado pela Conta B: sob o filtro dela, a sessão de A não existe.
        contaB.comoUsuario(() -> transacao.executeWithoutResult(status -> publicador.publishEvent(
                new SessaoCaixaFechada(contaB.contaId(), sessaoA, Instant.now()))));
        esperarConcluidas(sessaoA, 2);

        assertThat(ses.pedidos()).hasSize(1);
        SendEmailRequest paraA = ses.pedidos().getFirst();
        assertThat(paraA.destination().toAddresses()).containsExactly(contaA.email());
        assertThat(corpo(paraA)).contains("Faturamento: R$ 7,00 em 1 venda.", "- Dinheiro: R$ 7,00",
                        "1. Croissant da Aurora: 1 un, R$ 7,00")
                .doesNotContain("Sonho da Central", "R$ 50,00", "R$ 57,00");
    }

    private UUID abrirEFechar(ContaCriada conta) {
        return conta.comoUsuario(() -> {
            UUID aberta = caixas.abrir(Money.ZERO);
            caixas.fechar(aberta, Money.ZERO);
            return aberta;
        });
    }

    private UUID abrirComVenda(ContaCriada conta, String produto, String preco) {
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID produtoId = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto(produto, Money.de(preco), null, null, "un", null)));
        vendas.criarConcluidaEm(conta.contaId(), sessaoId, conta.usuarioId(), produtoId,
                Money.de(preco), Instant.now());
        return sessaoId;
    }

    private Catalogo cadastrarCatalogo(ContaCriada conta) {
        return conta.comoUsuario(() -> new Catalogo(
                produto("Café coado", "4.50", "un"),
                produto("Pão de queijo", "6.00", "un"),
                produto("Queijo minas", "40.00", "kg"),
                produto("Bolo de fubá", "9.00", "un")));
    }

    private UUID produto(String nome, String preco, String unidade) {
        return produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto(nome, Money.de(preco), null, null, unidade, null));
    }

    /** 0,350 kg de queijo a 40,00 e uma fatia de bolo a 9,00: 23,00 no fiado. */
    private void vendaFiada(ContaCriada conta, UUID sessaoId, UUID clienteId, Catalogo catalogo) {
        Instant concluidaEm = noBalcao(LocalTime.of(10, 0));
        List<ItemVenda> itens = List.of(
                new ItemVenda(UUID.randomUUID(), catalogo.queijo(), new BigDecimal("0.350"),
                        Money.de("40.00"), Money.ZERO, concluidaEm),
                new ItemVenda(UUID.randomUUID(), catalogo.bolo(), BigDecimal.ONE,
                        Money.de("9.00"), Money.ZERO, concluidaEm));
        Pagamento fiado = new Pagamento(UUID.randomUUID(), FormaPagamento.FIADO,
                Money.de("23.00"), StatusPagamento.PENDENTE, Money.ZERO, concluidaEm);
        vendas.gravar(conta.contaId(), Venda.reconstituir(UUID.randomUUID(), sessaoId,
                conta.usuarioId(), clienteId, StatusVenda.CONCLUIDA, Money.de("23.00"), Money.ZERO,
                concluidaEm, concluidaEm, itens, List.of(fiado)));
    }

    private <T> T comoOperador(ContaId contaId, UUID usuarioId, java.util.function.Supplier<T> acao) {
        return new CriadorDeContaDeTeste.UsuarioCriado(contaId, usuarioId, Perfil.OPERADOR)
                .comoUsuario(acao);
    }

    private static Instant noBalcao(LocalTime hora) {
        return DIA.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }

    private void esperarConcluida(UUID sessaoId) {
        await().atMost(Duration.ofSeconds(15)).until(() -> concluida(sessaoId));
    }

    /** Para quando a mesma sessão aparece em mais de um evento, como o forjado por outra Conta. */
    private void esperarConcluidas(UUID sessaoId, long quantas) {
        await().atMost(Duration.ofSeconds(15)).until(() -> publicacoesConcluidas.findAll().stream()
                .filter(publicacao -> daSessao(publicacao, sessaoId)).count() == quantas);
    }

    private void esperarStatus(UUID sessaoId, EventPublication.Status status) {
        await().atMost(Duration.ofSeconds(15))
                .until(() -> statusDe(sessaoId).orElse(null) == status);
    }

    private boolean concluida(UUID sessaoId) {
        return publicacoesConcluidas.findAll().stream().anyMatch(publicacao -> daSessao(publicacao, sessaoId));
    }

    private Optional<EventPublication.Status> statusDe(UUID sessaoId) {
        Collection<? extends EventPublication> naoConcluidas =
                registroDePublicacoes.findIncompletePublications();
        return naoConcluidas.stream().filter(publicacao -> daSessao(publicacao, sessaoId))
                .map(EventPublication::getStatus).findFirst();
    }

    private static boolean daSessao(EventPublication publicacao, UUID sessaoId) {
        return publicacao.getEvent() instanceof SessaoCaixaFechada evento
                && evento.sessaoCaixaId().equals(sessaoId);
    }

    /** Recua a publicação e o último reenvio, como se a tentativa tivesse sido há mais tempo. */
    private void envelhecerAEntrega(UUID sessaoId, Duration quanto) {
        int alteradas = jdbc.update("""
                UPDATE event_publication
                   SET publication_date = publication_date - make_interval(secs => ?),
                       last_resubmission_date = last_resubmission_date - make_interval(secs => ?)
                 WHERE completion_date IS NULL AND serialized_event LIKE ?
                """, quanto.toSeconds(), quanto.toSeconds(), "%" + sessaoId + "%");
        assertThat(alteradas).isEqualTo(1);
    }

    private static String assunto(SendEmailRequest pedido) {
        return pedido.content().simple().subject().data();
    }

    private static String corpo(SendEmailRequest pedido) {
        return pedido.content().simple().body().text().data();
    }

    private record Catalogo(UUID cafe, UUID paoDeQueijo, UUID queijo, UUID bolo) {
    }

    /** O SES deste contexto: grava cada pedido e, fora do ar, recusa como o provedor recusaria. */
    static class SesFalso implements SesV2Client {

        private final List<SendEmailRequest> pedidos = new CopyOnWriteArrayList<>();

        private volatile boolean foraDoAr;

        @Override
        public SendEmailResponse sendEmail(SendEmailRequest pedido) {
            if (foraDoAr) {
                throw new IllegalStateException("SES fora do ar para " + Stream.of(pedido)
                        .flatMap(p -> p.destination().toAddresses().stream()).toList());
            }
            pedidos.add(pedido);
            return SendEmailResponse.builder().messageId(UUID.randomUUID().toString()).build();
        }

        List<SendEmailRequest> pedidos() {
            return new ArrayList<>(pedidos);
        }

        void ficarForaDoAr() {
            foraDoAr = true;
        }

        void voltar() {
            foraDoAr = false;
        }

        void limpar() {
            pedidos.clear();
        }

        @Override
        public String serviceName() {
            return "ses";
        }

        @Override
        public void close() {
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ComSesFalso {

        @Bean
        @Primary
        SesFalso sesFalso() {
            return new SesFalso();
        }
    }
}
