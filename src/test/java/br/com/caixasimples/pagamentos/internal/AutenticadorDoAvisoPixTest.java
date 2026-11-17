package br.com.caixasimples.pagamentos.internal;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.pagamentos.domain.AutenticacaoDoAviso;
import br.com.caixasimples.shared.ContaId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * O par da URL acha a Conta e o PSP dela, e o certificado conferido pelo proxy tem de ser o desse PSP.
 * Os sujeitos são os medidos no Inter: o do aviso e o de um certificado de cliente comum da API, que a
 * mesma autoridade emite com o nome da integração.
 */
class AutenticadorDoAvisoPixTest {
    private static final String DO_AVISO = "CN=Webhook,OU=webhook,L=Minas Gerais,C=BR";
    private static final String DE_CLIENTE_DA_API =
            "CN=ECRX Caixa medição Pix,OU=6afc3ee83d65da11177a1942,L=Minas Gerais,C=BR";
    private static final String DIGITAL_DA_EFI =
            "8c0f3a1e5b2d4c6f8a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f";

    private final UUID efi = UUID.randomUUID();
    private final UUID inter = UUID.randomUUID();
    private final AutenticadorDoAvisoPix autenticador = new AutenticadorDoAvisoPix(ambiente(
            par("EFI", efi, "id-efi", "segredo-efi"),
            par("INTER", inter, "id-inter", "segredo-inter")));

    @Test
    void contaDaEfiExigeOCertificadoDaEfi() {
        assertThat(autenticador.autenticar("id-efi", "segredo-efi", efiConferida()))
                .isEqualTo(AutenticacaoDoAviso.daConta(ContaId.de(efi)));
        assertThat(autenticador.autenticar("id-efi", "segredo-efi", interConferido(DO_AVISO)))
                .isEqualTo(AutenticacaoDoAviso.recusada("certificado"));
        Map<String, String> osDois = new HashMap<>(efiConferida());
        osDois.putAll(interConferido(DO_AVISO));
        assertThat(autenticador.autenticar("id-efi", "segredo-efi", osDois))
                .isEqualTo(AutenticacaoDoAviso.recusada("certificado"));
    }

    @Test
    void contaDoInterExigeOCertificadoDoAvisoDoInter() {
        assertThat(autenticador.autenticar("id-inter", "segredo-inter", interConferido(DO_AVISO)))
                .isEqualTo(AutenticacaoDoAviso.daConta(ContaId.de(inter)));
        // A mesma autoridade emite o certificado de qualquer cliente da API do Inter.
        assertThat(autenticador.autenticar("id-inter", "segredo-inter",
                interConferido(DE_CLIENTE_DA_API)))
                .isEqualTo(AutenticacaoDoAviso.recusada("certificado"));
        assertThat(autenticador.autenticar("id-inter", "segredo-inter", efiConferida()))
                .isEqualTo(AutenticacaoDoAviso.recusada("certificado"));
    }

    @Test
    void sujeitoDoAvisoDoInterTemUmSoCnEUmSoOuEsperados() {
        assertThat(AutenticadorDoAvisoPix.sujeitoDoAvisoDoInter(DO_AVISO)).isTrue();
        for (String outro : new String[]{
                DE_CLIENTE_DA_API,
                "CN=Webhook,L=Minas Gerais,C=BR",
                "CN=Webhook,OU=6afc3ee83d65da11177a1942,L=Minas Gerais,C=BR",
                "CN=Webhook,OU=webhook,OU=outra,L=Minas Gerais,C=BR",
                "CN=Webhook,CN=Outro,OU=webhook,C=BR",
                "CN=Webhook+OU=webhook,C=BR",
                "CN=Webhook\\,OU=webhook,C=BR",
                "CN=webhook,OU=webhook,C=BR",
                ""}) {
            assertThat(AutenticadorDoAvisoPix.sujeitoDoAvisoDoInter(outro)).as(outro).isFalse();
        }
    }

    @Test
    void segredoErradoOuParDeOutraContaERecusadoPeloHmac() {
        assertThat(autenticador.autenticar("id-inter", "segredo-efi", interConferido(DO_AVISO)))
                .isEqualTo(AutenticacaoDoAviso.recusada("hmac"));
        assertThat(autenticador.autenticar("id-inexistente", "segredo-inter",
                interConferido(DO_AVISO))).isEqualTo(AutenticacaoDoAviso.recusada("hmac"));
        assertThat(autenticador.autenticar("", "segredo-inter", interConferido(DO_AVISO)))
                .isEqualTo(AutenticacaoDoAviso.recusada("hmac"));
        assertThat(autenticador.autenticar("id-inter", null, interConferido(DO_AVISO)))
                .isEqualTo(AutenticacaoDoAviso.recusada("hmac"));
    }

    /** O mesmo par em duas Contas não decide por nenhuma. */
    @Test
    void parRepetidoEmDuasContasERecusado() {
        UUID outra = UUID.randomUUID();
        var repetido = new AutenticadorDoAvisoPix(ambiente(
                par("INTER", inter, "id-igual", "segredo-igual"),
                par("EFI", outra, "id-igual", "segredo-igual")));
        assertThat(repetido.autenticar("id-igual", "segredo-igual", interConferido(DO_AVISO)))
                .isEqualTo(AutenticacaoDoAviso.recusada("hmac"));
    }

    private static Map<String, String> efiConferida() {
        return Map.of(AutenticadorDoAvisoPix.CERTIFICADO_DA_EFI, DIGITAL_DA_EFI);
    }

    private static Map<String, String> interConferido(String sujeito) {
        return Map.of(AutenticadorDoAvisoPix.CERTIFICADO_DO_INTER, sujeito);
    }

    @SafeVarargs
    private static Map<String, String> ambiente(Map<String, String>... pares) {
        Map<String, String> todos = new HashMap<>();
        for (Map<String, String> par : pares) {
            todos.putAll(par);
        }
        return todos;
    }

    private static Map<String, String> par(String psp, UUID conta, String id, String segredo) {
        String prefixo = "ECRX_CAIXA_" + psp + "_" + conta.toString().replace("-", "").toUpperCase()
                + "_WEBHOOK_";
        return Map.of(prefixo + "ID", id, prefixo + "SECRET", segredo);
    }
}
