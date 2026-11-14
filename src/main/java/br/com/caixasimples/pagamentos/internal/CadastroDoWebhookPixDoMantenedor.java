package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.EventoDeSeguranca;
import br.com.caixasimples.shared.TenantContext;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Cadastra na Efí o webhook Pix de uma Conta, no domínio só do aviso, que o proxy da máquina guarda
 * com o certificado de cliente da Efí.
 *
 * <p>Roda apenas sob o perfil {@code cadastrar-webhook-pix}, numa execução avulsa da imagem, como a
 * suspensão de uma Conta. O cadastro sai da aplicação, e não de um comando à mão, para a chave privada
 * do certificado da Conta e o segredo da URL não passarem pelo disco nem pelo histórico do shell: os
 * dois vêm do mesmo ambiente da cobrança, e o comando recebe só o id da Conta e o domínio.
 *
 * <p>A Efí confere a URL antes de aceitar: manda primeiro uma requisição sem certificado, que o proxy
 * recusa, e depois uma com ele e uma notificação de teste, que a rota do aviso responde com sucesso. O
 * cadastro substitui o webhook da chave Pix, então rodar de novo só repete o mesmo.
 */
@Component
@Profile("cadastrar-webhook-pix")
@EnableConfigurationProperties(PropriedadesDoCadastroDoWebhook.class)
class CadastroDoWebhookPixDoMantenedor implements ApplicationRunner {

    private final PropriedadesDoCadastroDoWebhook propriedades;
    private final EfiPixGateway efi;

    CadastroDoWebhookPixDoMantenedor(PropriedadesDoCadastroDoWebhook propriedades,
            EfiPixGateway efi) {
        this.propriedades = propriedades;
        this.efi = efi;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        // Os dois conferidos antes de abrir o certificado ou chamar a Efí.
        ContaId contaId = propriedades.contaId();
        String dominio = propriedades.dominioConferido();

        // A configuração da Efí é lida pela Conta do contexto, como na cobrança.
        TenantContext.executarComo(contaId, () -> efi.cadastrarWebhook(dominio));

        // Sem a URL, que leva o segredo.
        EventoDeSeguranca.de("webhook.cadastrado")
                .com("conta", contaId)
                .registrar();
    }
}
