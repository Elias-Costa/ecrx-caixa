package br.com.caixasimples.contas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.internal.AssinaturaDePedido;
import br.com.caixasimples.contas.internal.CicloDeVencimento;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O pagamento do plano pela API, com o token de verdade: o caminho da tela do plano, os códigos de
 * status de cada recusa e a identidade que o aplicativo lê ao abrir, com os campos que o aplicativo
 * instalado antes do plano único continua lendo.
 */
class PlanoHttpTest extends TesteDeIntegracao {

    private static final String[] TODOS = {"RELATORIOS", "ESTOQUE", "MULTIUSUARIO", "PIX_INTEGRADO"};

    private static final String SENHA = "uma senha longa de teste";

    @Autowired
    private MockMvc http;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private AutenticadorDeTeste autenticador;

    @Autowired
    private AssinaturaDePedido assinatura;

    @Test
    @DisplayName("no teste, o administrador pede o primeiro mês, recebe o pedido e aplica o código; a identidade passa a trazer o mês pago")
    void pedidoECodigoPelaApi() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        LocalDate hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO);
        LocalDate fim = CicloDeVencimento.fimDoTeste(hoje);
        LocalDate pago = CicloDeVencimento.seguinte(fim, fim.getDayOfMonth());

        http.perform(get("/api/conta/plano").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano").value("COMPLETO"))
                .andExpect(jsonPath("$.situacao").value("EM_DIA"))
                .andExpect(jsonPath("$.emTeste").value(true))
                .andExpect(jsonPath("$.vencimento").value(fim.toString()))
                .andExpect(jsonPath("$.inicioDaSuspensao")
                        .value(CicloDeVencimento.inicioDoSomenteLeitura(fim).toString()))
                .andExpect(jsonPath("$.recursos", containsInAnyOrder(TODOS)))
                .andExpect(jsonPath("$.mensalidadeCompleto").value(70.0))
                .andExpect(jsonPath("$.mensalidadeCaixaSimples").doesNotExist())
                .andExpect(jsonPath("$.propostas.length()").value(1))
                .andExpect(jsonPath("$.propostas[0].tipo").value("RENOVACAO"))
                .andExpect(jsonPath("$.propostas[0].plano").value("COMPLETO"))
                .andExpect(jsonPath("$.propostas[0].periodoInicio").value(fim.toString()))
                .andExpect(jsonPath("$.propostas[0].periodoFim").value(pago.toString()))
                .andExpect(jsonPath("$.pedidoAberto").doesNotExist());
        // Todos os campos que o aplicativo instalado lia continuam, com o mesmo nome.
        http.perform(get("/api/auth/eu").with(admin))
                .andExpect(jsonPath("$.plano").value("COMPLETO"))
                .andExpect(jsonPath("$.situacaoDoPlano").value("EM_DIA"))
                .andExpect(jsonPath("$.vencimentoDoPlano").value(fim.toString()))
                .andExpect(jsonPath("$.inicioDaSuspensao").exists())
                .andExpect(jsonPath("$.recursos", containsInAnyOrder(TODOS)))
                .andExpect(jsonPath("$.pixIntegrado").exists())
                .andExpect(jsonPath("$.termosPendentes").exists())
                .andExpect(jsonPath("$.emTeste").value(true));

        // O pedido vai no corpo de antes: só o plano.
        JsonNode pedido = pedir(admin, "COMPLETO");
        UUID pedidoId = UUID.fromString(pedido.path("id").asText());
        assertThat(pedido.path("tipo").asText()).isEqualTo("RENOVACAO");
        assertThat(pedido.path("periodoInicio").asText()).isEqualTo(fim.toString());
        String codigo = assinatura.codigo(pedidoId, Plano.COMPLETO, Money.de(MENSALIDADE_COMPLETO));

        http.perform(aplicar(admin, pedidoId, "0000-0000-0000-0000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("código")));
        // O código que o mantenedor geraria com o valor de um texto alterado.
        http.perform(aplicar(admin, pedidoId,
                        assinatura.codigo(pedidoId, Plano.COMPLETO, Money.de("0.01"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("código")));
        // Do jeito que alguém digita: minúsculo e sem os hífens.
        http.perform(aplicar(admin, pedidoId, codigo.replace("-", "").toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano").value("COMPLETO"))
                .andExpect(jsonPath("$.situacao").value("EM_DIA"))
                .andExpect(jsonPath("$.emTeste").value(false))
                .andExpect(jsonPath("$.recursos", containsInAnyOrder(TODOS)))
                .andExpect(jsonPath("$.vencimento").value(pago.toString()))
                .andExpect(jsonPath("$.pedidoAberto").doesNotExist());
        http.perform(aplicar(admin, pedidoId, codigo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vencimento").value(pago.toString()));

        http.perform(get("/api/auth/eu").with(admin))
                .andExpect(jsonPath("$.plano").value("COMPLETO"))
                .andExpect(jsonPath("$.situacaoDoPlano").value("EM_DIA"))
                .andExpect(jsonPath("$.emTeste").value(false))
                .andExpect(jsonPath("$.vencimentoDoPlano").value(pago.toString()))
                .andExpect(jsonPath("$.recursos", containsInAnyOrder(TODOS)));
        http.perform(get("/api/relatorios/faturamento/dia").param("dia", hoje.toString())
                        .with(admin))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("pedido substituído responde 409, pedido de outra Conta 404, operador 403, pedido sem plano ou de plano que não existe 400")
    void recusasPorStatus() throws Exception {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);
        ContaCriada outra = criador.criar("Loja do Outro Lado", SENHA);
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        RequestPostProcessor admin = autenticador.como(conta);

        UUID primeiro = UUID.fromString(pedir(admin, "COMPLETO").path("id").asText());
        pedir(admin, "COMPLETO");

        String codigoDoPrimeiro = assinatura.codigo(primeiro, Plano.COMPLETO,
                Money.de(MENSALIDADE_COMPLETO));
        http.perform(aplicar(admin, primeiro, codigoDoPrimeiro))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("substituído")));
        http.perform(aplicar(autenticador.como(outra), primeiro, codigoDoPrimeiro))
                .andExpect(status().isNotFound());
        http.perform(get("/api/conta/plano").with(autenticador.como(operador)))
                .andExpect(status().isForbidden());
        http.perform(post("/api/conta/plano/pedidos").with(autenticador.como(operador))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plano\":\"COMPLETO\"}"))
                .andExpect(status().isForbidden());
        http.perform(post("/api/conta/plano/pedidos").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // O plano intermediário saiu: o valor dele não é mais um plano que a API leia.
        http.perform(post("/api/conta/plano/pedidos").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plano\":\"CAIXA_SIMPLES\"}"))
                .andExpect(status().isBadRequest());
        // O aplicativo instalado antes ainda conhece o gratuito: a recusa é uma frase, não um 400.
        http.perform(post("/api/conta/plano/pedidos").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plano\":\"GRATIS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("O plano gratuito não existe mais; o pedido é do plano Completo."));
    }

    @Test
    @DisplayName("no teste, relatório e estoque respondem, e o operador continua com 403")
    void testeTemRelatorioEEstoque() throws Exception {
        ContaCriada conta = criador.criar("Salão Aurora", SENHA);
        criador.habilitarEstoque(conta.contaId());
        ContaCriada operador = criador.criar("Salão do Operador", SENHA, Perfil.OPERADOR, true);
        LocalDate hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO);

        http.perform(faturamentoDoDia(conta, hoje)).andExpect(status().isOk());
        http.perform(get("/api/estoque/produtos").with(autenticador.como(conta)))
                .andExpect(status().isOk());
        http.perform(faturamentoDoDia(operador, hoje)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("o teste, o vencimento e o só leitura são os da Conta do token: a em teste e a paga em dia não herdam o só leitura da outra (RNF05)")
    void planoEVencimentoSaoDaContaDoToken() throws Exception {
        LocalDate hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO);
        ContaCriada somenteLeitura = criador.criar("Mercearia Aurora", SENHA);
        criador.contratar(somenteLeitura.contaId());
        criador.vencerPlano(somenteLeitura.contaId(), 10);
        LocalDate vencida = hoje.minusDays(10);
        LocalDate desde = CicloDeVencimento.inicioDoSomenteLeitura(vencida);
        ContaCriada noTeste = criador.criar("Banca da Esquina", SENHA);
        LocalDate fimDoTeste = CicloDeVencimento.fimDoTeste(hoje);
        // Criada por último: quem lesse a Conta mais nova no lugar da do token leria esta.
        ContaCriada emDia = criador.criar("Café da Praça", SENHA);
        criador.contratar(emDia.contaId());
        LocalDate vencimentoEmDia = CicloDeVencimento.seguinte(fimDoTeste, fimDoTeste.getDayOfMonth());

        // A só leitura continua com o próprio vencimento e a data do só leitura, lê os relatórios
        // e não abre caixa.
        http.perform(get("/api/conta/plano").with(autenticador.como(somenteLeitura)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("SOMENTE_LEITURA"))
                .andExpect(jsonPath("$.emTeste").value(false))
                .andExpect(jsonPath("$.vencimento").value(vencida.toString()))
                .andExpect(jsonPath("$.inicioDaSuspensao").value(desde.toString()))
                .andExpect(jsonPath("$.recursos",
                        containsInAnyOrder("RELATORIOS", "ESTOQUE", "MULTIUSUARIO")))
                .andExpect(jsonPath("$.propostas[0].tipo").value("ADESAO"));
        http.perform(get("/api/auth/eu").with(autenticador.como(somenteLeitura)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacaoDoPlano").value("SOMENTE_LEITURA"))
                .andExpect(jsonPath("$.vencimentoDoPlano").value(vencida.toString()))
                .andExpect(jsonPath("$.inicioDaSuspensao").value(desde.toString()))
                .andExpect(jsonPath("$.pixIntegrado").value(false));
        http.perform(faturamentoDoDia(somenteLeitura, hoje)).andExpect(status().isOk());
        http.perform(abrirCaixa(somenteLeitura))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("só leitura")));

        // A em teste não lê o vencimento das outras nem herda o só leitura.
        http.perform(get("/api/conta/plano").with(autenticador.como(noTeste)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacao").value("EM_DIA"))
                .andExpect(jsonPath("$.emTeste").value(true))
                .andExpect(jsonPath("$.vencimento").value(fimDoTeste.toString()))
                .andExpect(jsonPath("$.recursos", containsInAnyOrder(TODOS)));
        http.perform(abrirCaixa(noTeste)).andExpect(status().isCreated());

        // A paga em dia também não.
        http.perform(get("/api/auth/eu").with(autenticador.como(emDia)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacaoDoPlano").value("EM_DIA"))
                .andExpect(jsonPath("$.emTeste").value(false))
                .andExpect(jsonPath("$.vencimentoDoPlano").value(vencimentoEmDia.toString()))
                .andExpect(jsonPath("$.recursos", containsInAnyOrder(TODOS)));
        http.perform(abrirCaixa(emDia)).andExpect(status().isCreated());
    }

    private RequestBuilder abrirCaixa(ContaCriada conta) {
        return post("/api/caixa/sessoes").with(autenticador.como(conta))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"valorAbertura\":0}");
    }

    private RequestBuilder faturamentoDoDia(ContaCriada conta, LocalDate dia) {
        return get("/api/relatorios/faturamento/dia").param("dia", dia.toString())
                .with(autenticador.como(conta));
    }

    private JsonNode pedir(RequestPostProcessor quem, String plano) throws Exception {
        String corpo = http.perform(post("/api/conta/plano/pedidos").with(quem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plano\":\"" + plano + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/conta/plano/pedidos/")))
                .andExpect(jsonPath("$.situacao").value("ABERTO"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(corpo);
    }

    private RequestBuilder aplicar(RequestPostProcessor quem, UUID pedidoId, String codigo) {
        return post("/api/conta/plano/pedidos/" + pedidoId + "/codigo").with(quem)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"codigo\":\"" + codigo + "\"}");
    }
}
