package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.Credencial;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.Usuario;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Para quem vai o resumo do caixa fechado, e com que nome de negócio: a pergunta que o ouvinte do
 * resumo, em relatórios, faz a este módulo, dono da Conta, do plano e dos endereços de e-mail.
 *
 * <p><strong>Não confere perfil, de propósito.</strong> Quem pergunta é um ouvinte de evento, que
 * roda sem usuário autenticado, na Conta que o evento carrega. A resposta só sai deste módulo para
 * o envio do resumo, e nenhuma rota a devolve.
 *
 * <p><strong>Recebem todos os ADMIN ativos, e só eles.</strong> O resumo traz faturamento, diferença
 * de caixa e mais vendidos, que o operador não vê por perfil; enviar a ele contornaria por e-mail a
 * regra que a tela aplica. O ADMIN inativo, inclusive o bloqueado à espera da conclusão da
 * inativação, também não recebe.
 *
 * <p><strong>A Conta suspensa pelo mantenedor não recebe nada</strong>, porque ninguém dela entra
 * para ver o que o e-mail traria. A Conta só leitura recebe: o caixa fechado é histórico, e os
 * relatórios continuam abertos a ela na tela.
 */
@Service
public class DestinatariosDoResumo {

    private final ContaRepository contas;
    private final UsuarioRepository usuarios;
    private final CredencialRepository credenciais;

    DestinatariosDoResumo(ContaRepository contas, UsuarioRepository usuarios,
            CredencialRepository credenciais) {
        this.contas = contas;
        this.usuarios = usuarios;
        this.credenciais = credenciais;
    }

    /**
     * Os destinatários da Conta do contexto, ou vazio quando ela não recebe o resumo hoje. A Conta
     * que não existe mais, encerrada entre o fechamento e o envio, também dá vazio.
     *
     * @throws br.com.caixasimples.shared.TenantNaoResolvidoException se não há conta no contexto
     */
    @Transactional(readOnly = true)
    public Optional<Destino> daContaAtual() {
        ContaId contaId = TenantContext.exigirAtual();
        // O id vem do contexto, posto pelo ouvinte a partir do evento; nunca de um pedido.
        Optional<Conta> encontrada = contas.findById(contaId.valor());
        if (encontrada.isEmpty()) {
            return Optional.empty();
        }
        Conta conta = encontrada.get();
        if (conta.isSuspensa()) {
            return Optional.empty();
        }

        // A credencial não tem filtro de Conta; a busca parte de um Usuario já filtrado por ela.
        List<String> emails = usuarios.findByAtivoTrue().stream()
                .filter(usuario -> usuario.getPerfil() == Perfil.ADMIN)
                .sorted(Comparator.comparing(Usuario::getCriadoEm))
                .map(usuario -> credenciais.findByUsuarioId(usuario.getId()))
                .flatMap(Optional::stream)
                .map(Credencial::getEmail)
                .toList();
        if (emails.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Destino(conta.getNomeNegocio(), emails));
    }

    /**
     * @param nomeNegocio o nome que a Conta deu ao negócio, para o assunto do e-mail
     * @param emails      os endereços dos ADMIN ativos, do mais antigo para o mais novo, nunca vazio
     */
    public record Destino(String nomeNegocio, List<String> emails) {

        public Destino {
            Objects.requireNonNull(nomeNegocio, "nomeNegocio nao pode ser nulo");
            emails = List.copyOf(emails);
        }
    }
}
