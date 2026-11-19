package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.caixa.SessaoCaixaFechada;
import br.com.caixasimples.contas.application.DestinatariosDoResumo;
import br.com.caixasimples.shared.FalhaRegistradaParaReenvio;
import br.com.caixasimples.shared.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manda ao administrador, por e-mail, o resumo de cada caixa fechado: faturamento, total por forma
 * de pagamento, sangrias, diferença e os três mais vendidos da sessão.
 *
 * <p><strong>Depois do commit, numa thread própria, pelo registro de publicação.</strong> Fechar o
 * caixa é operação de dinheiro e não pode falhar porque um e-mail não saiu. O evento fica gravado na
 * tabela {@code event_publication} junto com o fechamento, e a linha só é concluída quando este método
 * termina sem exceção; o que falhar ou ficar no meio de um reinício volta pelo
 * {@link ReenvioDoResumo}.
 *
 * <p><strong>Sem a anotação composta do Modulith, de propósito.</strong> Ela abre a transação antes do
 * corpo do método, na thread assíncrona, sem a Conta no contexto, e o Hibernate prende a sessão à
 * Conta que vale na abertura: toda consulta voltaria vazia. Aqui a Conta vem do evento e entra no
 * contexto primeiro; só então a transação abre, pelo {@link TransactionTemplate}, e a ordem fica
 * visível neste arquivo. O registro de publicação reconhece o método pela anotação de ouvinte
 * transacional, que está aqui por extenso.
 *
 * <p><strong>A transação só lê, e o envio fica fora dela</strong>, para uma chamada lenta ao provedor
 * não segurar uma conexão do banco.
 *
 * <p><strong>Reentrega por até 24 horas</strong>, contadas de quando o servidor gravou o fechamento.
 * Cada falha deixa uma linha de aviso, sem dado pessoal, e relança {@link FalhaRegistradaParaReenvio}
 * para a entrega ficar como falha; passado o prazo, a tentativa seguinte desiste sem enviar e deixa
 * uma linha de erro, que alerta o mantenedor. Uma falha depois de o primeiro de dois administradores
 * receber faz o primeiro receber de novo; é raro, e foi aceito.
 *
 * <p>Sem remetente configurado, o resumo está desligado, e a entrega termina sem fazer nada: é o caso
 * da máquina de desenvolvimento e dos testes que não tratam do resumo.
 */
@Component
class ResumoDoFechamentoListener {

    static final Duration PRAZO_DA_REENTREGA = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(ResumoDoFechamentoListener.class);

    private final MontagemDoResumo montagem;
    private final DestinatariosDoResumo destinatarios;
    private final EnvioDoResumoSes envio;
    private final TransactionTemplate leitura;
    private final String remetente;

    ResumoDoFechamentoListener(MontagemDoResumo montagem, DestinatariosDoResumo destinatarios,
            EnvioDoResumoSes envio, PlatformTransactionManager transacoes,
            @Value("${ecrx-caixa.resumo.remetente:}") String remetente) {
        this.montagem = montagem;
        this.destinatarios = destinatarios;
        this.envio = envio;
        this.leitura = new TransactionTemplate(transacoes);
        this.leitura.setReadOnly(true);
        this.remetente = remetente;
        // Sem remetente nada falha; esta linha é o único sinal de que o resumo não sai.
        if (remetente.isBlank()) {
            log.info("resumo.desligado motivo=sem_remetente");
        }
    }

    @Async
    @TransactionalEventListener
    public void aoFecharOCaixa(SessaoCaixaFechada evento) {
        if (remetente.isBlank()) {
            return;
        }
        Duration passado = Duration.between(evento.registradoEm(), Instant.now());
        if (passado.compareTo(PRAZO_DA_REENTREGA) >= 0) {
            log.error("resumo.abandonado conta={} sessao={} registrado_em={}",
                    evento.contaId(), evento.sessaoCaixaId(), evento.registradoEm());
            return;
        }

        try {
            // A Conta primeiro, a transação depois. Ver o javadoc da classe.
            Optional<Preparado> preparado = TenantContext.executarComo(evento.contaId(),
                    () -> leitura.execute(status -> preparar(evento)));
            if (preparado == null || preparado.isEmpty()) {
                log.info("resumo.nao_enviado conta={} sessao={}", evento.contaId(),
                        evento.sessaoCaixaId());
                return;
            }
            Preparado mensagem = preparado.get();
            for (String destinatario : mensagem.destino().emails()) {
                envio.enviar(remetente, destinatario, mensagem.assunto(), mensagem.corpo());
            }
            log.info("resumo.enviado conta={} sessao={} destinatarios={}", evento.contaId(),
                    evento.sessaoCaixaId(), mensagem.destino().emails().size());
        } catch (RuntimeException falha) {
            // Só o tipo: a mensagem do provedor pode trazer o endereço do destinatário.
            log.warn("resumo.falhou conta={} sessao={} erro={}", evento.contaId(),
                    evento.sessaoCaixaId(), falha.getClass().getName());
            throw new FalhaRegistradaParaReenvio();
        }
    }

    /**
     * O resumo e os destinatários, ou vazio quando não há o que mandar: a Conta fora do Completo em
     * dia ou sem administrador ativo, ou a sessão que já não existe, encerrada junto com a Conta.
     */
    private Optional<Preparado> preparar(SessaoCaixaFechada evento) {
        Optional<DestinatariosDoResumo.Destino> destino = destinatarios.daContaAtual();
        if (destino.isEmpty()) {
            return Optional.empty();
        }
        return montagem.daSessao(evento.sessaoCaixaId()).map(resumo -> new Preparado(
                destino.get(),
                TextoDoResumo.assunto(destino.get().nomeNegocio(), resumo),
                TextoDoResumo.corpo(destino.get().nomeNegocio(), resumo)));
    }

    private record Preparado(DestinatariosDoResumo.Destino destino, String assunto, String corpo) {
    }
}
