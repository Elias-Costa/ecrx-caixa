package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.caixa.SessaoCaixaFechada;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A cada 15 minutos, entrega de novo o resumo dos caixas fechados cujo envio falhou ou ficou no meio
 * de um reinício da aplicação. Quem desiste, passadas 24 horas, é o próprio ouvinte, na tentativa
 * seguinte ao prazo, para a desistência deixar uma linha de erro uma vez só.
 *
 * <p><strong>Pega toda entrega não concluída, e não só a marcada como falha.</strong> A entrega que
 * um reinício interrompeu fica como publicada ou em andamento, sem nunca virar falha, e se perderia se
 * o reenvio olhasse só as falhas.
 *
 * <p><strong>Só a que não foi tentada nos últimos 10 minutos.</strong> Uma entrega em andamento
 * também não está concluída, e reenviá-la mandaria o mesmo e-mail duas vezes; 10 minutos passam em
 * muito o tempo de uma tentativa, que é de 30 segundos por destinatário no máximo, e ficam abaixo do
 * intervalo de 15, para a falha de agora entrar na rodada seguinte, e não na outra.
 *
 * <p>Só existe com servidor web. As execuções avulsas do mantenedor rodam sem ele e terminam quando o
 * trabalho acaba; um agendador ligado nelas as manteria vivas.
 */
@Component
@ConditionalOnWebApplication
class ReenvioDoResumo {

    static final Duration SEM_TENTATIVA_HA = Duration.ofMinutes(10);

    private final IncompleteEventPublications naoConcluidas;

    ReenvioDoResumo(IncompleteEventPublications naoConcluidas) {
        this.naoConcluidas = naoConcluidas;
    }

    @Scheduled(initialDelay = 15, fixedDelay = 15, timeUnit = TimeUnit.MINUTES)
    void reenviar() {
        Instant limite = Instant.now().minus(SEM_TENTATIVA_HA);
        naoConcluidas.resubmitIncompletePublications(publicacao ->
                publicacao.getEvent() instanceof SessaoCaixaFechada
                        && ultimaTentativa(publicacao).isBefore(limite));
    }

    /** A última vez que a entrega foi tentada: a publicação, ou o último reenvio, se houve. */
    private static Instant ultimaTentativa(EventPublication publicacao) {
        Instant reenvio = publicacao.getLastResubmissionDate();
        return reenvio != null ? reenvio : publicacao.getPublicationDate();
    }
}

/** Liga o agendamento só onde o reenvio existe: com servidor web. Ver {@link ReenvioDoResumo}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableScheduling
class ConfiguracaoDoReenvioDoResumo {
}
