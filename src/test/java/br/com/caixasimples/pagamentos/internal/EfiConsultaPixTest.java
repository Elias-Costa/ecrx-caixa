package br.com.caixasimples.pagamentos.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.shared.Money;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EfiConsultaPixTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void distingueCobrancaRemovidaDeCobrancaAindaAtiva() {
        String base = """
                {"txid":"12345678901234567890123456789012","chave":"chave-a",
                 "valor":{"original":"10.00"},"status":"%s"}
                """;
        assertThat(EfiPixGateway.interpretarConsulta(JSON.readTree(
                base.formatted("ATIVA"))).removida()).isFalse();
        assertThat(EfiPixGateway.interpretarConsulta(JSON.readTree(
                base.formatted("REMOVIDA_PELO_USUARIO_RECEBEDOR"))).removida()).isTrue();
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                EfiPixGateway.interpretarConsulta(JSON.readTree(
                        base.formatted("STATUS_DESCONHECIDO"))));
    }

    @Test
    void soAceitaCobrancaConcluidaComPixRecebidoCorrespondente() {
        var resposta = EfiPixGateway.interpretarConsulta(JSON.readTree("""
                {"txid":"12345678901234567890123456789012","chave":"chave-a",
                 "valor":{"original":"10.00"},"status":"CONCLUIDA",
                 "pix":[{"txid":"12345678901234567890123456789012","valor":"10.00"}]}
                """));
        assertThat(resposta.pago()).isTrue();
        assertThat(resposta.valor()).isEqualTo(Money.de("10.00"));
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                EfiPixGateway.interpretarConsulta(JSON.readTree("""
                        {"txid":"12345678901234567890123456789012","chave":"chave-a",
                         "valor":{"original":"10.00"},"status":"CONCLUIDA","pix":[]}
                        """)));
        assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                EfiPixGateway.interpretarConsulta(JSON.readTree("""
                        {"txid":"12345678901234567890123456789012","chave":"chave-a",
                         "valor":{"original":"10.00"},"status":"CONCLUIDA",
                         "pix":[{"txid":"outro","valor":"10.00"}]}
                        """)));
    }
}
