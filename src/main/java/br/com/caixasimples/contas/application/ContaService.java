package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.internal.ChavePix;
import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.PrimeiroAcessoDaConta;
import br.com.caixasimples.contas.MovimentosDeEstoqueDaConta;
import java.time.Instant;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso da Conta em operação: consultar e configurar o estoque e a exigência do NSU no
 * cartão, corrigir o nome do negócio, marcar o primeiro acesso e conferir se a Conta está só leitura.
 * A chave Pix fica com a autenticação, porque a troca dela confere a senha atual.
 *
 * <p><strong>Nenhum método recebe a conta como parâmetro, e essa ausência é a regra.</strong>
 * {@code Conta} é a única entidade de negócio sem filtro automático de tenant, porque o id dela
 * <em>é</em> o tenant; em troca, toda leitura parte de {@code TenantContext.exigirAtual()} e
 * nunca de um id que alguém informe (RNF05). Quem precisa saber algo de outra conta não tem por
 * onde perguntar.
 *
 * <p>O módulo de estoque pergunta se a conta ligou o controle (RF17) antes de dar baixa numa
 * venda concluída, e o módulo de vendas pergunta se ela exige o NSU antes de gravar um pagamento
 * em cartão. O login chama a marca do primeiro acesso (RF32) dentro deste módulo, que publica o
 * fato para o cadastro reagir sem receber uma chamada direta de escrita. Vendas e caixa perguntam
 * se a Conta está só leitura antes de cada gesto que começa algo no balcão.
 */
@Service
public class ContaService {

    private final ContaRepository contas;
    private final ApplicationEventPublisher eventos;
    private final MovimentosDeEstoqueDaConta movimentos;

    ContaService(ContaRepository contas, ApplicationEventPublisher eventos,
            MovimentosDeEstoqueDaConta movimentos) {
        this.contas = contas;
        this.eventos = eventos;
        this.movimentos = movimentos;
    }

