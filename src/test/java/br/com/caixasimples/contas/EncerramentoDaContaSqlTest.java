package br.com.caixasimples.contas;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.caixa.SessaoCaixaFechada;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.application.PlanoService;
import br.com.caixasimples.contas.application.TermosDeUsoService;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.sincronizacao.GestoDeTeste;
import br.com.caixasimples.sincronizacao.application.SincronizacaoService;
import br.com.caixasimples.vendas.application.VendaService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventSerializer;
import tools.jackson.databind.ObjectMapper;

/** Executa o mesmo SQL do procedimento contra o PostgreSQL real do ensaio de integração. */
class EncerramentoDaContaSqlTest extends TesteDeIntegracao {

    /**
     * Toda tabela com a coluna da Conta. O SQL roda fora do filtro de tenant, então cada
     * {@code DELETE} depende do próprio filtro, e só a Conta preservada com linha em cada uma delas
     * mostra que nenhum filtro faltou: sem ela, o filtro tirado de uma tabela não mudaria nada.
     */
    private static final List<String> TABELAS_DA_CONTA = List.of("usuario", "credencial",
            "cliente", "produto", "sessao_caixa", "movimento_caixa", "venda", "item_venda",
            "pagamento", "recebimento", "movimento_estoque", "operacao_sincronizada",
            "pedido_de_plano", "aceite_dos_termos");

    @Autowired CriadorDeContaDeTeste criador;
    @Autowired ClienteService clientes;
    @Autowired JdbcTemplate banco;
    @Autowired PlanoService planos;
    @Autowired TermosDeUsoService termos;
    @Autowired ProdutoService produtos;
    @Autowired SessaoCaixaService caixas;
    @Autowired VendaService vendas;
    @Autowired SincronizacaoService sincronizacao;
    @Autowired ObjectMapper json;
    @Autowired EventSerializer serializador;

    @Test
    void encerraUmaContaSemApagarAOutra() throws Exception {
        ContaCriada encerrada = criador.criar("Conta encerrada", "senha longa de teste");
        ContaCriada preservada = criador.criar("Conta preservada", "senha longa de teste");
        darUmaLinhaEmCadaTabela(encerrada);
        darUmaLinhaEmCadaTabela(preservada);
        Map<String, Integer> antes = contagens(preservada);
        assertThat(antes).allSatisfy((tabela, linhas) -> assertThat(linhas).as(tabela).isPositive());
        assertThat(contagens(encerrada))
                .allSatisfy((tabela, linhas) -> assertThat(linhas).as(tabela).isPositive());

        String sql = Files.readString(Path.of("operacao/exclusao/apagar-conta.sql"))
                .replace("\\set ON_ERROR_STOP on", "")
                .replace(":'conta_id'::uuid", "'" + encerrada.contaId() + "'::uuid");
        banco.execute(sql);

        assertThat(contarContas(encerrada)).isZero();
        assertThat(contagens(encerrada))
                .allSatisfy((tabela, linhas) -> assertThat(linhas).as(tabela).isZero());
        assertThat(contarContas(preservada)).isEqualTo(1);
        assertThat(contagens(preservada))
                .as("a outra Conta termina com as mesmas linhas, tabela a tabela")
                .isEqualTo(antes);
    }

    /**
     * O outbox não tem coluna de Conta, e o encerramento acha o evento pela Conta no texto
     * serializado. O evento é o real, serializado pelo mesmo serializador do registro de
     * publicações, para a prova falhar se a Conta deixar de aparecer no texto. A linha nasce
     * concluída, para nenhum reenvio a tocar durante o teste.
     */
    @Test
    void encerramentoApagaSoOsEventosDaConta() throws Exception {
        ContaCriada encerrada = criador.criar("Conta encerrada", "senha longa de teste");
        ContaCriada preservada = criador.criar("Conta preservada", "senha longa de teste");
        UUID daEncerrada = publicacaoDe(encerrada);
        UUID daPreservada = publicacaoDe(preservada);

        banco.execute(Files.readString(Path.of("operacao/exclusao/apagar-conta.sql"))
                .replace("\\set ON_ERROR_STOP on", "")
                .replace(":'conta_id'::uuid", "'" + encerrada.contaId() + "'::uuid"));

        assertThat(publicacoes(daEncerrada)).as("o evento da Conta encerrada sai").isZero();
        assertThat(publicacoes(daPreservada)).as("o da outra Conta fica").isEqualTo(1);
    }

