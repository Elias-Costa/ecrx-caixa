package br.com.caixasimples.contas;

import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.Credencial;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioAutenticado;
import br.com.caixasimples.shared.UsuarioContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Monta conta, usuário e credencial num passo só, pelo mesmo caminho que a criação de conta usa.
 *
 * <p>Existe porque quase todo teste de integração precisa de uma conta com alguém que consiga
 * entrar, e montar isso à mão em cada teste esconderia o que o teste quer de fato provar.
 *
 * <p>O que ela devolve sabe executar código <strong>como aquela pessoa</strong>: conta no
 * contexto de tenant e usuário no contexto de identidade, os dois juntos, que é o que o filtro do
 * token faz numa requisição de verdade. Todo caso de uso restrito por perfil precisa disso.
 */
public class CriadorDeContaDeTeste {

    private final ContaRepository contas;
    private final UsuarioRepository usuarios;
    private final CredencialRepository credenciais;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;

    public CriadorDeContaDeTeste(ContaRepository contas, UsuarioRepository usuarios,
            CredencialRepository credenciais, PasswordEncoder encoder, JdbcTemplate jdbc) {
        this.contas = contas;
        this.usuarios = usuarios;
        this.credenciais = credenciais;
        this.encoder = encoder;
        this.jdbc = jdbc;
    }

    public ContaCriada criar(String nomeNegocio, String senha) {
        return criar(nomeNegocio, senha, Perfil.ADMIN, true);
    }

    /** Conta com tipo de negócio, para quem testa o que depende dele, como a oferta do catálogo. */
    public ContaCriada criarComTipo(String nomeNegocio, String tipoNegocio, String senha) {
        return criar(nomeNegocio, tipoNegocio, senha, Perfil.ADMIN, true);
    }

    public ContaCriada criar(String nomeNegocio, String senha, Perfil perfil, boolean ativo) {
        return criar(nomeNegocio, null, senha, perfil, ativo);
    }

    private ContaCriada criar(String nomeNegocio, String tipoNegocio, String senha, Perfil perfil,
            boolean ativo) {
        ContaId contaId = contas.save(new Conta(nomeNegocio, tipoNegocio, Instant.now())).contaId();
        String email = "teste-" + UUID.randomUUID() + "@exemplo.test";

        UUID usuarioId = TenantContext.executarComo(contaId, () -> {
            Usuario usuario = new Usuario("Pessoa de " + nomeNegocio, perfil);
            if (!ativo) {
                usuario.inativar();
            }
            return usuarios.save(usuario).getId();
        });

        // A pessoa da fixture já escolheu a própria senha: com a provisória, o servidor só responde à
        // identidade e à troca, e quase nenhum teste é sobre isso. Quem testa a senha provisória a
        // produz pela redefinição ou pela criação de usuário, os caminhos reais.
        Credencial credencial = new Credencial(email, encoder.encode(senha), usuarioId, contaId);
        credencial.trocarSenha(credencial.getSenhaHash());
        credenciais.save(credencial);
        return new ContaCriada(contaId, usuarioId, perfil, email, senha);
    }

    /**
     * Mais um usuário dentro de uma conta que já existe: o segundo atendente do mesmo negócio.
     *
     * <p>Nasce <strong>sem credencial</strong>, de propósito. Quem precisa dele é teste de regra
     * por operador, como a de um caixa aberto por vez, e não teste de login. Acrescentar uma
     * credencial que ninguém usa só tornaria a fixture mais lenta e menos legível.
     */
    public UsuarioCriado criarOperadorEm(ContaId contaId, String nome) {
        return criarEm(contaId, nome, Perfil.OPERADOR);
    }

    /** Um segundo administrador na mesma conta, para os testes de gestão de usuários. */
    public UsuarioCriado criarAdminEm(ContaId contaId, String nome) {
        return criarEm(contaId, nome, Perfil.ADMIN);
    }

    private UsuarioCriado criarEm(ContaId contaId, String nome, Perfil perfil) {
        UUID usuarioId = TenantContext.executarComo(contaId, () ->
                usuarios.save(new Usuario(nome, perfil)).getId());
        return new UsuarioCriado(contaId, usuarioId, perfil);
    }

