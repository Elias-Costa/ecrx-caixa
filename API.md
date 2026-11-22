# API HTTP do ECRX Caixa

As rotas que o aplicativo consome e o que cada uma recusa. A visão geral está no
[README](README.md); o comportamento das telas, em [FUNCIONALIDADES.md](FUNCIONALIDADES.md); o
desenho, em [ARQUITETURA.md](ARQUITETURA.md).

Os casos de uso de produto, cliente, caixa, pagamento, venda, estoque, relatório e usuário são
exercitados por teste de integração, inclusive a autorização por perfil, que é verificada dentro de
cada caso de uso e não por rota: a camada `web` de cada módulo nasce junto com a tela do PWA que vai
consumi-la, para o contrato HTTP ser exercitado pela interface.

## Sumário

- [Autenticação e identidade](#autenticação-e-identidade)
- [Contrato comum](#contrato-comum)
- [Cadastro](#cadastro)
- [Venda](#venda)
  - [Pix integrado e aviso do provedor](#pix-integrado-e-aviso-do-provedor)
  - [Conciliação, Cliente, conclusão e comprovante](#conciliação-cliente-conclusão-e-comprovante)
- [Fiado](#fiado)
- [Caixa](#caixa)
- [Relatórios](#relatórios)
- [Usuários](#usuários)
- [Configuração da Conta](#configuração-da-conta)
- [Plano e termos de uso](#plano-e-termos-de-uso)
- [Estoque](#estoque)
- [Sincronização](#sincronização)
- [Concorrência](#concorrência)
- [Erros do aplicativo](#erros-do-aplicativo)
- [Saúde da aplicação](#saúde-da-aplicação)
- [Configuração do webhook Pix (RF25, RNF05)](#configuração-do-webhook-pix-rf25-rnf05)

## Autenticação e identidade

A autenticação usa `POST /api/auth/login`, que devolve o token ou, depois de falhas demais da mesma
origem, 429 com a espera até a janela acabar, e `GET /api/auth/eu`, que diz quem está autenticado e
em que negócio, para o cabeçalho de toda tela, e que serve também de recurso protegido contra o qual
verificar, por HTTP, que requisição sem token é recusada, que o tenant vem do claim e não do pedido,
e que o perfil é o do banco: um usuário inativado recebe 401 na requisição seguinte, com o token que
já tinha. `POST /api/auth/senha` troca a senha de quem está autenticado, com a atual e a nova, e
devolve no corpo o token novo deste aparelho; a senha atual errada responde 400 e conta na mesma
contenção do login, com 429 além do limite. `POST /api/auth/sair-de-todos` responde 204 e derruba
todos os tokens de quem pediu, inclusive o desta requisição. `GET /api/auth/meus-dados` devolve o
nome, o e-mail de entrada e o perfil de quem pede, que o aplicativo não guarda no aparelho, e
`POST /api/auth/email` troca o e-mail de entrada com a senha atual, que passa pela mesma contenção,
responde 204 e mantém os tokens, que não levam o e-mail; o e-mail que já entra em outra credencial,
de qualquer Conta, responde 409. A identidade traz também a chave Pix e a cidade do QR do Pix
manual, para todo aparelho da Conta, e a marca de que a Conta pode cobrar pelo Pix integrado hoje:
ela não está só leitura, e o provedor está configurado.

## Contrato comum

O contrato de erro é o mesmo em todos os endpoints: toda resposta de erro é Problem Details (RFC
9457), produzida por um tratador transversal em `shared` que estende o do próprio Spring MVC, para o
cliente receber uma forma só venha o erro do framework ou da aplicação. A recusa por perfil vira
403; argumento que o domínio rejeita, como o texto acima do tamanho da coluna, 400, o mesmo da
validação de corpo, que ainda lista a mensagem por campo; estado que o agregado rejeita, 409;
tentativas demais no login, 429 com `Retry-After`; corpo acima do limite da rota, 413; erro
inesperado, 500 sem a mensagem interna; e o não encontrado de cada módulo é traduzido em 404 no
pacote `web` do próprio módulo. O detalhe de toda recusa é texto de tela, em português, com
dinheiro, quantidade e data como o aplicativo os mostra e o nome no lugar do id; o detalhe que o
Spring escreve em inglês para o que recusa antes do controller é trocado por uma frase em português.
Valor monetário viaja como número, e a conversão para `Money` é escrita no controller.

## Cadastro

O cadastro oferece `GET` e `POST /api/produtos`, `GET /api/produtos/inativos`,
`PUT /api/produtos/{id}`, `POST /api/produtos/{id}/inativar` e `POST /api/produtos/{id}/reativar`,
esta só do administrador, que recusa o produto cujo código passou a outro item ativo; para clientes,
`GET` e `POST /api/clientes`, `GET /api/clientes/inativos`, `PUT /api/clientes/{id}`,
`POST /api/clientes/{id}/inativar`, `POST /api/clientes/{id}/reativar` e
`POST /api/clientes/{id}/remover`, esta última só do administrador. O cadastro aceita no corpo um id
gerado no aparelho, opcional: `produtoId` em `POST /api/produtos` e `clienteId` em
`POST /api/clientes`. O reenvio com o mesmo id e o mesmo conteúdo devolve 200 com o mesmo id, sem
gravar outro registro, e o mesmo id com outro conteúdo devolve 409; o gesto que o aparelho guardou
sem resposta leva o mesmo id, e o lote o reconhece do mesmo jeito. A escrita de produto por operador
responde 403; id de outra Conta responde 404.

## Venda

O PDV busca produto em `GET /api/produtos/busca?termo=...` e usa `GET` e `POST /api/vendas` para
listar as vendas de uma SessaoCaixa, cada uma com o valor de cada forma de pagamento, e iniciar uma
comanda; o início aceita `vendaId` no corpo, opcional, e o reenvio com o mesmo id, o mesmo caixa e o
mesmo operador devolve 200 com a mesma venda, sem abrir outra. A lista segue o dono do caixa, como o
extrato: o operador vê no próprio caixa também a venda que o administrador fez nele, e abri-la
continua com quem a fez.

`GET /api/vendas/{id}` devolve itens com a unidade do cadastro, total, parcelas e recebimentos, com
o NSU de cada um em cartão; `POST /api/vendas/{id}/itens` e `DELETE /api/vendas/{id}/itens/{itemId}`
montam a comanda; o lançamento aceita `itemId` no corpo, opcional, e o reenvio com o mesmo id e o
mesmo produto, quantidade e desconto devolve 200 com o mesmo item, enquanto outro conteúdo para o id
recebe 409; SessaoCaixa ou produto que não existe na Conta responde 404 ao iniciar a comanda ou
lançar o item; `PUT /api/vendas/{id}/desconto` é só do administrador;
`POST /api/vendas/{id}/pagamentos` exige `pagamentoId` no corpo, aceita `nsu` no cartão e devolve o
troco; com a forma `PIX`, registra o Pix lançado à mão, confirmado e sem cobrança; o dinheiro sem
`valorRecebido` e o NSU com mais de 40 caracteres, fora do cartão ou ausente no cartão de uma Conta
que o exige respondem 400.

### Pix integrado e aviso do provedor

`POST /api/vendas/{id}/pagamentos/pix` recebe `tentativaId` e valor, devolve parcela PENDENTE e
estado da cobrança, e responde 409, sem alterar nada, ao `tentativaId` que já é de uma parcela de
outra Venda e à tentativa nova da Conta só leitura; a retomada de uma tentativa já reservada não
pergunta pelo só leitura; o PWA mostra QR Code e copia e cola; `GET /api/vendas/{id}` permite
retomar o mesmo QR e não expõe outra Conta; `POST /api/webhooks/pix/{identificador}?hmac=...` recebe
o aviso do PSP sem JWT de operador, só depois de o proxy conferir o certificado de cliente do PSP
num domínio próprio do aviso, identifica a configuração da Conta pelo identificador opaco e segredo,
e reconsulta a cobrança antes de alterar a Venda; o aviso autenticado sem a lista `pix`, como a
notificação de teste do cadastro, responde 204 sem efeito. A mesma rota também atende
`POST /api/webhooks/pix/{identificador}/pix`.

### Conciliação, Cliente, conclusão e comprovante

`GET /api/vendas/conciliacoes-pix` mostra somente ao ADMIN os Pix pagos que chegaram após a
SessaoCaixa fechar ou após a Venda ser cancelada, sem apresentar essas Vendas como concluídas;
`PUT /api/vendas/{id}/cliente` vincula Cliente ativo, e `DELETE /api/vendas/{id}/cliente` o tira da
Venda aberta que ainda não tem parcela FIADO; `POST /api/vendas/{id}/conclusao` e
`POST /api/vendas/{id}/cancelamento` mudam o status; o cancelamento grava o usuário autenticado e o
instante, que a lista do caixa e o detalhe devolvem em `canceladaPor` e `canceladaEm`, ausentes fora
da venda cancelada e na cancelada antes desses campos; no cancelamento com Pix pendente, o servidor
pede remoção ao PSP e reconsulta antes de gravar, respondendo 503 quando não consegue comprovar que
a cobrança parou de aceitar pagamento; repetir um cancelamento de Venda com Pix integrado já
cancelada não repete o efeito; `GET /api/vendas/{id}/comprovante` traz o dado não fiscal para a
tela, com `nomeCliente` só na venda fiada.

## Fiado

Para RF33, `GET /api/fiado/clientes/{clienteId}/saldo` e `GET /api/fiado/dividas` mostram a dívida;
`POST /api/vendas/{id}/recebimentos` exige `recebimentoId` no corpo, aceita `nsu` no cartão, com a
mesma regra da parcela, e registra cada entrada; o reenvio igual devolve 200 com o saldo devedor
atual, e o primeiro envio devolve 201;
`GET /api/vendas/{id}/recebimentos/{recebimentoId}/comprovante` traz seu comprovante.

## Caixa

O caixa oferece `GET` e `POST /api/caixa/sessoes`, `GET /api/caixa/sessoes/aberta`,
`GET /api/caixa/sessoes/abertas`, só do administrador, com todas as sessões abertas da Conta,
`GET /api/caixa/sessoes/{id}`, `POST /api/caixa/sessoes/{id}/sangrias`,
`POST /api/caixa/sessoes/{id}/suprimentos`, ambos com `movimentoId` obrigatório no corpo, e
`POST /api/caixa/sessoes/{id}/fechamento`. A abertura aceita no corpo o `sessaoId` gerado no
aparelho, e repeti-la com o mesmo id e o mesmo valor devolve o mesmo caixa; o gesto de sangria e de
suprimento que chega no lote com o `movimentoId` segue a regra das rotas com rede, e a abertura do
lote com o id de um caixa que já é do mesmo operador conta como aplicada. O histórico recebe o dia
e, opcionalmente, o operador; a consulta por id devolve o extrato, com o `usuarioId` de quem lançou
cada movimento, que pode não ser o dono do caixa: quem concluiu a venda, quem a cancelou, quem
lançou a sangria ou o suprimento, quem recebeu o fiado, e na venda concluída pelo aviso do Pix
integrado, o operador dela. Ele falta no movimento gravado antes desse campo, e as listas carregam
só os totais. As quatro rotas online de dinheiro aceitam o mesmo id e conteúdo sem repetir o efeito;
conteúdo diferente para o id recebe 409, inclusive quando só o NSU muda; o reenvio igual responde
como da primeira vez mesmo que a exigência do NSU tenha ligado depois. Parcela repetida devolve o
troco gravado, sangria e suprimento repetidos devolvem 204.

## Relatórios

O faturamento usa `GET /api/relatorios/faturamento/dia?dia=...` e
`GET /api/relatorios/faturamento?inicio=...&fim=...`; os dois devolvem o período, o total e a
quantidade de vendas, e a rota do período aceita ainda `forma` e `operadorId`, que se combinam. Os
mais vendidos usam `GET /api/relatorios/mais-vendidos?inicio=...&fim=...&limite=...`, com
`operadorId` e `ordem` opcionais, e devolvem as posições com nome, unidade, quantidade e valor, pela
quantidade que saiu ou, com `ordem=VALOR`, pelo valor vendido; sem `ordem`, a rota ordena pela
quantidade, como sempre ordenou, e uma ordem fora da lista recebe 400; o fluxo de caixa usa
`GET /api/relatorios/fluxo-de-caixa?inicio=...&fim=...` e devolve o total de cada tipo de movimento,
as entradas, as saídas e o saldo. A conferência do cartão usa
`GET /api/relatorios/conferencia-do-cartao?dia=...`, com `operadorId` opcional, e devolve os
pagamentos em cartão do dia em ordem de lançamento, cada um com a origem, parcela ou recebimento, a
Venda e a situação dela, o valor, quem lançou e o NSU, que falta na resposta quando não foi
informado. A conferência do Pix usa `GET /api/relatorios/conferencia-do-pix?dia=...`, com
`operadorId` opcional, e devolve cada Pix do dia na mesma ordem, com a origem, a Venda e a situação
dela, o valor, quem lançou, o cliente, que falta quando a Venda não tem, se é integrado e se a
cobrança aguarda confirmação. O resumo mensal usa `GET /api/relatorios/resumo-mensal?mes=AAAA-MM` e
devolve a planilha como anexo, em CSV com ponto e vírgula, vírgula decimal e UTF-8 com a marca de
ordem dos bytes, para o Excel em português abri-la direto; o mês corrente ou futuro recebe 400, com
a frase que a tela mostra, e a recusa sai em Problem Details como nas outras rotas. As rotas de
relatório recusam o OPERADOR com 403 e o período invertido e o dia ausente com 400; operador de
outra Conta dá zero ou lista vazia.

## Usuários

Usuários usam `GET` e `POST /api/usuarios`, `PUT /api/usuarios/{id}/nome`,
`POST /api/usuarios/{id}/inativar`, `POST /api/usuarios/{id}/redefinir-senha` e
`POST /api/usuarios/{id}/anonimizar`; a lista não expõe e-mail, e a inativação do último
administrador responde 409. A inativação responde 200 com `loginRemovido`: falso quando o registro
externo das remoções estava fora do ar e a credencial ficou, o que a lista marca com
`loginGuardado`; o pedido repetido conclui, ou responde 503 com o registro ainda fora. A correção do
nome alcança o ativo e o inativo, sem pedir o plano, e o nome já removido a pedido responde 409. A
redefinição só alcança operador ativo, e o administrador ou o inativo responde 409.

## Configuração da Conta

A configuração usa `GET` e `PUT /api/conta/configuracao`, com `estoqueHabilitado` booleano,
`PUT /api/conta/configuracao/nsu`, com `nsuObrigatorio`, que não depende do plano, e
`PUT /api/conta/configuracao/nome-do-negocio`, com `nomeNegocio`, e
`PUT /api/conta/configuracao/chave-pix`, com `senhaAtual`, `tipo`, `chave` e `cidade`, sem tipo e
com a chave em branco para tirá-la; a chave é conferida pelo tipo, pelo formato e pelos dígitos do
CPF e do CNPJ, inclusive o alfanumérico, antes da senha, e a senha atual errada responde 400 e conta
na contenção do login. As mudanças devolvem a configuração inteira, com o nome do negócio, a chave
Pix e `estoqueComMovimentos`, verdadeiro depois do primeiro movimento de estoque, quando a tentativa
de desligar o controle responde 409. Essas rotas são só do administrador.

## Plano e termos de uso

O plano usa `GET /api/conta/plano`, com o plano, a situação, se a Conta está no teste, a
mensalidade, o que pode ser pedido hoje e o pedido aberto; `POST /api/conta/plano/pedidos`, que
grava o pedido, responde 201 e substitui o aberto; e `POST /api/conta/plano/pedidos/{id}/codigo`,
que aplica o código: o errado responde 400, o de pedido substituído 409, o pedido de outra Conta
404, e o mesmo código de novo não ativa duas vezes. Também só do administrador. O aceite dos termos
usa `POST /api/conta/termos`, com a versão que a tela mostrou: responde 204, aceitar de novo não
grava outra linha, a versão que não é a vigente responde 409, e o operador recebe 403. Na Conta só
leitura, abrir caixa, sangria, suprimento, começar venda, receber fiado e a cobrança Pix nova
respondem 409, com a data e a saída na frase; o pedido de plano gratuito, que um aplicativo
instalado antes ainda pode mandar, também.

## Estoque

O estoque oferece `GET /api/estoque/produtos` para os saldos e mínimos, `GET /api/estoque/baixo`
para o alerta, `POST /api/estoque/produtos/{id}/entradas` para a mercadoria que chegou, com a
quantidade positiva e o motivo opcional, `POST /api/estoque/produtos/{id}/ajustes` para lançar uma
diferença com motivo e `PUT /api/estoque/produtos/{id}/minimo` para o limiar. As cinco rotas exigem
ADMIN e controle ligado; produto de outra Conta recebe 404. A entrada e o ajuste aceitam
`movimentoId` no corpo, opcional: o reenvio com o mesmo id e o mesmo produto, quantidade e motivo
não move o saldo de novo, e o mesmo id com outro conteúdo recebe 409.

## Sincronização

O lote de sincronização usa `POST /api/sincronizacao`, com até 100 operações por envio, cada uma com
o id gerado no dispositivo, o registro, o tipo do gesto, o conteúdo, a versão lida, as dependências
e o instante do balcão. A resposta é 200 com um resultado por operação, na ordem do pedido:
`APLICADA`, `APLICADA_COM_REVISAO` ou `NAO_APLICADA`, com o motivo e a nova revisão do registro
quando ele tem uma, ou `ERRO_TRANSITORIO`, que não é gravado e pode ser reenviado. A revisão traz
também `tiposDaRevisao`, a lista dos tipos dela (`PRECO_DIFERENTE`, `NSU_AUSENTE`,
`ESTOQUE_NEGATIVO`, `RELOGIO_ADIANTADO`), campo que falta nos outros resultados e que o aparelho sem
ele pode ignorar. O lote inteiro recebe 400 quando passa do limite, repete um id de operação ou tem
dependências em ciclo. A lista do administrador usa `GET /api/sincronizacao/revisoes?dia=`, com as
revisões e recusas não conferidas de qualquer dia e as conferidas no dia pedido, e
`POST /api/sincronizacao/revisoes/{operacaoId}/conferencia` registra a conferência, que repetida
mantém a primeira; operador recebe 403, operação aplicada sem pendência 409 e id de outra Conta 404.

## Concorrência

Duas operações que alteram a mesma raiz ao mesmo tempo fazem a segunda responder 409 em qualquer
rota, sem gravar nada; a conclusão da venda repete sozinha, até três tentativas, antes de responder
assim.

## Erros do aplicativo

`POST /api/erros-do-aplicativo` recebe do PWA, com o token, o erro que nenhuma tela tratou: o tipo,
o arquivo, a linha e a coluna do código do aplicativo, a tela e a versão, sem a mensagem; responde
204, e o campo com caractere fora de letras sem acento, dígitos e `._:/@%+-` recebe 400.

## Saúde da aplicação

Fora da API, `GET /actuator/health/liveness` diz se a instância está de pé, sem consultar o banco, e
é o que o deploy e o vigia conferem; `GET /actuator/health` consulta também o banco. Nenhum outro
endpoint do Actuator é exposto.

## Configuração do webhook Pix (RF25, RNF05)

Cada Conta recebedora usa as variáveis de credencial de um PSP só: as da Efí, com prefixo
`ECRX_CAIXA_EFI_<UUID_DA_CONTA_SEM_HIFENS>_`, ou as do Inter, com
`ECRX_CAIXA_INTER_<UUID_DA_CONTA_SEM_HIFENS>_`, mais `WEBHOOK_ID` (identificador opaco exclusivo) e
`WEBHOOK_SECRET` (valor aleatório forte), os dois só com letras, números, hífen e sublinhado, porque
vão na URL. A Conta com as duas fica sem Pix integrado, e cada tentativa registra um erro. A
execução avulsa `cadastrar-webhook-pix` registra no PSP da Conta a URL
`https://{domínio do aviso}/api/webhooks/pix/{identificador}?hmac={segredo}`, com a credencial e o
certificado da própria Conta; na Efí, a URL termina em `&ignorar=`, para o `/pix` que ela acrescenta
não colar no segredo. Nenhum desses valores é enviado pelo navegador.

Cada PSP avisa num subdomínio só dele, em que o proxy exige o certificado de cliente do PSP. O da
Efí confia apenas nas duas autoridades intermediárias do webhook dela, de produção e de homologação,
versionadas em `operacao/vps/efi/`, e não na raiz que elas compartilham, e repassa a impressão
digital no cabeçalho `X-Ecrx-Caixa-Certificado-Efi`. O do Inter confia na autoridade do webhook do
Inter, em `operacao/vps/inter/`, que também emite o certificado de cada cliente da API dele: por
isso o proxy repassa o sujeito no cabeçalho `X-Ecrx-Caixa-Certificado-Inter`, e a aplicação só
aceita o do aviso, com `CN=Webhook` e `OU=webhook`. Cada site do proxy apaga o cabeçalho dos outros,
o domínio do aplicativo, que não pede certificado ao navegador, apaga os dois, e a aplicação, cuja
porta não é publicada, responde 403 ao aviso sem o cabeçalho do PSP da Conta. Os testes exercitam o
callback simulado e a consulta por gateway de teste, o ensaio local prova o proxy com uma autoridade
de teste, e o Inter foi exercitado no ambiente de testes dele.
