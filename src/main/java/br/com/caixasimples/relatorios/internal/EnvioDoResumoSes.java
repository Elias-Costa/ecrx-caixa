package br.com.caixasimples.relatorios.internal;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Manda um e-mail de texto puro pelo Amazon SES em São Paulo, um destinatário por chamada.
 *
 * <p><strong>Pela API, por HTTPS, e não por SMTP.</strong> A aplicação já fala com a AWS pela porta
 * 443, para o registro de remoções no S3, com as mesmas credenciais lidas do ambiente; o SMTP pediria
 * outra porta de saída da máquina, que o provedor da hospedagem pode bloquear, e outra credencial.
 *
 * <p><strong>Um destinatário por chamada</strong>, para nenhum administrador ver o endereço do outro.
 * A falha de qualquer chamada sobe como a exceção do SDK, e quem chama decide o que fazer com ela;
 * o texto dela não vai para o log, porque pode conter o endereço.
 */
@Component
class EnvioDoResumoSes {

    private final SesV2Client ses;

    EnvioDoResumoSes(SesV2Client ses) {
        this.ses = ses;
    }

    void enviar(String remetente, String destinatario, String assunto, String corpo) {
        String utf8 = StandardCharsets.UTF_8.name();
        ses.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(remetente)
                .destination(Destination.builder().toAddresses(destinatario).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().charset(utf8).data(assunto).build())
                                .body(Body.builder()
                                        .text(Content.builder().charset(utf8).data(corpo).build())
                                        .build())
                                .build())
                        .build())
                .build());
    }
}

/**
 * O cliente do SES, na região de São Paulo, onde fica todo dado do serviço. As credenciais vêm do
 * ambiente pela cadeia padrão do SDK, como as do S3, e só são lidas na primeira chamada: a aplicação
 * sobe sem elas, e o resumo desligado nunca chama.
 *
 * <p>Trinta segundos por chamada, no máximo: o envio roda numa thread do ouvinte, e uma chamada presa
 * seguraria a publicação no meio, em vez de deixá-la falhar e voltar no reenvio.
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoDoEnvioDoResumo {

    @Bean
    SesV2Client clienteSesDoResumo() {
        return SesV2Client.builder()
                .region(Region.SA_EAST_1)
                .overrideConfiguration(configuracao ->
                        configuracao.apiCallTimeout(Duration.ofSeconds(30)))
                .build();
    }
}
