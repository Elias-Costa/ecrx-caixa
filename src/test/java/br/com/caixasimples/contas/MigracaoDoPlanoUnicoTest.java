package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A {@code V33}, que deixa um plano só, aplicada sobre um banco com Contas do plano gratuito e do
 * Completo, como estavam antes dela.
 *
 * <p>O banco da suíte já nasce na última versão, então a prova roda num banco à parte, no mesmo
 * contêiner: o Flyway o leva até a {@code V32}, o teste grava as linhas antigas por SQL e o Flyway
 * aplica a {@code V33}.
 */
class MigracaoDoPlanoUnicoTest extends TesteDeIntegracao {

    private static final UUID CONTA_GRATIS = UUID.randomUUID();
    private static final UUID CONTA_COMPLETA = UUID.randomUUID();
    private static final LocalDate VENCIMENTO_COMPLETO = LocalDate.of(2026, 11, 5);

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcClient jdbcDaSuite;

    @Test
    @DisplayName("a V33 passa a Conta do gratuito ao teste de 14 dias contados dela, mantém o vencimento da paga e substitui a adesão aberta")
    void migraParaOPlanoUnico() {
        String nome = "migracao_v33_" + UUID.randomUUID().toString().replace("-", "");
        jdbcDaSuite.sql("CREATE DATABASE " + nome).update();
        try {
            String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/" + nome;
            migrarAte(url, "32");
            JdbcClient banco = JdbcClient.create(
                    new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));

            UUID adminGratis = conta(banco, CONTA_GRATIS, "'GRATIS', null, null");
            UUID adminCompleto = conta(banco, CONTA_COMPLETA, "'COMPLETO', 5, '"
                    + VENCIMENTO_COMPLETO + "'");
            UUID adesaoAberta = pedido(banco, CONTA_GRATIS, adminGratis, "'ADESAO', 'COMPLETO', 70,"
                    + " null, null, 'ABERTO', null, null");
            UUID adesaoAplicada = pedido(banco, CONTA_COMPLETA, adminCompleto, "'ADESAO', 'COMPLETO',"
                    + " 70, '2026-10-05', '2026-11-05', 'APLICADO', now(), '" + adminCompleto + "'");
            UUID renovacaoAberta = pedido(banco, CONTA_COMPLETA, adminCompleto, "'RENOVACAO',"
                    + " 'COMPLETO', 70, '2026-11-05', '2026-12-05', 'ABERTO', null, null");
            // O dia do balcão pelo relógio do banco, o mesmo que a migração lê.
            LocalDate hoje = banco.sql("SELECT (now() AT TIME ZONE 'America/Bahia')::date")
                    .query(LocalDate.class).single();
            LocalDate fimDoTeste = hoje.plusDays(14);

            migrarAte(url, "33");

            assertThat(banco.sql("SELECT id, plano, em_teste, dia_de_vencimento, proximo_vencimento"
                            + " FROM conta")
                    .query((linha, n) -> tuple(linha.getObject("id", UUID.class),
                            linha.getString("plano"), linha.getBoolean("em_teste"),
                            linha.getObject("dia_de_vencimento"),
                            linha.getObject("proximo_vencimento", LocalDate.class)))
                    .list())
                    .containsExactlyInAnyOrder(
                            tuple(CONTA_GRATIS, "COMPLETO", true, fimDoTeste.getDayOfMonth(),
                                    fimDoTeste),
                            tuple(CONTA_COMPLETA, "COMPLETO", false, 5, VENCIMENTO_COMPLETO));
            assertThat(banco.sql("SELECT id, situacao FROM pedido_de_plano")
                    .query((linha, n) -> tuple(linha.getObject("id", UUID.class),
                            linha.getString("situacao")))
                    .list())
                    .as("a adesão aberta, feita para sair do gratuito, é substituída; o resto fica")
                    .containsExactlyInAnyOrder(
                            tuple(adesaoAberta, "SUBSTITUIDO"),
                            tuple(adesaoAplicada, "APLICADO"),
                            tuple(renovacaoAberta, "ABERTO"));

            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .isThrownBy(() -> banco.sql("UPDATE conta SET plano = 'GRATIS' WHERE id = :id")
                            .param("id", CONTA_COMPLETA)
                            .update())
                    .withMessageContaining("conta_plano_valido");
            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .as("a Conta sem ciclo, como o gratuito gravava, não entra mais")
                    .isThrownBy(() -> conta(banco, UUID.randomUUID(), "'COMPLETO', null, null"))
                    .withMessageContaining("dia_de_vencimento");
        } finally {
            jdbcDaSuite.sql("DROP DATABASE " + nome + " WITH (FORCE)").update();
        }
    }

    private void migrarAte(String url, String versao) {
        Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(versao)
                .load()
                .migrate();
    }

    /**
     * Uma Conta com o ADMIN que a criou, gravada por SQL como a versão anterior aceitava.
     *
     * @param planoECiclo o plano, o dia e o próximo vencimento, já como literais de SQL
     * @return o id do ADMIN
     */
    private static UUID conta(JdbcClient banco, UUID id, String planoECiclo) {
        banco.sql("INSERT INTO conta (id, nome_negocio, plano, dia_de_vencimento,"
                        + " proximo_vencimento) VALUES (:id, 'Loja da Esquina', " + planoECiclo + ")")
                .param("id", id)
                .update();
        UUID admin = UUID.randomUUID();
        banco.sql("INSERT INTO usuario (id, conta_id, nome, perfil) VALUES (:id, :conta, 'Ana',"
                        + " 'ADMIN')")
                .param("id", admin)
                .param("conta", id)
                .update();
        return admin;
    }

    /**
     * @param resto tipo, plano, valor, período, situação e aplicação, já como literais de SQL
     * @return o id do pedido
     */
    private static UUID pedido(JdbcClient banco, UUID conta, UUID criadoPor, String resto) {
        UUID id = UUID.randomUUID();
        banco.sql("INSERT INTO pedido_de_plano (id, conta_id, criado_em, criado_por, tipo, plano,"
                        + " valor, periodo_inicio, periodo_fim, situacao, aplicado_em, aplicado_por)"
                        + " VALUES (:id, :conta, now(), :criadoPor, " + resto + ")")
                .param("id", id)
                .param("conta", conta)
                .param("criadoPor", criadoPor)
                .update();
        return id;
    }
}