    /**
     * Liga o controle de estoque da conta (RF17). Conta nasce com ele desligado, então todo teste
     * da baixa por venda precisa deste passo. A fixture prepara o estado diretamente para testes
     * de outros módulos; a tela de configuração usa o caso de uso autenticado.
     */
    public void habilitarEstoque(ContaId contaId) {
        Conta conta = contas.findById(contaId.valor()).orElseThrow();
        conta.definirEstoqueHabilitado(true);
        contas.save(conta);
    }

    /**
     * Paga o primeiro mês da conta, como se o código da renovação pedida no teste tivesse sido
     * aplicado hoje (RF31): a conta sai do teste e vence um mês depois do fim dele. Conta nasce no
     * teste, com tudo o que o plano inclui; quem chama é o teste que quer a conta já paga.
     */
    public void contratar(ContaId contaId) {
        Conta conta = contas.findById(contaId.valor()).orElseThrow();
        conta.renovar(conta.periodoDaRenovacao(LocalDate.now(FusoDeReferencia.DO_BALCAO)).fim());
        contas.save(conta);
    }

    /**
     * Põe o vencimento a tantos dias de hoje, no teste ou no período pago, como a conta estiver:
     * zero é o dia do vencimento, de zero a três é sempre a tolerância, e de seis em diante é sempre
     * o só leitura, porque a tolerância é de três dias úteis e o fim de semana a estica até seis
     * dias. Quatro e cinco dependem do dia da semana de hoje, e nenhum teste deve usá-los. Um
     * número negativo deixa o vencimento no futuro. Por SQL, porque a aplicação nunca recua um
     * vencimento.
     */
    public void vencerPlano(ContaId contaId, int diasDepoisDoVencimento) {
        if (diasDepoisDoVencimento == 4 || diasDepoisDoVencimento == 5) {
            throw new IllegalArgumentException("4 e 5 dias dependem do dia da semana de hoje");
        }
        LocalDate vencimento = LocalDate.now(FusoDeReferencia.DO_BALCAO)
                .minusDays(diasDepoisDoVencimento);
        int alteradas = jdbc.update("UPDATE conta SET proximo_vencimento = ?, dia_de_vencimento = ?"
                + " WHERE id = ?", vencimento, vencimento.getDayOfMonth(), contaId.valor());
        if (alteradas != 1) {
            throw new IllegalStateException("conta nao encontrada: " + contaId);
        }
    }

    /** Deixa a conta só leitura desde ontem ou antes: o vencimento fica sete dias atrás. */
    public void deixarSomenteLeitura(ContaId contaId) {
        vencerPlano(contaId, 7);
    }

    /**
     * Uma conta com a pessoa que a criou, que sabe executar código como ela.
     */
    public record ContaCriada(ContaId contaId, UUID usuarioId, Perfil perfil, String email,
            String senha) {

        /** Conta e usuário nos dois contextos, como numa requisição autenticada desta pessoa. */
        public <T> T comoUsuario(Supplier<T> acao) {
            return executarComo(contaId, usuarioId, perfil, acao);
        }

        public void comoUsuario(Runnable acao) {
            executarComo(contaId, usuarioId, perfil, acao);
        }
    }

    /**
     * Um usuário criado dentro de uma conta que já existia, que também sabe executar código como
     * ele.
     */
    public record UsuarioCriado(ContaId contaId, UUID usuarioId, Perfil perfil) {

        public <T> T comoUsuario(Supplier<T> acao) {
            return executarComo(contaId, usuarioId, perfil, acao);
        }

        public void comoUsuario(Runnable acao) {
            executarComo(contaId, usuarioId, perfil, acao);
        }
    }

    private static <T> T executarComo(ContaId contaId, UUID usuarioId, Perfil perfil,
            Supplier<T> acao) {
        return TenantContext.executarComo(contaId, () ->
                UsuarioContext.executarComo(new UsuarioAutenticado(usuarioId, perfil), acao));
    }

    private static void executarComo(ContaId contaId, UUID usuarioId, Perfil perfil,
            Runnable acao) {
        executarComo(contaId, usuarioId, perfil, () -> {
            acao.run();
            return null;
        });
    }
}
