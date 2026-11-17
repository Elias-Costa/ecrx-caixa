package br.com.caixasimples.pagamentos.domain;

import br.com.caixasimples.shared.ContaId;
import java.util.Objects;

/**
 * O resultado da autenticação de um aviso Pix: a Conta dele, ou o motivo da recusa numa palavra fixa,
 * que vai ao evento de segurança. O motivo nunca leva o identificador nem o segredo da URL.
 */
public record AutenticacaoDoAviso(ContaId conta, String recusa) {

    public AutenticacaoDoAviso {
        if ((conta == null) == (recusa == null)) {
            throw new IllegalArgumentException("o aviso tem uma Conta ou um motivo de recusa");
        }
    }

    public static AutenticacaoDoAviso daConta(ContaId conta) {
        return new AutenticacaoDoAviso(Objects.requireNonNull(conta), null);
    }

    public static AutenticacaoDoAviso recusada(String motivo) {
        return new AutenticacaoDoAviso(null, Objects.requireNonNull(motivo));
    }

    public boolean aceita() {
        return conta != null;
    }
}
