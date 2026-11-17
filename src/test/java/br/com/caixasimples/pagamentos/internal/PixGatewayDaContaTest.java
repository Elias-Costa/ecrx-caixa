package br.com.caixasimples.pagamentos.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.pagamentos.domain.ConsultaPix;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A escolha do PSP pela Conta do contexto. Os adapters de teste só respondem quem foram; o contrato
 * HTTP de cada um está no teste dele.
 */
@ExtendWith(OutputCaptureExtension.class)
class PixGatewayDaContaTest {
    private final ContaId conta = ContaId.de(UUID.randomUUID());
    private final String sufixo = conta.valor().toString().replace("-", "").toUpperCase();
    private final CobrancaPix cobranca = CobrancaPix.aguardando(UUID.randomUUID(), "chave",
            Instant.now().plusSeconds(900));

    @Test
    void contaDaEfiUsaAEfi() {
        PixGatewayDaConta selecao = selecao(Map.of("ECRX_CAIXA_EFI_" + sufixo + "_AMBIENTE",
                "HOMOLOGACAO"));
        TenantContext.executarComo(conta, () -> {
            assertThat(selecao.pspDaConta()).isEqualTo(PixGatewayDaConta.Psp.EFI);
            assertThat(selecao.configurada()).isTrue();
            assertThat(selecao.chaveRecebedora()).isEqualTo("chave da Efí");
            assertThat(selecao.criarCobranca(cobranca, Money.de("1.00"))).isEqualTo("código da Efí");
        });
    }

    @Test
    void contaDoInterUsaOInter() {
        PixGatewayDaConta selecao = selecao(Map.of("ECRX_CAIXA_INTER_" + sufixo + "_AMBIENTE",
                "PRODUCAO"));
        TenantContext.executarComo(conta, () -> {
            assertThat(selecao.pspDaConta()).isEqualTo(PixGatewayDaConta.Psp.INTER);
            assertThat(selecao.configurada()).isTrue();
            assertThat(selecao.chaveRecebedora()).isEqualTo("chave do Inter");
            assertThat(selecao.consultar(cobranca).chaveRecebedora()).isEqualTo("chave do Inter");
        });
    }

    /** A configuração de outra Conta não liga o Pix desta. */
    @Test
    void contaSemPspContinuaSemPixIntegrado() {
        String outra = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        PixGatewayDaConta selecao = selecao(Map.of("ECRX_CAIXA_INTER_" + outra + "_AMBIENTE",
                "PRODUCAO"));
        TenantContext.executarComo(conta, () -> {
            assertThat(selecao.pspDaConta()).isEqualTo(PixGatewayDaConta.Psp.NENHUM);
            assertThat(selecao.configurada()).isFalse();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(selecao::chaveRecebedora)
                    .withMessage("O Pix integrado não está configurado para esta Conta.");
        });
    }

    @Test
    void contaComOsDoisFicaSemPixERegistraOErro(CapturedOutput logs) {
        Map<String, String> ambiente = new HashMap<>();
        ambiente.put("ECRX_CAIXA_EFI_" + sufixo + "_AMBIENTE", "PRODUCAO");
        // Incompleta também conta: a Efí pela metade não deixa o Inter assumir.
        ambiente.put("ECRX_CAIXA_INTER_" + sufixo + "_AMBIENTE", "qualquer");
        PixGatewayDaConta selecao = selecao(ambiente);
        TenantContext.executarComo(conta, () -> {
            assertThat(selecao.configurada()).isFalse();
            assertThatExceptionOfType(PixIndisponivelException.class)
                    .isThrownBy(() -> selecao.consultar(cobranca));
            assertThatExceptionOfType(PixIndisponivelException.class)
                    .isThrownBy(() -> selecao.removerCobranca(cobranca));
        });
        assertThat(logs.getAll()).contains("ERROR")
                .contains("Pix integrado desligado na Conta " + conta.valor());
    }

    private static PixGatewayDaConta selecao(Map<String, String> ambiente) {
        return new PixGatewayDaConta(new EfiDeTeste(), new InterDeTeste(), ambiente);
    }

    private static final class EfiDeTeste extends EfiPixGateway {
        @Override
        public boolean configurada() {
            return true;
        }

        @Override
        public String chaveRecebedora() {
            return "chave da Efí";
        }

        @Override
        public String criarCobranca(CobrancaPix cobranca, Money valor) {
            return "código da Efí";
        }
    }

    private static final class InterDeTeste extends InterPixGateway {
        InterDeTeste() {
            super(Map.of(), Clock.systemUTC());
        }

        @Override
        public boolean configurada() {
            return true;
        }

        @Override
        public String chaveRecebedora() {
            return "chave do Inter";
        }

        @Override
        public ConsultaPix consultar(CobrancaPix cobranca) {
            return new ConsultaPix(cobranca.txid(), "chave do Inter", Money.de("1.00"), false);
        }
    }
}
