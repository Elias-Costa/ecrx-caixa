package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.shared.ContaId;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A Conta e os domínios do {@link CadastroDoWebhookPixDoMantenedor}, vindos do ambiente de quem roda o
 * comando na máquina.
 *
 * <p>Cada PSP tem um domínio só do aviso dele, o mesmo que o proxy guarda com o certificado de cliente
 * do PSP, lido da mesma variável: o da Efí em dominio, o do Inter em dominio-inter. Só o nome da
 * máquina: a URL inteira, com o caminho e o segredo, é montada pela aplicação.
 */
@ConfigurationProperties(prefix = "ecrx-caixa.webhook")
record PropriedadesDoCadastroDoWebhook(String conta, String dominio, String dominioInter) {

    private static final Pattern NOME_DE_MAQUINA = Pattern.compile(
            "[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    /**
     * O id já conferido, antes de qualquer leitura.
     *
     * @throws IllegalStateException se a Conta não foi informada
     * @throws IllegalArgumentException se o valor não é um id
     */
    ContaId contaId() {
        if (conta == null || conta.isBlank()) {
            throw new IllegalStateException("Informe ecrx-caixa.webhook.conta");
        }
        try {
            return ContaId.de(conta.trim());
        } catch (IllegalArgumentException malformado) {
            throw new IllegalArgumentException("O valor informado não é o id de uma Conta");
        }
    }

    /**
     * O domínio do aviso da Efí, em minúsculas, sem esquema, porta nem caminho.
     *
     * @throws IllegalStateException se o domínio não foi informado
     * @throws IllegalArgumentException se o valor não é só um nome de máquina
     */
    String dominioConferido() {
        return conferido(dominio, "ecrx-caixa.webhook.dominio");
    }

    /**
     * O domínio do aviso do Inter, com a mesma conferência.
     *
     * @throws IllegalStateException se o domínio não foi informado
     * @throws IllegalArgumentException se o valor não é só um nome de máquina
     */
    String dominioInterConferido() {
        return conferido(dominioInter, "ecrx-caixa.webhook.dominio-inter");
    }

    private static String conferido(String valor, String propriedade) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Informe " + propriedade);
        }
        String nome = valor.trim();
        if (!NOME_DE_MAQUINA.matcher(nome).matches()) {
            throw new IllegalArgumentException("O domínio do webhook é só o nome da máquina, em "
                    + "minúsculas, sem https://, porta nem caminho");
        }
        return nome;
    }
}
