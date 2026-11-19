package br.com.caixasimples.shared.internal;

import br.com.caixasimples.shared.FalhaRegistradaParaReenvio;
import br.com.caixasimples.shared.PilhaSemMensagens;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Liga os métodos assíncronos, que hoje são só os ouvintes de evento que passam pelo registro de
 * publicação, e diz o que fazer com a exceção que sobe de um deles.
 *
 * <p>O executor é o que o Spring Boot registra, com threads virtuais, porque a aplicação as liga.
 *
 * <p><strong>A exceção que sobe vira uma linha de erro, menos uma.</strong>
 * {@link FalhaRegistradaParaReenvio} é a falha que o ouvinte já anotou e relança só para a entrega
 * ficar como falha e voltar no reenvio; registrá-la de novo como erro dispararia o alerta do
 * mantenedor a cada tentativa. Qualquer outra é defeito, e sai como erro, com a pilha sem as
 * mensagens, que podem trazer dado pessoal, como nas outras linhas de erro da aplicação.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class ConfiguracaoAssincrona implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoAssincrona.class);

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return ConfiguracaoAssincrona::registrar;
    }

    private static void registrar(Throwable falha, Method metodo, Object... argumentos) {
        if (falha instanceof FalhaRegistradaParaReenvio) {
            return;
        }
        log.error("assincrono.falhou metodo={}.{} {}", metodo.getDeclaringClass().getSimpleName(),
                metodo.getName(), PilhaSemMensagens.de(falha));
    }
}
