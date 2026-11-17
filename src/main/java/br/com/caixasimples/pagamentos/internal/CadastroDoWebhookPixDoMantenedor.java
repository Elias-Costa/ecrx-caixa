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
 * Cadastra no PSP da Conta o webhook Pix dela, no domínio só do aviso desse PSP, que o proxy da
 * máquina guarda com o certificado de cliente do PSP.
 *
 * <p>Roda apenas sob o perfil {@code cadastrar-webhook-pix}, numa execução avulsa da imagem, como a
 * suspensão de uma Conta. O cadastro sai da aplicação, e não de um comando à mão, para a chave privada
 * do certificado da Conta e o segredo da URL não passarem pelo disco nem pelo histórico do shell: os
 * dois vêm do mesmo ambiente da cobrança, e o comando recebe só o id da Conta e os domínios.
 *
 * <p>O PSP é o que a cobrança da Conta usa. A Efí confere a URL antes de aceitar: manda primeiro uma
 * requisição sem certificado, que o proxy recusa, e depois uma com ele e uma notificação de teste, que
 * a rota do aviso responde com sucesso. O Inter não chama a URL no cadastro. Nos dois, o cadastro
 * substitui o webhook da chave Pix, então rodar de novo só repete o mesmo.
 */
@Component
@Profile("cadastrar-webhook-pix")
@EnableConfigurationProperties(PropriedadesDoCadastroDoWebhook.class)
class CadastroDoWebhookPixDoMantenedor implements ApplicationRunner {

    private final PropriedadesDoCadastroDoWebhook propriedades;
    private final PixGatewayDaConta selecao;
    private final EfiPixGateway efi;
    private final InterPixGateway inter;

    CadastroDoWebhookPixDoMantenedor(PropriedadesDoCadastroDoWebhook propriedades,
            PixGatewayDaConta selecao, EfiPixGateway efi, InterPixGateway inter) {
        this.propriedades = propriedades;
        this.selecao = selecao;
        this.efi = efi;
        this.inter = inter;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        // A Conta é conferida antes de qualquer leitura da configuração dela.
        ContaId contaId = propriedades.contaId();

        // A configuração do PSP é lida pela Conta do contexto, como na cobrança, e o domínio do PSP
        // escolhido é conferido antes de abrir o certificado ou chamar o PSP.
        TenantContext.executarComo(contaId, () -> {
            switch (selecao.pspDaConta()) {
                case EFI -> efi.cadastrarWebhook(propriedades.dominioConferido());
                case INTER -> inter.cadastrarWebhook(propriedades.dominioInterConferido());
                case NENHUM -> throw new IllegalStateException(
                        "A Conta não tem a Efí nem o Inter configurados.");
                case OS_DOIS -> throw new IllegalStateException(
                        "A Conta tem a Efí e o Inter configurados; deixe só um.");
            }
        });

        // Sem a URL, que leva o segredo.
        EventoDeSeguranca.de("webhook.cadastrado")
                .com("conta", contaId)
                .registrar();
    }
}