    /**
     * Os dois scripts do Usuário, na restauração, filtram pela Conta do registro e pelo id. O par
     * trocado, com a Conta de uma e o Usuário da outra, não toca ninguém; o par certo alcança só o
     * Usuário dele.
     */
    @Test
    void reaplicacaoDoUsuarioNaoAlcancaOutraConta() throws Exception {
        for (String arquivo : List.of("reaplicar-usuario.sql", "reaplicar-nome-usuario.sql")) {
            ContaCriada daRemocao = criador.criar("Conta da remoção", "senha longa de teste");
            ContaCriada vizinha = criador.criar("Conta vizinha", "senha longa de teste");
            Map<String, Object> daRemocaoAntes = usuarioECredencial(daRemocao);
            Map<String, Object> vizinhaAntes = usuarioECredencial(vizinha);

            banco.execute(reaplicacao(arquivo, daRemocao, vizinha.usuarioId()));
            assertThat(usuarioECredencial(vizinha)).as(arquivo + ", par trocado")
                    .isEqualTo(vizinhaAntes);
            assertThat(usuarioECredencial(daRemocao)).as(arquivo + ", par trocado")
                    .isEqualTo(daRemocaoAntes);

            banco.execute(reaplicacao(arquivo, daRemocao, daRemocao.usuarioId()));
            assertThat(usuarioECredencial(daRemocao)).as(arquivo + ", par certo")
                    .containsEntry("ativo", false)
                    .containsEntry("credenciais", 0L)
                    .containsEntry("nome", arquivo.contains("nome")
                            ? "Usuário removido" : daRemocaoAntes.get("nome"));
            assertThat(usuarioECredencial(vizinha)).as(arquivo + ", par certo")
                    .isEqualTo(vizinhaAntes);
        }
    }

    @Test
    void restauracaoNaoTrazDeVoltaIdentidadeDoCliente() throws Exception {
        ContaCriada conta = criador.criar("Conta restaurada", "senha longa de teste");
        UUID clienteId = conta.comoUsuario(() -> clientes.cadastrar(
                new DadosDoCliente("Nome antigo", "contato@exemplo.test")));
        conta.comoUsuario(() -> clientes.remover(clienteId));

        // Simula uma cópia feita antes da remoção, ainda com o dado pessoal.
        banco.update("UPDATE cliente SET nome = 'Nome antigo', contato = 'contato@exemplo.test', "
                + "ativo = true, removido_em = NULL WHERE conta_id = ? AND id = ?",
                conta.contaId().valor(), clienteId);
        String sql = Files.readString(Path.of("operacao/copia/reaplicar-cliente.sql"))
                .replace("\\set ON_ERROR_STOP on", "")
                .replace(":'conta_id'::uuid", "'" + conta.contaId() + "'::uuid")
                .replace(":'registro_id'::uuid", "'" + clienteId + "'::uuid")
                .replace(":'instante'::timestamptz", "'2026-09-29T12:00:00Z'::timestamptz");
        banco.execute(sql);

        assertThat(banco.queryForMap("SELECT nome, contato, ativo, removido_em FROM cliente "
                + "WHERE conta_id = ? AND id = ?", conta.contaId().valor(), clienteId))
                .containsEntry("nome", "Cliente removido")
                .containsEntry("contato", null)
                .containsEntry("ativo", false);
    }