    /**
     * Se a conta em operação ligou o controle de estoque (RF17). Conta nova nasce com ele
     * desligado, porque negócio baseado em serviço não precisa dele.
     *
     * @throws br.com.caixasimples.shared.TenantNaoResolvidoException se não há conta no contexto
     * @throws IllegalStateException se a conta do contexto não existe, o que só acontece com um
     *         evento que carrega uma conta apagada do banco por fora da aplicação
     */
    @Transactional(readOnly = true)
    public boolean estoqueHabilitado() {
        ContaId contaId = TenantContext.exigirAtual();
        Conta conta = contas.findById(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        return conta.isEstoqueHabilitado();
    }

    /**
     * Recusa o gesto que começa algo no balcão se a Conta está só leitura: começar venda, abrir
     * caixa, sangria, suprimento, receber fiado e cobrar pelo Pix integrado. Os outros gestos não
     * perguntam, e por isso a comanda começada antes do só leitura termina, e o caixa aberto fecha.
     *
     * <p>O gesto feito antes do início do só leitura passa, mesmo que chegue depois, porque a Conta
     * estava em dia quando ele aconteceu: é a venda lançada sem rede no último dia da tolerância. O
     * dia do gesto e o de hoje são os do balcão, porque o só leitura começa à meia-noite de lá. Quem
     * chama confere antes se o gesto já foi gravado, para o reenvio de um gesto aplicado não ser
     * recusado.
     *
     * @param feitoEm quando o gesto foi feito: agora, na rota, e o instante do aparelho, no lote
     * @throws ContaSomenteLeituraException se a Conta está só leitura e o gesto foi feito nele
     */
    @Transactional(readOnly = true)
    public void exigirOperacao(Instant feitoEm) {
        Objects.requireNonNull(feitoEm, "instante do gesto nao pode ser nulo");
        Conta conta = contaDoContexto();
        if (!conta.somenteLeitura(LocalDate.now(FusoDeReferencia.DO_BALCAO))) {
            return;
        }
        LocalDate inicio = conta.inicioDoSomenteLeitura();
        if (LocalDate.ofInstant(feitoEm, FusoDeReferencia.DO_BALCAO).isBefore(inicio)) {
            return;
        }
        throw new ContaSomenteLeituraException(inicio);
    }

    private Conta contaDoContexto() {
        ContaId contaId = TenantContext.exigirAtual();
        return contas.findById(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
    }

    /**
     * Se a conta em operação exige o NSU do comprovante da maquininha no pagamento em cartão. A
     * venda pergunta antes de gravar a parcela e o recebimento de fiado lançados com rede, e o
     * aplicador dos gestos sem rede pergunta para marcar a parcela que chegou sem ele.
     *
     * @throws br.com.caixasimples.shared.TenantNaoResolvidoException se não há conta no contexto
     */
    @Transactional(readOnly = true)
    public boolean nsuObrigatorio() {
        return contaDoContexto().isNsuObrigatorio();
    }

    /** A configuração é restrita ao administrador, embora as perguntas operacionais sejam públicas. */
    @Transactional(readOnly = true)
    public ConfiguracaoDaConta configuracao() {
        UsuarioContext.exigirAdmin();
        return ConfiguracaoDaConta.de(contaDoContexto(), movimentos.existem());
    }

    /**
     * Altera o controle da Conta autenticada (RF17). Depois do primeiro movimento, desligar faria
     * as vendas continuarem sem atualizar o saldo; a checagem consulta também produtos inativos.
     *
     * <p>Vale também no só leitura: o controle não põe nem tira dinheiro do balcão.
     */
    @Transactional
    public ConfiguracaoDaConta definirEstoqueHabilitado(boolean habilitado) {
        UsuarioContext.exigirAdmin();
        ContaId contaId = TenantContext.exigirAtual();
        Conta conta = contas.buscarParaAtualizar(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        boolean comMovimentos = movimentos.existem();
        if (!habilitado && conta.isEstoqueHabilitado() && comMovimentos) {
            throw new EstoqueComMovimentosException();
        }
        conta.definirEstoqueHabilitado(habilitado);
        return ConfiguracaoDaConta.de(conta, comMovimentos);
    }

    /**
     * Liga ou desliga a exigência do NSU no pagamento em cartão da Conta autenticada.
     *
     * <p>Não pergunta pelo plano: o campo e a exigência são da venda, que todo plano tem, e um NSU
     * anotado em qualquer plano aparece na conferência quando o plano a incluir. Ligar não alcança
     * o passado; o pagamento já gravado sem NSU continua válido. A linha fica travada como na
     * configuração do estoque, para duas mudanças simultâneas não se sobreporem.
     */
    @Transactional
    public ConfiguracaoDaConta definirNsuObrigatorio(boolean obrigatorio) {
        UsuarioContext.exigirAdmin();
        ContaId contaId = TenantContext.exigirAtual();
        Conta conta = contas.buscarParaAtualizar(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        conta.definirNsuObrigatorio(obrigatorio);
        return ConfiguracaoDaConta.de(conta, movimentos.existem());
    }

    /**
     * Corrige o nome do negócio da Conta autenticada (LGPD, art. 18, III): o nome pode identificar o
     * dono, como num MEI, e o ECRX Caixa é controlador desse cadastro.
     *
     * <p>A linha fica travada como nas outras chaves, porque a gravação escreve a linha inteira, com o
     * plano e o vencimento, e não pode desfazer um código de plano aplicado ao mesmo tempo.
     *
     * @throws IllegalArgumentException se o nome está em branco ou passa do tamanho da coluna
     */
    @Transactional
    public ConfiguracaoDaConta corrigirNomeDoNegocio(String nomeNegocio) {
        UsuarioContext.exigirAdmin();
        ContaId contaId = TenantContext.exigirAtual();
        Conta conta = contas.buscarParaAtualizar(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        conta.corrigirNomeDoNegocio(nomeNegocio);
        return ConfiguracaoDaConta.de(conta, movimentos.existem());
    }

    /**
     * O que o administrador configura na Conta, como a tela o mostra.
     *
     * @param estoqueComMovimentos se algum produto da Conta, ativo ou inativo, já tem movimento de
     *                             estoque: a tela deixa de oferecer o desligamento que seria recusado
     * @param tipoDaChavePix       nulo junto com a chave
     * @param chavePix             nula enquanto o administrador não cadastra uma
     * @param cidadePix            nula junto com a chave
     */
    public record ConfiguracaoDaConta(String nomeNegocio, boolean estoqueHabilitado,
            boolean estoqueComMovimentos, boolean nsuObrigatorio, ChavePix.Tipo tipoDaChavePix,
            String chavePix, String cidadePix) {

        /**
         * Também para a troca da chave Pix, que confere a senha antes de gravar. Toda mudança devolve
         * a configuração inteira, e a tela troca a que tinha pela resposta: por isso cada caminho
         * pergunta pelos movimentos, e não só a leitura.
         */
        static ConfiguracaoDaConta de(Conta conta, boolean estoqueComMovimentos) {
            ChavePix.Tipo tipo = conta.getChavePix() == null
                    ? null : ChavePix.tipoDaGravada(conta.getChavePix());
            return new ConfiguracaoDaConta(conta.getNomeNegocio(), conta.isEstoqueHabilitado(),
                    estoqueComMovimentos, conta.isNsuObrigatorio(), tipo, conta.getChavePix(),
                    conta.getCidadePix());
        }
    }

    /**
     * Aplica a oferta no primeiro login de ADMIN. O bloqueio da linha da Conta torna a marca
     * suficiente mesmo com dois logins simultâneos, e o ouvinte síncrono copia antes do commit.
     */
    @Transactional
    public void registrarPrimeiroAcessoSeNecessario() {
        UsuarioContext.exigirAdmin();
        ContaId contaId = TenantContext.exigirAtual();
        Conta conta = contas.buscarParaAtualizar(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
        if (conta.isCatalogoInicialAplicado()) {
            return;
        }
        conta.marcarCatalogoInicialAplicado();
        eventos.publishEvent(new PrimeiroAcessoDaConta(contaId.valor(), conta.getTipoNegocio()));
    }
}
