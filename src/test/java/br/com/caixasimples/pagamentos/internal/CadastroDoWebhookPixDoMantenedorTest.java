package br.com.caixasimples.pagamentos.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A execução avulsa que cadastra o webhook de uma Conta, sem subir o contexto: com o perfil dela, a
 * subida já rodaria o cadastro. Os adapters de teste só anotam em que Conta e com que domínio foram
 * chamados; o contrato HTTP de cada PSP está no teste do adapter dele.
 */
@ExtendWith(OutputCaptureExtension.class)
class CadastroDoWebhookPixDoMantenedorTest {
    private final List<String> chamadas = new ArrayList<>();

    @Test
    void contaDaEfiCadastraNoDominioDaEfiEDeixaSoAContaNoLog(CapturedOutput logs) {
        ContaId conta = ContaId.de(UUID.randomUUID());

        cadastro(ambiente(conta, "EFI"),
                new PropriedadesDoCadastroDoWebhook(" " + conta + " ", "pix.loja-da-esquina.com.br",
                        "pix-inter.loja-da-esquina.com.br")).run(null);

        assertThat(chamadas).containsExactly("efi " + conta + " pix.loja-da-esquina.com.br");
        assertThat(TenantContext.atual()).isEmpty();
        assertThat(logs.getAll()).contains("evento=webhook.cadastrado conta=" + conta);
    }

    @Test
    void contaDoInterCadastraNoDominioDoInter() {
        ContaId conta = ContaId.de(UUID.randomUUID());

        cadastro(ambiente(conta, "INTER"),
                new PropriedadesDoCadastroDoWebhook(conta.toString(), "pix.loja-da-esquina.com.br",
                        "pix-inter.loja-da-esquina.com.br")).run(null);

        assertThat(chamadas).containsExactly("inter " + conta + " pix-inter.loja-da-esquina.com.br");
    }

    @Test
    void contaSemPspOuComOsDoisERecusadaSemChamarNinguem() {
        ContaId conta = ContaId.de(UUID.randomUUID());
        var propriedades = new PropriedadesDoCadastroDoWebhook(conta.toString(),
                "pix.exemplo.com.br", "pix-inter.exemplo.com.br");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> cadastro(Map.of(), propriedades).run(null))
                .withMessageContaining("nem o Inter");
        Map<String, String> osDois = new HashMap<>(ambiente(conta, "EFI"));
        osDois.putAll(ambiente(conta, "INTER"));
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> cadastro(osDois, propriedades).run(null))
                .withMessageContaining("deixe só um");
        assertThat(chamadas).isEmpty();
    }

    @Test
    void recusaContaEDominioMalformadosAntesDeChamarOPsp() {
        ContaId id = ContaId.de(UUID.randomUUID());
        String conta = id.toString();
        Map<String, String> daEfi = ambiente(id, "EFI");
        Map<String, String> doInter = ambiente(id, "INTER");

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                cadastro(daEfi, new PropriedadesDoCadastroDoWebhook(null, "pix.exemplo.com.br",
                        null)).run(null));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                cadastro(daEfi, new PropriedadesDoCadastroDoWebhook("nao-e-um-id",
                        "pix.exemplo.com.br", null)).run(null));
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                cadastro(daEfi, new PropriedadesDoCadastroDoWebhook(conta, " ", null)).run(null));
        // O domínio que falta é o do PSP da Conta; o do outro PSP não é lido.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> cadastro(doInter, new PropriedadesDoCadastroDoWebhook(conta,
                        "pix.exemplo.com.br", null)).run(null))
                .withMessageContaining("dominio-inter");
        for (String dominio : new String[]{"https://pix.exemplo.com.br", "pix.exemplo.com.br/x",
                "pix.exemplo.com.br:443", "Pix.Exemplo.com.br", "localhost", "pix..exemplo.com.br",
                "pix.exemplo.com.br?hmac=1"}) {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    cadastro(daEfi, new PropriedadesDoCadastroDoWebhook(conta, dominio, null))
                            .run(null));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    cadastro(doInter, new PropriedadesDoCadastroDoWebhook(conta, null, dominio))
                            .run(null));
        }
        assertThat(chamadas).isEmpty();
    }

    private CadastroDoWebhookPixDoMantenedor cadastro(Map<String, String> ambiente,
            PropriedadesDoCadastroDoWebhook propriedades) {
        EfiPixGateway efi = new EfiPixGateway() {
            @Override
            void cadastrarWebhook(String dominio) {
                chamadas.add("efi " + TenantContext.exigirAtual() + " " + dominio);
            }
        };
        InterPixGateway inter = new InterPixGateway(Map.of(), Clock.systemUTC()) {
            @Override
            void cadastrarWebhook(String dominio) {
                chamadas.add("inter " + TenantContext.exigirAtual() + " " + dominio);
            }
        };
        return new CadastroDoWebhookPixDoMantenedor(propriedades,
                new PixGatewayDaConta(efi, inter, ambiente), efi, inter);
    }

    private static Map<String, String> ambiente(ContaId conta, String psp) {
        return Map.of("ECRX_CAIXA_" + psp + "_"
                + conta.valor().toString().replace("-", "").toUpperCase() + "_AMBIENTE",
                "HOMOLOGACAO");
    }
}
