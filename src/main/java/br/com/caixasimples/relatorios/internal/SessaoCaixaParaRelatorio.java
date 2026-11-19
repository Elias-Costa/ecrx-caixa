package br.com.caixasimples.relatorios.internal;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * A tabela {@code sessao_caixa} vista pelos relatórios: o dono da sessão, para a conferência do
 * cartão dizer quem recebeu um fiado, e o status, os instantes e a diferença, para o resumo do caixa
 * fechado.
 *
 * <p>O recebimento de fiado guarda a sessão em que entrou, e não a pessoa; a pessoa é a dona da
 * sessão, porque o fiado só se recebe no próprio caixa aberto. Mesmo desenho de
 * {@link PagamentoParaRelatorio}: somente leitura, alvo de junção, e lida pelo repositório do resumo,
 * {@link ResumoDaSessaoRepository}, que não tem método de escrita.
 *
 * <p>{@code contaId} filtra pelo {@link TenantId} (RNF05).
 */
@Entity
@Immutable
@Table(name = "sessao_caixa")
public class SessaoCaixaParaRelatorio {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    /** O operador que abriu a sessão: o caixa é sempre de uma pessoa só. */
    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    /** O status, para o resumo do caixa fechado ler só uma sessão FECHADA. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusSessaoCaixa status;

    /** O instante do balcão em que a sessão abriu, em UTC, para o resumo dizer de qual caixa é. */
    @Column(name = "aberta_em", nullable = false)
    private Instant abertaEm;

    /** O instante do balcão em que a gaveta foi contada; nulo enquanto ABERTA. */
    @Column(name = "fechada_em")
    private Instant fechadaEm;

    /** Positivo é falta na gaveta, negativo é sobra; nulo enquanto ABERTA. */
    @Column
    private BigDecimal diferenca;

    protected SessaoCaixaParaRelatorio() {
        // exigido pelo JPA, e o unico construtor de proposito: ninguem instancia esta classe
    }
}
