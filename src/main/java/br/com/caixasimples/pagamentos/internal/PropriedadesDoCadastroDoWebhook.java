package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.shared.ContaId;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A Conta e o domínio do {@link CadastroDoWebhookPixDoMantenedor}, vindos do ambiente de quem roda o
 * comando na máquina.
 *
 * <p>O domínio é o mesmo que o proxy guarda com o certificado de cliente da Efí, lido da mesma
 * variável. Só o nome da máquina: a URL inteira, com o caminho e o segredo, é montada pela aplicação.
 */
@ConfigurationProperties(prefix = "ecrx-caixa.webhook")
record PropriedadesDoCadastroDoWebhook(String conta, String dominio) {

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
     * O nome da máquina, em minúsculas, sem esquema, porta nem caminho.
     *
     * @throws IllegalStateException se o domínio não foi informado
     * @throws IllegalArgumentException se o valor não é só um nome de máquina
     */
    String dominioConferido() {
        if (dominio == null || dominio.isBlank()) {
            throw new IllegalStateException("Informe ecrx-caixa.webhook.dominio");
        }
        String nome = dominio.trim();
        if (!NOME_DE_MAQUINA.matcher(nome).matches()) {
            throw new IllegalArgumentException("O domínio do webhook é só o nome da máquina, em "
                    + "minúsculas, sem https://, porta nem caminho");
        }
        return nome;
    }
}
