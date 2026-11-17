package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.pagamentos.domain.ConsultaPix;
import br.com.caixasimples.pagamentos.domain.PixGateway;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * A PixGateway que o caso de uso recebe: escolhe, pela Conta do contexto, o adapter do PSP dela, e
 * repassa a operação. Os adapters de cada PSP também implementam a porta, e só esta classe os chama;
 * por isso ela é a principal entre as implementações, e é ela que a injeção por tipo entrega.
 *
 * <p>A escolha é pela presença da variável AMBIENTE de cada PSP na Conta. Uma configuração incompleta
 * conta como presente: a Efí pela metade não deixa o Inter assumir em silêncio, e o adapter dela
 * responde que falta configuração.
 *
 * <p>Com os dois PSPs presentes, a aplicação não sabe qual é o certo. A Conta fica sem Pix integrado
 * até o mantenedor deixar só um, as outras seguem, e cada chamada registra o erro, que o alerta de
 * erros leva ao mantenedor.
 */
@Component
@Primary
class PixGatewayDaConta implements PixGateway {
    private static final Logger log = LoggerFactory.getLogger(PixGatewayDaConta.class);

    enum Psp { EFI, INTER, NENHUM, OS_DOIS }

    private final EfiPixGateway efi;
    private final InterPixGateway inter;
    private final Map<String, String> ambiente;

    @Autowired
    PixGatewayDaConta(EfiPixGateway efi, InterPixGateway inter) {
        this(efi, inter, System.getenv());
    }

    PixGatewayDaConta(EfiPixGateway efi, InterPixGateway inter, Map<String, String> ambiente) {
        this.efi = efi;
        this.inter = inter;
        this.ambiente = ambiente;
    }

    Psp pspDaConta() {
        ContaId conta = TenantContext.exigirAtual();
        String sufixo = conta.valor().toString().replace("-", "").toUpperCase();
        boolean naEfi = presente("ECRX_CAIXA_EFI_" + sufixo + "_AMBIENTE");
        boolean noInter = presente("ECRX_CAIXA_INTER_" + sufixo + "_AMBIENTE");
        if (naEfi && noInter) {
            log.error("Pix integrado desligado na Conta {}: a configuração tem a Efí e o Inter ao "
                    + "mesmo tempo, e só um pode ficar", conta.valor());
            return Psp.OS_DOIS;
        }
        if (naEfi) {
            return Psp.EFI;
        }
        if (noInter) {
            return Psp.INTER;
        }
        return Psp.NENHUM;
    }

    @Override
    public boolean configurada() {
        return switch (pspDaConta()) {
            case EFI -> efi.configurada();
            case INTER -> inter.configurada();
            case NENHUM, OS_DOIS -> false;
        };
    }

    @Override
    public String chaveRecebedora() {
        return daConta().chaveRecebedora();
    }

    @Override
    public String criarCobranca(CobrancaPix cobranca, Money valor) {
        return daConta().criarCobranca(cobranca, valor);
    }

    @Override
    public ConsultaPix consultar(CobrancaPix cobranca) {
        return daConta().consultar(cobranca);
    }

    @Override
    public void removerCobranca(CobrancaPix cobranca) {
        daConta().removerCobranca(cobranca);
    }

    private PixGateway daConta() {
        return switch (pspDaConta()) {
            case EFI -> efi;
            case INTER -> inter;
            // A cobrança já reservada fica pendente, sem confirmar nem liberar, até a correção.
            case OS_DOIS -> throw new PixIndisponivelException(
                    "Pix integrado com a Efí e o Inter configurados na mesma Conta");
            case NENHUM -> throw new IllegalStateException(
                    "O Pix integrado não está configurado para esta Conta.");
        };
    }

    private boolean presente(String nome) {
        String valor = ambiente.get(nome);
        return valor != null && !valor.isBlank();
    }
}
