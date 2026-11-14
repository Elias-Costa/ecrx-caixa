package br.com.caixasimples.pagamentos.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A execução avulsa que cadastra o webhook de uma Conta, sem subir o contexto: com o perfil dela, a
 * subida já rodaria o cadastro. O adapter de teste só anota em que Conta e com que domínio foi
 * chamado; o contrato HTTP com a Efí está em {@link EfiPixGatewayTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class CadastroDoWebhookPixDoMantenedorTest {

    @Test
    void cadastraNaContaInformadaEDeixaSoAContaNoLog(CapturedOutput logs) {
        ContaId conta = ContaId.de(UUID.randomUUID());
        EfiDeTeste efi = new EfiDeTeste();

        new CadastroDoWebhookPixDoMantenedor(
                new PropriedadesDoCadastroDoWebhook(" " + conta + " ", "pix.loja-da-esquina.com.br"),
                efi).run(null);

        assertThat(efi.chamadas).containsExactly(conta + " pix.loja-da-esquina.com.br");
        assertThat(TenantContext.atual()).isEmpty();
        assertThat(logs.getAll()).contains("evento=webhook.cadastrado conta=" + conta);
    }

    @Test
    void recusaContaEDominioMalformadosAntesDeChamarAEfi() {
        EfiDeTeste efi = new EfiDeTeste();
        String conta = UUID.randomUUID().toString();

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                new CadastroDoWebhookPixDoMantenedor(
                        new PropriedadesDoCadastroDoWebhook(null, "pix.exemplo.com.br"), efi)
                        .run(null));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                new CadastroDoWebhookPixDoMantenedor(
                        new PropriedadesDoCadastroDoWebhook("nao-e-um-id", "pix.exemplo.com.br"),
                        efi).run(null));
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                new CadastroDoWebhookPixDoMantenedor(
                        new PropriedadesDoCadastroDoWebhook(conta, " "), efi).run(null));
        for (String dominio : new String[]{"https://pix.exemplo.com.br", "pix.exemplo.com.br/x",
                "pix.exemplo.com.br:443", "Pix.Exemplo.com.br", "localhost", "pix..exemplo.com.br",
                "pix.exemplo.com.br?hmac=1"}) {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    new CadastroDoWebhookPixDoMantenedor(
                            new PropriedadesDoCadastroDoWebhook(conta, dominio), efi).run(null));
        }
        assertThat(efi.chamadas).isEmpty();
    }

    private static final class EfiDeTeste extends EfiPixGateway {
        final List<String> chamadas = new ArrayList<>();

        @Override
        void cadastrarWebhook(String dominio) {
            chamadas.add(TenantContext.exigirAtual() + " " + dominio);
        }
    }
}
