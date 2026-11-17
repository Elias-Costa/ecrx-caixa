package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.domain.AutenticacaoDoAviso;
import br.com.caixasimples.pagamentos.domain.WebhookPixAutenticador;
import br.com.caixasimples.shared.ContaId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Autentica o aviso Pix de qualquer PSP integrado: o par identificador e segredo da URL acha a Conta e
 * o PSP dela, e o certificado conferido pelo proxy tem de ser o desse PSP. O par fica no ambiente, por
 * Conta, fora do banco e do Git.
 *
 * <p>Cada PSP chega por um domínio só dele, em que o proxy confere o certificado de cliente na
 * autoridade do PSP e escreve um cabeçalho próprio; os outros domínios apagam esse cabeçalho. A Efí
 * tem autoridades só do webhook, e basta o cabeçalho dela. A autoridade do Inter também emite o
 * certificado de cada cliente da API dele, com o nome que o cliente dá à integração: por isso, para o
 * Inter, o sujeito tem de ser o do aviso, com um único CN igual a Webhook e um único OU igual a
 * webhook.
 *
 * <p>O cabeçalho do outro PSP numa Conta é recusado: ele só chegaria por um domínio errado ou por um
 * proxy que deixou de apagá-lo.
 */
@Component
class AutenticadorDoAvisoPix implements WebhookPixAutenticador {
    static final String CERTIFICADO_DA_EFI = "x-ecrx-caixa-certificado-efi";
    static final String CERTIFICADO_DO_INTER = "x-ecrx-caixa-certificado-inter";
    private static final Pattern NOME = Pattern.compile(
            "ECRX_CAIXA_(EFI|INTER)_([0-9A-F]{32})_WEBHOOK_ID");
    private final Map<String, String> ambiente;

    AutenticadorDoAvisoPix() {
        this(System.getenv());
    }

    AutenticadorDoAvisoPix(Map<String, String> ambiente) {
        this.ambiente = ambiente;
    }

    @Override
    public AutenticacaoDoAviso autenticar(String identificador, String segredo,
            Map<String, String> certificados) {
        if (identificador == null || identificador.isBlank() || segredo == null
                || segredo.isBlank()) {
            return AutenticacaoDoAviso.recusada("hmac");
        }
        ContaId encontrada = null;
        String psp = null;
        for (var entrada : ambiente.entrySet()) {
            Matcher nome = NOME.matcher(entrada.getKey());
            if (!nome.matches() || !seguro(identificador, entrada.getValue())) {
                continue;
            }
            String prefixo = entrada.getKey().substring(0, entrada.getKey().length() - 2);
            String esperado = ambiente.get(prefixo + "SECRET");
            if (esperado == null || esperado.isBlank() || !seguro(segredo, esperado)
                    || encontrada != null) {
                return AutenticacaoDoAviso.recusada("hmac");
            }
            psp = nome.group(1);
            String hex = nome.group(2).toLowerCase();
            encontrada = ContaId.de(UUID.fromString(hex.substring(0, 8) + "-"
                    + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                    + hex.substring(16, 20) + "-" + hex.substring(20)));
        }
        if (encontrada == null) {
            return AutenticacaoDoAviso.recusada("hmac");
        }
        boolean certificadoDoPsp;
        if ("EFI".equals(psp)) {
            certificadoDoPsp = certificados.containsKey(CERTIFICADO_DA_EFI)
                    && !certificados.containsKey(CERTIFICADO_DO_INTER);
        } else {
            certificadoDoPsp = certificados.containsKey(CERTIFICADO_DO_INTER)
                    && !certificados.containsKey(CERTIFICADO_DA_EFI)
                    && sujeitoDoAvisoDoInter(certificados.get(CERTIFICADO_DO_INTER));
        }
        return certificadoDoPsp ? AutenticacaoDoAviso.daConta(encontrada)
                : AutenticacaoDoAviso.recusada("certificado");
    }

    /**
     * O sujeito como o proxy o escreve, partes separadas por vírgula, a mais específica primeiro. Uma
     * vírgula precedida de barra invertida é parte do valor, e não separa. Uma parte com mais de um
     * atributo, unidos por mais, não é igual a nenhuma das esperadas e reprova.
     */
    static boolean sujeitoDoAvisoDoInter(String sujeito) {
        int cn = 0;
        int ou = 0;
        boolean cnDoAviso = false;
        boolean ouDoAviso = false;
        for (String parte : sujeito.split("(?<!\\\\),")) {
            String atributo = parte.trim();
            if (atributo.startsWith("CN=")) {
                cn++;
                cnDoAviso = atributo.equals("CN=Webhook");
            } else if (atributo.startsWith("OU=")) {
                ou++;
                ouDoAviso = atributo.equals("OU=webhook");
            }
        }
        return cn == 1 && ou == 1 && cnDoAviso && ouDoAviso;
    }

    private static boolean seguro(String recebido, String esperado) {
        return MessageDigest.isEqual(recebido.getBytes(StandardCharsets.UTF_8),
                esperado.getBytes(StandardCharsets.UTF_8));
    }
}