    /**
     * Pelo menos uma linha em cada tabela da lista, gravada pelos casos de uso, como a Conta em
     * operação as grava. Usuário e credencial vêm da criação da Conta; a Venda fiada, concluída com
     * o estoque ligado, dá o item, a parcela e a baixa do produto; o recebimento em dinheiro dá o
     * movimento do caixa; o cliente criado pelo lote dá o registro da operação; e o administrador
     * pede o plano e aceita os termos.
     */
    private void darUmaLinhaEmCadaTabela(ContaCriada conta) {
        criador.habilitarEstoque(conta.contaId());
        conta.comoUsuario(() -> {
            UUID produtoId = produtos.cadastrar(TipoProduto.PRODUTO,
                    new DadosDoProduto("Café", Money.de("10.00"), null, null, "un", null));
            UUID sessaoId = caixas.abrir(Money.ZERO);
            UUID clienteId = clientes.cadastrar(new DadosDoCliente("Cliente antigo", null));
            UUID vendaId = vendas.iniciar(sessaoId);
            vendas.adicionarItem(vendaId, produtoId, BigDecimal.ONE, Money.ZERO);
            vendas.vincularCliente(vendaId, clienteId);
            vendas.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.FIADO, Money.de("10.00")));
            vendas.concluir(vendaId);
            vendas.receber(vendaId, Money.de("4.00"), FormaPagamento.DINHEIRO);
            sincronizacao.sincronizar(List.of(GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                    conteudo("nome", "Cliente sem rede", "contato", null)).recebida(json)));
            // Um pedido substituído e outro aberto: os dois apontam para o usuário que sai depois
            // deles.
            planos.pedir(Plano.COMPLETO);
            planos.pedir(Plano.COMPLETO);
            // O aceite também aponta para o usuário.
            termos.aceitar(TermosDeUsoService.VERSAO_VIGENTE);
        });
    }

    /** Quantas linhas a Conta tem em cada tabela da lista, na ordem dela. */
    private Map<String, Integer> contagens(ContaCriada conta) {
        Map<String, Integer> porTabela = new LinkedHashMap<>();
        for (String tabela : TABELAS_DA_CONTA) {
            porTabela.put(tabela, banco.queryForObject(
                    "SELECT count(*) FROM " + tabela + " WHERE conta_id = ?", Integer.class,
                    conta.contaId().valor()));
        }
        return porTabela;
    }

    /** Uma publicação concluída do evento do fechamento do caixa da Conta, como o registro grava. */
    private UUID publicacaoDe(ContaCriada conta) {
        UUID id = UUID.randomUUID();
        Object evento = serializador.serialize(
                new SessaoCaixaFechada(conta.contaId(), UUID.randomUUID(), Instant.now()));
        banco.update("INSERT INTO event_publication (id, listener_id, event_type, "
                        + "serialized_event, publication_date, completion_date, status, "
                        + "completion_attempts) VALUES (?, ?, ?, ?, now(), now(), 'COMPLETED', 1)",
                id, "ouvinte.do.teste", SessaoCaixaFechada.class.getName(), evento.toString());
        return id;
    }

    private int publicacoes(UUID id) {
        return banco.queryForObject("SELECT count(*) FROM event_publication WHERE id = ?",
                Integer.class, id);
    }

    private static String reaplicacao(String arquivo, ContaCriada conta, UUID registro)
            throws Exception {
        return Files.readString(Path.of("operacao/copia/" + arquivo))
                .replace("\\set ON_ERROR_STOP on", "")
                .replace(":'conta_id'::uuid", "'" + conta.contaId() + "'::uuid")
                .replace(":'registro_id'::uuid", "'" + registro + "'::uuid");
    }

    /** O nome, a situação e quantas credenciais o Usuário dono da Conta tem. */
    private Map<String, Object> usuarioECredencial(ContaCriada conta) {
        return banco.queryForMap("SELECT u.nome, u.ativo, (SELECT count(*) FROM credencial c "
                + "WHERE c.usuario_id = u.id) AS credenciais FROM usuario u WHERE u.id = ?",
                conta.usuarioId());
    }

    private int contarContas(ContaCriada conta) {
        return banco.queryForObject("SELECT count(*) FROM conta WHERE id = ?", Integer.class,
                conta.contaId().valor());
    }
}
