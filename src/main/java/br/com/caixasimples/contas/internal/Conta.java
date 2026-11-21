package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.contas.SituacaoDoPlano;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.LimiteDeTexto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * O negócio contratante, isto é, o tenant. Agregado de uma entidade só.
 *
 * <p><strong>Não tem {@code @TenantId}, e isso é correto:</strong> o {@code id} desta entidade
 * <em>é</em> o tenant que as outras tabelas carregam em {@code conta_id}, então não há o que
 * filtrar aqui. Em troca, {@link ContaRepository} nunca é consultado com um id vindo de fora da
 * aplicação, e sim sempre a partir do contexto de tenant.
 *
 * <p>O plano é um só, e a Conta nasce num teste dele: o fim do teste é o primeiro vencimento, e o
 * dia dele, o dia do vencimento de todos os meses. Depois do vencimento sem pagamento vem a
 * tolerância e, depois dela, o só leitura. O ciclo só muda por aqui, pela aplicação de um pedido de
 * plano, e as datas seguem {@link CicloDeVencimento}.
 */
@Entity
@Table(name = "conta")
public class Conta {

    /** O tamanho da coluna do nome do negócio: o texto longo é recusado aqui, e não pelo banco. */
    private static final int TAMANHO_DO_NOME_DO_NEGOCIO = 120;

    /** O tamanho da coluna da cidade do recebedor do Pix, que cabe o maior nome de município. */
    private static final int TAMANHO_DA_CIDADE_PIX = 60;

    @Id
    private UUID id;

    @Column(name = "nome_negocio", nullable = false)
    private String nomeNegocio;

    @Column(name = "tipo_negocio")
    private String tipoNegocio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plano plano;

    /** O dia do mês em que o plano vence: o do fim do teste, ou o do pagamento feito no só leitura. */
    @Column(name = "dia_de_vencimento", nullable = false)
    private int diaDeVencimento;

    /** O fim, exclusive, do teste ou do último período pago. */
    @Column(name = "proximo_vencimento", nullable = false)
    private LocalDate proximoVencimento;

    /** Se nenhum período foi pago ainda: o vencimento é o fim do teste. */
    @Column(name = "em_teste", nullable = false)
    private boolean emTeste;

    @Column(name = "estoque_habilitado", nullable = false)
    private boolean estoqueHabilitado;

    @Column(name = "nsu_obrigatorio", nullable = false)
    private boolean nsuObrigatorio;

    @Column(name = "catalogo_inicial_aplicado", nullable = false)
    private boolean catalogoInicialAplicado;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    /**
     * O instante da suspensão imediata do acesso, por uso ilegal ou ataque ao serviço; nulo quando o
     * acesso vale. Não tem relação com o plano: a mensalidade atrasada deixa a Conta só leitura,
     * pelo vencimento, e todos continuam entrando.
     */
    @Column(name = "suspensa_em")
    private Instant suspensaEm;

    /**
     * A chave Pix do Pix lançado à mão, na forma conferida por {@link ChavePix}; nula enquanto o
     * administrador não cadastra uma. O aparelho a põe no QR estático, com a cidade e o nome do
     * negócio, e o banco recusa a chave sem a cidade e a cidade sem a chave.
     */
    @Column(name = "chave_pix")
    private String chavePix;

    /** A cidade do recebedor no QR estático do Pix; nula junto com a chave. */
    @Column(name = "cidade_pix")
    private String cidadePix;

    protected Conta() {
        // exigido pelo JPA
    }

    /**
     * A Conta nova, no teste, que vence no fim dele, contado do dia da criação no balcão.
     *
     * @param tipoNegocio opcional; casa com {@code ModeloProduto.tipo_negocio} para sugerir o
     *                    catálogo inicial (RF32), e nulo significa cadastro começando em branco
     * @param criadoEm    o instante da criação, que dá o dia em que o teste começa
     */
    public Conta(String nomeNegocio, String tipoNegocio, Instant criadoEm) {
        this.id = UUID.randomUUID();
        this.nomeNegocio = nomeDoNegocioConferido(nomeNegocio);
        this.tipoNegocio = tipoNegocio;
        this.plano = Plano.COMPLETO;
        this.estoqueHabilitado = false;
        this.nsuObrigatorio = false;
        this.catalogoInicialAplicado = false;
        this.criadoEm = Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");
        LocalDate diaDaCriacao = LocalDate.ofInstant(criadoEm, FusoDeReferencia.DO_BALCAO);
        this.proximoVencimento = CicloDeVencimento.fimDoTeste(diaDaCriacao);
        this.diaDeVencimento = proximoVencimento.getDayOfMonth();
        this.emTeste = true;
    }

