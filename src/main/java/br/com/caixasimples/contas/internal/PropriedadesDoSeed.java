package br.com.caixasimples.contas.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dados da conta criada pelo {@link SeedDeConta}, vindos do ambiente.
 *
 * <p>Sem valor padrão para {@code email} e {@code senha}: uma senha padrão em código seria a mesma
 * em toda instalação.
 *
 * @param tipoNegocio opcional; casa com {@code modelo_produto.tipo_negocio} para sugerir o
 *                    catálogo inicial (RF32), e nulo ou em branco significa cadastro começando
 *                    vazio
 */
@ConfigurationProperties(prefix = "ecrx-caixa.seed")
record PropriedadesDoSeed(
        String nomeNegocio,
        String tipoNegocio,
        String nomeUsuario,
        String email,
        String senha) {

    PropriedadesDoSeed {
        nomeUsuario = (nomeUsuario == null || nomeUsuario.isBlank()) ? "Administrador" : nomeUsuario;
        // Na VPS o tipo é lido por um prompt, e a resposta vazia chega aqui como texto vazio. Gravado
        // assim, a Conta teria um tipo que não é nenhum, no lugar do nulo que diz começar vazia.
        tipoNegocio = (tipoNegocio == null || tipoNegocio.isBlank()) ? null : tipoNegocio.trim();
    }
}
