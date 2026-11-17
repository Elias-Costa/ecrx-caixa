package br.com.caixasimples.pagamentos.domain;

import java.util.Map;

/**
 * Resolve a Conta do aviso Pix pelo segredo da URL e pelo certificado que o proxy conferiu, sem aceitar
 * identidade da Conta no payload. Qual certificado cada Conta exige depende do PSP dela, que só a
 * implementação conhece.
 */
public interface WebhookPixAutenticador {

    /**
     * @param certificados os cabeçalhos de certificado que o proxy escreveu, com o nome em minúsculas,
     *                     já sem os vazios
     */
    AutenticacaoDoAviso autenticar(String identificador, String segredo,
            Map<String, String> certificados);
}