    private static String nomeDoNegocioConferido(String nome) {
        return LimiteDeTexto.exigirAte(exigirTexto(nome, "nome do negócio"), TAMANHO_DO_NOME_DO_NEGOCIO,
                "nome do negócio");
    }

    private static String exigirTexto(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("Informe o " + campo + ".");
        }
        return valor.trim();
    }

    public ContaId contaId() {
        return ContaId.de(id);
    }

    public UUID getId() {
        return id;
    }

    public String getNomeNegocio() {
        return nomeNegocio;
    }

    /**
     * Corrige o nome do negócio. Nada guarda cópia dele no servidor: o aplicativo o lê da identidade
     * de quem entrou, no cabeçalho e no comprovante, e cada aparelho mostra o nome novo na próxima
     * leitura dela.
     */
    public void corrigirNomeDoNegocio(String novo) {
        this.nomeNegocio = nomeDoNegocioConferido(novo);
    }

    public String getTipoNegocio() {
        return tipoNegocio;
    }

    public Plano getPlano() {
        return plano;
    }

    /** Negócio baseado em serviço opera com o módulo de estoque desligado (RF17). */
    public boolean isEstoqueHabilitado() {
        return estoqueHabilitado;
    }

    /**
     * Se o pagamento em cartão lançado com rede exige o NSU do comprovante da maquininha. Nasce
     * desligada, para ninguém receber um campo obrigatório sem pedir.
     */
    public boolean isNsuObrigatorio() {
        return nsuObrigatorio;
    }

    public boolean isCatalogoInicialAplicado() {
        return catalogoInicialAplicado;
    }

    public void marcarCatalogoInicialAplicado() {
        if (catalogoInicialAplicado) {
            throw new IllegalStateException("catalogo inicial desta conta ja foi aplicado");
        }
        this.catalogoInicialAplicado = true;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    /** Se o acesso de todos os usuários da Conta está suspenso. */
    public boolean isSuspensa() {
        return suspensaEm != null;
    }

    /** Nulo quando o acesso vale. */
    public Instant getSuspensaEm() {
        return suspensaEm;
    }

    /**
     * Suspende o acesso de todos os usuários da Conta. Quem suspende derruba também os tokens já
     * emitidos, subindo a versão de acesso de cada Usuário na mesma transação.
     *
     * @throws IllegalStateException se a Conta já está suspensa: suspender de novo trocaria o instante
     *         em que a suspensão começou, que o aviso ao administrador informa
     */
    public void suspender(Instant agora) {
        Objects.requireNonNull(agora, "instante nao pode ser nulo");
        if (suspensaEm != null) {
            throw new IllegalStateException("a Conta já está suspensa desde " + suspensaEm);
        }
        this.suspensaEm = agora;
    }

    /**
     * Devolve o acesso à Conta. Os tokens de antes da suspensão continuam recusados, porque a suspensão
     * subiu a versão de acesso de cada Usuário: todos entram de novo.
     *
     * @throws IllegalStateException se a Conta não está suspensa
     */
    public void reativar() {
        if (suspensaEm == null) {
            throw new IllegalStateException("a Conta não está suspensa");
        }
        this.suspensaEm = null;
    }

    public int getDiaDeVencimento() {
        return diaDeVencimento;
    }

    /** O fim, exclusive, do teste ou do último período pago. */
    public LocalDate getProximoVencimento() {
        return proximoVencimento;
    }

    /** Se nenhum período foi pago ainda. */
    public boolean isEmTeste() {
        return emTeste;
    }

    /** Onde a Conta está no teste ou no ciclo, no dia de hoje do balcão. */
    public SituacaoDoPlano situacao(LocalDate hoje) {
        return CicloDeVencimento.situacao(proximoVencimento, emTeste, hoje);
    }

    /**
     * Se a Conta está só leitura hoje: passou a tolerância depois do vencimento, e o pagamento não
     * chegou. Não começa venda nem abre caixa, mas fecha o aberto e consulta tudo.
     */
    public boolean somenteLeitura(LocalDate hoje) {
        return situacao(hoje) == SituacaoDoPlano.SOMENTE_LEITURA;
    }

    /**
     * Os recursos que valem hoje, para o aplicativo decidir o que mostrar. No só leitura sai o Pix
     * integrado, porque a cobrança nova é recusada.
     */
    public Set<RecursoDoPlano> recursos(LocalDate hoje) {
        Set<RecursoDoPlano> ativos = EnumSet.allOf(RecursoDoPlano.class);
        if (somenteLeitura(hoje)) {
            ativos.remove(RecursoDoPlano.PIX_INTEGRADO);
        }
        return ativos;
    }

    /** O primeiro dia em só leitura, se o pagamento não chegar até lá. */
    public LocalDate inicioDoSomenteLeitura() {
        return CicloDeVencimento.inicioDoSomenteLeitura(proximoVencimento);
    }

    /**
     * O período que uma renovação pedida hoje paga: o que começa no vencimento ainda não pago, no
     * teste também.
     *
     * @throws IllegalStateException se a Conta está só leitura, em que o pagamento recomeça o ciclo
     *         em vez de renovar
     */
    public CicloDeVencimento.Periodo periodoDaRenovacao(LocalDate hoje) {
        if (somenteLeitura(hoje)) {
            throw new IllegalStateException("conta em so leitura nao renova; o ciclo recomeca");
        }
        return CicloDeVencimento.periodoDaRenovacao(proximoVencimento, diaDeVencimento);
    }

    /**
     * Tira a Conta do só leitura com o pagamento: o ciclo recomeça hoje, e o dia de hoje vira o dia
     * do vencimento de todos os meses. Ninguém paga pelos dias em que não pôde vender.
     *
     * @return o primeiro período do ciclo novo
     * @throws IllegalStateException se a Conta não está só leitura, quando o pagamento é renovação
     */
    public CicloDeVencimento.Periodo retomar(LocalDate hoje) {
        Objects.requireNonNull(hoje, "hoje nao pode ser nulo");
        if (!somenteLeitura(hoje)) {
            throw new IllegalStateException("conta fora do so leitura renova; nao recomeca o ciclo");
        }
        this.diaDeVencimento = hoje.getDayOfMonth();
        this.proximoVencimento = CicloDeVencimento.seguinte(hoje, diaDeVencimento);
        this.emTeste = false;
        return new CicloDeVencimento.Periodo(hoje, proximoVencimento);
    }

    /**
     * Registra o período pago pela renovação e encerra o teste. O dia do vencimento não muda, nem com
     * o pagamento feito na tolerância: o período pago é o que venceu, e não um mês a partir dele.
     *
     * @param fim o fim, exclusive, do período pago, que passa a ser o próximo vencimento
     * @throws IllegalStateException se o período já estava pago
     */
    public void renovar(LocalDate fim) {
        Objects.requireNonNull(fim, "fim nao pode ser nulo");
        if (!fim.isAfter(proximoVencimento)) {
            throw new IllegalStateException("O período desta renovação já está pago.");
        }
        this.proximoVencimento = fim;
        this.emTeste = false;
    }

    /** Liga ou desliga o controle de estoque desta conta (RF17). */
    public void definirEstoqueHabilitado(boolean habilitado) {
        this.estoqueHabilitado = habilitado;
    }

    /** Nula enquanto o administrador não cadastra a chave. */
    public String getChavePix() {
        return chavePix;
    }

    /** Nula junto com a chave. */
    public String getCidadePix() {
        return cidadePix;
    }

    /**
     * Grava a chave Pix e a cidade do recebedor juntas, porque o QR não se monta com uma só. O nome do
     * recebedor no QR é o do negócio, que já está na Conta.
     *
     * @throws IllegalArgumentException se a cidade está em branco ou passa do tamanho da coluna
     */
    public void definirChavePix(ChavePix chave, String cidade) {
        Objects.requireNonNull(chave, "chave Pix nao pode ser nula");
        this.cidadePix = cidadeDoPixConferida(cidade);
        this.chavePix = chave.valor();
    }

    /**
     * A cidade do recebedor como fica gravada, sem os espaços das pontas. Pública para quem troca a
     * chave conferir a cidade antes da senha, como confere a chave.
     *
     * @throws IllegalArgumentException se a cidade está em branco ou passa do tamanho da coluna
     */
    public static String cidadeDoPixConferida(String cidade) {
        if (cidade == null || cidade.isBlank()) {
            throw new IllegalArgumentException("Chave Pix: informe a cidade do recebedor.");
        }
        return LimiteDeTexto.exigirAte(cidade.trim(), TAMANHO_DA_CIDADE_PIX, "cidade do Pix");
    }

    /** Tira a chave e a cidade juntas: o Pix manual continua, lançado sem QR. */
    public void removerChavePix() {
        this.chavePix = null;
        this.cidadePix = null;
    }

    /** Liga ou desliga a exigência do NSU no pagamento em cartão. */
    public void definirNsuObrigatorio(boolean obrigatorio) {
        this.nsuObrigatorio = obrigatorio;
    }
}
