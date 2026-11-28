# Funcionalidades do ECRX Caixa

O que cada parte do sistema faz hoje, em detalhe. A visão geral e as telas estão no
[README](README.md); as rotas, em [API.md](API.md); o desenho e o porquê de cada escolha, em
[ARQUITETURA.md](ARQUITETURA.md).

## Sumário

- [Visão geral](#visão-geral)
- [Por módulo](#por-módulo)
- [Sem internet](#sem-internet)
- [O aplicativo](#o-aplicativo)
- [Plano](#plano)
- [Privacidade e termos de uso](#privacidade-e-termos-de-uso)

## Visão geral

O núcleo transacional é construído módulo a módulo, e cada um fecha com a suíte verde antes do
próximo começar. Os nove módulos estão declarados e têm suas fronteiras verificadas desde o primeiro
dia; oito já têm código de negócio dentro. O aplicativo, um PWA em `frontend/`, tem login, sessão no
dispositivo, navegação por perfil e abertura sem rede. As telas de produto, cliente, caixa, Venda,
relatórios, usuários, configuração, estoque, sincronização e plano já operam sobre a API.

## Por módulo

### `shared`

**Estado:** Implementado.

`Money`, `ContaId`, `FusoDeReferencia` e os dois contextos da requisição: `TenantContext`, a conta
em operação, e `UsuarioContext`, quem está operando e com que perfil, com as duas perguntas de
autorização que todo caso de uso restrito faz na primeira linha.

O registro mínimo de remoções é gravado em objetos separados das cópias do banco; sem essa gravação,
a remoção é recusada. Cada texto que vai a uma coluna limitada passa por uma conferência única de
tamanho antes do banco.

O tratamento transversal de erro da API devolve Problem Details: recusa por perfil vira 403, estado
inválido vira 409 e indisponibilidade do registro vira 503.

Os eventos de segurança saem no log numa linha chave=valor, com os ids e a origem e sem dado
pessoal, e o erro inesperado sai com a pilha sem as mensagens; toda resposta 5xx deixa uma linha com
o status, o método e o caminho, e o erro que o aplicativo relata entra numa linha com o tipo, o
local no código, a tela e a versão.

O shell do PWA sai daqui, com o fallback que devolve a mesma página para qualquer rota do cliente e
nunca para a API.

### `contas`

**Estado:** Implementado.

`Conta`, `Usuario`, `Credencial`, login por JWT com contenção de tentativas repetidas, política de
senha, provisionamento de conta, as perguntas que os outros módulos fazem à conta em operação, se o
controle de estoque está ligado e se a Conta está só leitura, e a gestão de usuários: o
administrador cria operador ou administrador com login próprio, lista e inativa pela API e pelo PWA,
com a conta nunca sem administrador.

Inativar apaga a credencial e libera o e-mail; o administrador pode anonimizar o nome depois. Com o
registro externo das remoções fora do ar, a inativação bloqueia o usuário na hora e guarda a
credencial até o administrador concluí-la, e a resposta e a lista dizem que o login ficou.

O administrador corrige o nome de qualquer usuário da Conta, ativo ou inativo, menos o já removido,
e o nome do negócio; cada um vê o próprio nome, e-mail e perfil, sem cópia no aparelho, e troca o
próprio e-mail de entrada informando a senha atual, que conta na mesma contenção do login.

A configuração liga o controle de estoque e só o desliga antes do primeiro movimento, diz se ele já
houve, e liga ou desliga a exigência do NSU no pagamento em cartão; o menu reage imediatamente à
mudança.

O filtro que resolve o token lê o usuário no banco a cada requisição, então inativar vale na
requisição seguinte, e o perfil que vale é o do banco, não o do token. A mesma leitura confere a
versão de acesso que vai no token: cada um troca a própria senha, informando a atual, e os outros
aparelhos dele saem na requisição seguinte; sair de todos os aparelhos derruba também o que pediu; o
administrador redefine a senha de um operador, que sai de todos os aparelhos.

A senha escolhida por outra pessoa, a do seed e a que o administrador define, é provisória: o PWA só
mostra a tela de troca até a pessoa escolher a dela, e o servidor recusa com 403 toda rota da API,
menos a identidade, a troca da própria senha e a saída de todos os aparelhos, para quem definiu a
senha não usar pela API a conta da outra pessoa. A senha tem de 15 caracteres a 72 bytes em UTF-8, e
uma execução avulsa, como o seed, socorre o administrador que perdeu o acesso. Outra suspende na
hora o acesso de uma Conta inteira, por uso ilegal ou ataque ao serviço, e uma terceira o devolve:
suspensa, a Conta não entra, o login com a senha certa diz por quê, todo token dela recebe 401, e
depois do reverso todos entram de novo.

O primeiro login do administrador marca a Conta e publica o primeiro acesso na mesma transação que
copia o catálogo sugerido.

O administrador aceita os termos de uso em nome da Conta antes de qualquer outra tela, e de novo a
cada versão; cada aceite fica gravado com a versão, o instante e quem aceitou, a versão que não é a
vigente é recusada, e o operador nunca é parado por eles.

O pagamento do plano pela própria Conta grava o pedido, confere o código de ativação recalculando a
assinatura do pedido, sem guardá-lo, e mantém o teste e o ciclo mensal com aviso, tolerância de três
dias úteis e só leitura; no só leitura, começar venda, abrir caixa, sangria, suprimento, receber
fiado e cobrar pelo Pix integrado são recusados, pela rota e pelo lote, e o gesto sem rede feito
antes do início do só leitura é aplicado; pedir e aplicar travam a linha da Conta, e a aplicação
repetida não ativa duas vezes.

Quem está autenticado pergunta em `/api/auth/eu` e recebe o próprio nome, o negócio, o tipo dele, se
o estoque está ligado, se o cartão exige o NSU e o plano, com a situação, se a Conta está no teste e
os recursos que valem hoje, além das marcas de senha provisória e de termos pendentes: é o cabeçalho
de toda tela.

### `cadastro`

**Estado:** Implementado, com cadastro local recebido pelo lote.

`Produto` com atributos `JSONB`, busca por nome ou código para o balcão, `Cliente` como vertical
slice, catálogo sugerido por tipo de negócio e copiado no primeiro login do administrador, com o que
a cafeteria prepara na hora sem estoque, e o agregado inteiro: `MovimentoEstoque` como membro, com a
baixa por venda, o estorno por cancelamento e o ajuste manual gravando movimento e saldo na mesma
transação, o estoque mínimo de cada produto e a resposta de quais estão com estoque baixo.

A API expõe a revisão de Produto e Cliente; o PWA guarda listas por Conta e gestos por usuário para
cadastrar, editar, inativar e reativar os dois sem rede, após a preparação online.

Só o administrador remove Cliente: o nome vira `Cliente removido`, o contato sai e o cadastro não
volta às listas; dívida ou comanda aberta impede a remoção. Os dois perfis consultam produtos e
cadastram clientes no balcão. Os gestos do dispositivo chegam pelo lote de sincronização, com o id e
o instante gravados sem rede.

### `caixa`

**Estado:** Implementado, com gestos locais recebidos pelo lote.

`SessaoCaixa`: abertura, sangria, suprimento, fechamento com conferência, histórico por operador e
por dia, consulta da sessão aberta do usuário atual e extrato de uma sessão com a Venda de origem
nos movimentos.

Na sessão escolhida, o PWA soma o vendido por forma de pagamento e lista as vendas dela, abertas,
concluídas e canceladas, com o detalhe só de leitura e a segunda via do comprovante da concluída: o
administrador vê as de qualquer operador.

Quem está com o caixa aberto desde um dia anterior vê um aviso no alto de toda tela, também sem
rede, e o administrador vê no alto da tela Caixa as sessões de outros usuários nessa situação, para
conferir e fechar. Fechado noutro aparelho, esse caixa sai da faixa, e a tela Caixa de quem estava
com ele avisa o fechamento e leva ao dia dele; a tela que recebe a recusa de um caixa já fechado
relê o caixa.

A API e o PWA permitem operar e conferir o caixa, com o esperado visível antes de informar o
contado. Sangria e suprimento online recebem o id do movimento do aparelho e aceitam o reenvio igual
sem lançá-lo de novo.

Sem rede, o PWA guarda os gestos por Conta e usuário, recompõe a sessão após recarga, conta no
esperado o dinheiro das Vendas concluídas no dispositivo e mostra a diferença antes do fechamento.

O dinheiro em espécie de cada venda concluída entra na gaveta pelo evento, na mesma transação da
conclusão e com o instante dela, uma vez só; o cancelamento o estorna. Abertura, sangria, suprimento
e fechamento feitos sem rede chegam pelo lote com o id e os instantes do balcão, e o fechamento
calcula a diferença com o que o servidor conhece.

O caixa é de quem o abriu: o operador só lança, fecha e consulta a própria sessão e só pede o
próprio histórico; o administrador toca qualquer sessão da conta, inclusive para fechar o caixa de
outro operador.

### `pagamentos`

**Estado:** Implementado para cobrança, confirmação e cancelamento Pix.

Strategy por forma: dinheiro com troco, cartão manual, FIADO pendente e Pix integrado. O Pix que o
operador lança à mão, depois de ver o dinheiro no banco, entra por uma porta própria do serviço e
nasce confirmado, como o cartão.

`PixGateway` escolhe, pela configuração da Conta, o adapter da Efí ou o do Inter, para criar,
remover e reconsultar a cobrança pelo `txid`; só o Pix comprovado na consulta quita a parcela
(RF25), e a cobrança nova é recusada na Conta só leitura. Cada adapter também cadastra o webhook da
chave Pix da Conta, por uma execução avulsa, fora da porta. O Inter foi exercitado no ambiente de
testes dele; a Efí ainda não, por falta de credenciais de homologação.

### `vendas`

**Estado:** Implementado, com Venda local recebida pelo lote.

Agregado `Venda`, com `ItemVenda`, `Pagamento` e `Recebimento` como membros. A comanda copia o
preço, permite divisão de formas e desconto pelo ADMIN.

O Cliente ativo pode ser vinculado à Venda, e tirado dela enquanto não há parcela FIADO, porque a
conclusão com FIADO exige Cliente; só o ADMIN registra FIADO e conclui a Venda com sua parcela
pendente. Qualquer perfil recebe a dívida na própria SessaoCaixa, em lançamentos parciais; o saldo
vem das parcelas e dos recebimentos.

O comprovante da Venda mostra o valor pendente e, na venda fiada, o nome do cliente, e cada
recebimento tem comprovante próprio (RF33).

O Pix manual vale em todo plano, com e sem rede, e o PWA mostra ao cliente um QR estático do valor,
montado no aparelho com a chave Pix da Conta, o nome do negócio e a cidade; sem chave cadastrada, o
Pix manual é lançado sem QR.

Confirmação Pix reconsultada conclui uma vez quando a cobertura e a SessaoCaixa permitem; Pix tardio
fica visível para conciliação do ADMIN. O cancelamento recusa o Pix pendente só após remoção
comprovada no PSP e mantém o Pix pago para devolução manual.

Conclusão e cancelamento publicam eventos para caixa e estoque, e recebimento publica evento para
caixa; todos são ouvidos na transação de quem publica. O operador só altera suas Vendas, salvo o
recebimento de fiado da Conta.

A parcela e o recebimento em cartão levam o NSU do comprovante da maquininha, opcional ou exigido
pela Conta, que aparece no detalhe da Venda e não no comprovante.

Sem rede, o PWA monta, recebe e conclui a Venda no dispositivo com as mesmas regras da raiz e
imprime o comprovante marcado como pendente; a Venda aberta com rede espera a rede voltar, e o
cancelamento fica para depois da sincronização. No lote, a Venda chega com os ids da Venda, do item
e da parcela gerados no dispositivo, o preço visto e os instantes do balcão.

### `estoque`

**Estado:** Implementado para o PWA online.

O ouvinte da venda concluída: quando a conta ligou o controle de estoque, cada produto vendido vira
uma baixa, pedida ao cadastro, dono do agregado; serviço no meio dos itens não gera nada, a baixa
acontece na mesma transação da conclusão, e a mesma venda não baixa duas vezes.

E os casos de uso que uma pessoa aciona: a entrada de mercadoria comprada, pela quantidade que
chegou, com motivo opcional para a nota ou o fornecedor, que o histórico separa do estorno de
cancelamento por não apontar para venda; ajuste manual com motivo obrigatório, perda, quebra ou
contagem, com a diferença carregando o sinal; estoque mínimo por produto; e o alerta de estoque
baixo como consulta, a lista dos produtos ativos no mínimo ou abaixo.

A API e o PWA listam saldos e mínimos, registram a entrada, ajustam o saldo, definem o mínimo e
mostram o alerta; a contagem na tela mostra a diferença antes de gravar, e o produto se escolhe pela
lista ou pelo código de barras, digitado, passado no leitor ou lido pela câmera, num campo próprio
que se esvazia a cada leitura.

A Conta com controle desligado recebe 409, e o operador, 403; o estoque continua no só leitura, e a
baixa pela Venda nunca pergunta pelo plano.

O ouvinte da venda cancelada devolve cada produto que a venda baixou, uma vez só, na mesma transação
do cancelamento.

### `relatorios`

**Estado:** Implementado.

Faturamento do dia e de um período, produtos mais vendidos, pela quantidade ou pelo valor, fluxo de
caixa da gaveta, as conferências do cartão e do Pix de um dia e o resumo mensal das vendas, numa
planilha CSV para o negócio analisar ou mandar ao contador (RF67).

Venda concluída com FIADO conta no faturamento no dia da conclusão; recebimento em dinheiro conta no
fluxo no dia da entrada, sem faturar outra vez (RF33). Filtros combináveis de faturamento por forma
e operador incluem a parcela FIADO pendente.

A conferência lista cada pagamento em cartão no dia e na hora em que foi lançado, parcela de venda
de qualquer situação ou fiado recebido, com quem lançou, a situação da venda e o NSU ou a marca de
que falta, para bater com o extrato da operadora. A do Pix faz o mesmo com o extrato do banco: cada
Pix de venda e de fiado recebido, com quem lançou, o cliente, a situação da venda e se foi lançado à
mão ou cobrado pelo Pix integrado, a cobrança ainda não paga marcada e a recusada fora.

O resumo mensal é de um mês já fechado: separa o faturamento em mercadoria e serviço, com o desconto
dado na venda inteira repartido entre os itens pelo subtotal, e traz as formas de pagamento, o fiado
recebido no mês e as vendas canceladas, sem nome de pessoa nem do negócio.

O módulo lê mapeamentos imutáveis de venda, item, produto, pagamento, recebimento, sessão de caixa e
movimento de caixa, e os seis relatórios são do administrador, no teste, nos períodos pagos e no só
leitura.

A API e o PWA mostram os três primeiros para o mesmo período, com o faturamento repartido por forma
de pagamento, e as conferências quando o período é um dia; o filtro de operador vale para o
faturamento, o ranking e as conferências.

Ao fechar um caixa, cada administrador ativo da Conta recebe por e-mail o resumo daquele caixa, com
o faturamento, o total por forma de pagamento, as sangrias, a diferença e os três mais vendidos, sem
nome de pessoa; o envio sai depois do commit do fechamento, numa thread própria, pelo outbox do
Modulith e pela API do Amazon SES no Brasil, e o que falha volta a cada 15 minutos por até 24 horas,
sem nunca desfazer o fechamento.

### `sincronizacao`

**Estado:** Implementado, com envio automático pelo PWA.

Recebe o lote de gestos que o dispositivo registrou sem rede, até 100 por envio, e aplica cada um
pelos casos de uso do módulo dono, que implementa a porta deste: cadastro, caixa e vendas. Uma
transação por operação, com o resultado gravado junto, por Conta e id de operação: o reenvio devolve
o gravado, o mesmo id com outro conteúdo é recusado, e duas requisições simultâneas não aplicam duas
vezes.

Dependência que ainda não chegou pede reenvio; o gesto seguinte de um registro recusado é recusado,
e o de outro registro é tentado.

O item fica com o preço visto no balcão e vai para revisão quando difere do vigente; também vão a
conclusão que deixou o estoque negativo, a parcela em cartão que chega sem o NSU que a Conta exige,
gravada mesmo assim, e o gesto com instante adiantado mais de cinco minutos, seja o da operação ou o
que ele grava no registro.

O administrador lista as revisões e recusas de todos os usuários da Conta e registra quem conferiu e
quando.

Ao editar Cliente, o conteúdo dos gestos gravados antes da edição é esvaziado, e ao removê-lo,
anonimizado; nos dois casos o reenvio antigo entra em conflito.

O PWA descarta o gesto enviado quando todo retrato que o usa já contém o efeito dele.

A revisão traz o tipo, e o aparelho deixa a de estoque negativo para a lista da Conta, porque só o
administrador ajusta estoque; a venda feita sem rede e recusada na volta aparece numa faixa no PDV e
na tela Caixa, com a hora, o valor, o motivo e o dinheiro que ficou fora do saldo esperado.

## Sem internet

### Fila e cópias no aparelho

O PWA guarda uma fila local de gestos no IndexedDB, separada por Conta e usuário, e pede
armazenamento persistente ao ser instalado. O cadastro de Produto, serviço e Cliente já usa sem rede
a cópia local recebida da API e registra cada alteração na fila com UUID estável e revisão de
origem. O catálogo sugerido é copiado no servidor no primeiro login ADMIN e então guardado no
dispositivo; novos itens do balcão não duplicam essa cópia, e a cópia que o login não conseguiu
fazer é refeita na volta do servidor, sem entrar de novo.

### Caixa sem rede

A `SessaoCaixa` também pode ser aberta, movimentada por sangria e suprimento, conferida e fechada
sem rede: cada gesto fica na fila, o saldo esperado e a diferença são calculados no dispositivo, e a
tela indica o que aguarda sincronização.

### Venda sem rede

A Venda também é registrada e concluída sem rede no PDV, em dinheiro, em Pix e em cartão lançados à
mão e, pelo administrador, em fiado de Cliente conhecido no dispositivo; o Pix integrado fica de
fora, porque a cobrança depende do provedor. Antes de gravar cada gesto, o dispositivo confere as
mesmas regras da raiz da Venda, guarda o preço visto e o instante do balcão, soma o dinheiro ao
esperado do caixa e mostra a Venda como pendente de sincronização, com um comprovante que diz isso.

### Envio da fila

O servidor recebe a fila em lote: aplica cada gesto uma vez, na ordem das dependências, com os ids e
os instantes do balcão, e grava o resultado de cada um na mesma transação do efeito, para o reenvio
devolver o que foi gravado em vez de aplicar de novo.

O PWA envia a fila sozinho: ao abrir, na volta do servidor, a cada gesto gravado com ele respondendo
e a cada meio minuto enquanto houver gesto sem resultado, uma rodada por vez entre as abas, que
avisam umas às outras as mudanças da fila, o começo e o fim de cada rodada e o servidor que deixou
de responder ou voltou, sem nenhum dado no aviso; depois de cada envio respondido, relê a
identidade, para a venda sem rede conhecer logo o que a Conta passou a exigir, como o NSU no cartão.

### Desfecho de cada gesto

Cada gesto recebe o seu desfecho, aplicado, em revisão, recusado ou com falha, que é repetida com o
mesmo id. A projeção local deixa de somar de novo o que o retrato do servidor já contém, e o gesto
enviado só sai do aparelho quando todo retrato que o usa já contém o efeito dele: a Venda em
dinheiro continua na gaveta sem rede até o caixa ser relido. A sessão de caixa que o servidor diz
não estar mais aberta deixa de aceitar operação sem rede e aparece fechada, sem os valores do
fechamento até a próxima leitura, enquanto a abertura feita no aparelho e ainda não enviada continua
valendo; a que o próprio aparelho fechou com rede mostra o valor contado e a diferença que o
servidor devolveu, e só a hora espera a leitura.

### Servidor sem resposta

Depois de cada lançamento com rede que mexe na gaveta, o aparelho relê o caixa; se a rede cair antes
da releitura, o saldo esperado aparece como indisponível sem rede e a sangria espera a próxima
leitura, em vez de partir de um saldo antigo. Para o aparelho, o servidor fora com o Wi-Fi ligado
vale como falta de rede: a chamada que falha, que passa de oito segundos sem começar a responder ou
que volta do proxy sem a aplicação põe o aparelho a operar pela fila, o cabeçalho diz que o servidor
não responde, e uma pergunta leve a cada quinze segundos descobre a volta, quando a fila sai e a
tela Caixa relê sozinha. A abertura, a sangria e o suprimento cuja resposta se perdeu vão à fila com
o mesmo id que a chamada levou, e o servidor não os grava de novo. A comanda que existe no servidor
fica só para leitura enquanto ele não responde, e o próximo item começa outra venda no aparelho.
Quando ele volta, a comanda cuja escrita ficou sem resposta é relida antes de alguém repetir: o item
gravado aparece, e a venda que concluiu aparece concluída, com o comprovante.

### O que a tela mostra

O cabeçalho mostra quantos registros faltam enviar e quantos estão em revisão, e a tela de
sincronização mostra uma linha por registro (a venda, o movimento de caixa, o cliente ou o produto),
com a hora, o total, a forma e o detalhe do servidor, os gestos recolhidos e um Conferir que vale
para o registro inteiro. O agrupamento é só da tela: o servidor continua gravando e conferindo por
operação. O administrador confere numa lista só as revisões e recusas de todos os aparelhos da
Conta, inclusive as do próprio, e registra no servidor que as conferiu.

### Sair e apagar os dados do aparelho

Sem rede ou sem resposta do servidor, Sair pergunta antes e diz quantos registros ficam guardados,
porque apaga o token que abre o aplicativo e entrar de novo precisa do servidor; com ele, não
pergunta. A fila de cada usuário só sai quando ele entra, e a tela de sincronização de quem entra
depois conta, sem nome, o que outro usuário deixou por enviar no aparelho. Sair não apaga o que o
aparelho guardou: a tela de entrada tem um link discreto para uma página que apaga a fila e as
cópias locais de todos os usuários. Antes do botão, a página diz o que sai do aparelho, que o já
enviado continua no servidor, quantos registros de quantos usuários ainda não chegaram a ele, na
mesma conta do cabeçalho de cada um, e a revisão ainda não conferida que sai junto. Ela fica fora do
aplicativo porque o aparelho de uma Conta encerrada ou de quem foi inativado não entra mais e o
servidor não o alcança.

### Reenvio com rede

Nas rotas online de recebimento de fiado, parcela, sangria e suprimento, o aparelho envia um UUID
para cada intenção. Se a resposta se perder, a aba conserva esse id e o usa na nova tentativa; o
servidor confirma o mesmo lançamento sem somar dinheiro outra vez e recusa o mesmo id com conteúdo
diferente. O lançamento de item leva o id do mesmo jeito, opcional na rota, e o reenvio, também o
que o navegador repete sozinho, não grava outro item; o id sai quando a comanda mostra o item, e o
mesmo código passado depois disso é mais uma unidade.

## O aplicativo

### Entrega pelo servidor

Fora de `/api` o servidor entrega o PWA, público por natureza: a página, os scripts com hash no
nome, o manifest, o service worker e os ícones, copiados do build do `frontend/` para dentro do jar,
na mesma origem da API, sem CORS. Qualquer rota do cliente pedida direto ao servidor recebe a mesma
página, e o roteador do cliente escolhe a tela; uma rota da API que não existe nunca recebe a
página, e sim 401 sem token e 404 com token. Todo dado sai por `/api`, com token.

### Instalação

O mantenedor confirmou a instalação e abertura do PWA pelo ícone em desktop e tablet real (RNF08).

### Navegação

O aplicativo tem o login, a sessão guardada no dispositivo, o shell com o nome do negócio e de quem
opera, a navegação por perfil e as telas de produtos, clientes, caixa, Venda, fiado, relatórios,
usuários, configuração, estoque, sincronização e plano. A entrada leva ao PDV com o caixa aberto, e
ao caixa sem ele; no tablet e no celular, os itens do balcão ficam à vista e o resto num botão Mais.

### Telas de cadastro

Nas telas de produtos e clientes se listam, se filtram pelo nome, sem diferença de acento, e se
alteram os cadastros, com pares livres de chave e valor para os atributos do produto; a lista de
produtos mostra 50 itens por vez, com a contagem e um botão que mostra mais. No aparelho com câmera,
o código do produto também se lê por ela, no lugar de digitado; com o leitor de código de barras, o
Enter que ele manda no campo do código passa ao campo seguinte, sem salvar o item. Os valores em dinheiro
vão em campos de texto com o teclado decimal do aparelho, que aceitam vírgula e até duas casas. A
sangria ou o suprimento começados no caixa e o item começado em Produtos voltam como ficaram quando
a pessoa vai a outra tela e volta, até registrar, salvar, cancelar, recarregar ou sair, sem nada
gravado no aparelho.

### Tela Caixa

No caixa, quem opera abre a sessão, lança sangria e suprimento e fecha com o saldo esperado à vista
e a diferença apurada, dita como falta, sobra ou sem diferença. O caixa escolhido diz de quem é e
põe a gaveta ao lado do vendido nele por forma de pagamento, com o número de vendas; o extrato, com
o saldo depois de cada movimento, e a lista das vendas vêm recolhidos, em tabela, e a lista abre
logo abaixo da linha o detalhe de cada venda, só para conferir, e reimprime o comprovante da
concluída como segunda via, com a data e a hora da reimpressão, também sem rede. Para o
administrador, cada movimento do extrato diz por quem foi lançado, e a venda cancelada diz por quem
e a que horas, o controle do dono sobre o cancelamento que o operador faz no próprio caixa. O
fechamento lista as vendas abertas com o que já receberam, porque o dinheiro delas fica fora do
esperado e depois do fechamento elas só se cancelam, e a pergunta antes de fechar lembra delas. O
administrador que abre o caixa de outro operador, aberto hoje, lê que ele está em uso antes de
lançar ou fechar, e o histórico do dia mostra de cada caixa a abertura, o fechamento, o valor
inicial e a diferença. O caixa aberto num dia anterior ao de hoje ganha um aviso no alto de toda
tela para quem está com ele, e o administrador vê no alto da tela Caixa os de outros usuários, para
fechá-los. O valor inicial nasce vazio e é obrigatório, mesmo quando é zero.

### Tela Venda

No PDV, a busca aceita nome, sem diferença de acento nem de maiúscula, código e multiplicador de
quantidade, e o Enter do leitor de código de barras lança o produto do código exato ou o resultado
único; os resultados trazem primeiro esse produto e depois os nomes que começam pelo termo, com o
preço pela unidade de venda, e param nos 20 primeiros, com o total quando há mais. No aparelho com
câmera, um código de barras desenhado dentro do campo da busca abre uma janela que lê um código por
toque, das embalagens (EAN-13, EAN-8, UPC-A e UPC-E) ou de etiqueta (Code 128), avisa com um bipe e
uma vibração onde o aparelho deixa, e o código lido entra como o Enter do leitor, com a quantidade e o
desconto da tela, também sem rede; o QR Code não é lido, para um QR de Pix ou de site não virar busca.
A câmera de notebook, de foco fixo, costuma não ler o código de embalagem, e a janela avisa disso
quando a câmera aberta não é a traseira, indicando o leitor de código de barras ou o celular. A
quantidade sai
com vírgula e unidade, como 0,35 kg, na comanda e no comprovante, que aparece logo abaixo da comanda
concluída. Quando a quantidade não é 1, a linha do item na comanda mostra também o preço da unidade,
no formato da busca. A comanda admite pagamento dividido, e o total, o que falta lançar e o botão de
concluir ficam num rodapé preso ao fim da tela. Com desconto da venda, a soma dos itens e o desconto
aparecem logo abaixo dos itens, como no comprovante. A lista das vendas do caixa mostra as dez mais
recentes, com a hora e as formas de pagamento, e a venda que ficou aberta no caixa é avisada na
comanda, com o valor, a hora e um botão para retomá-la. No celular, a tela de venda deixa no menu só
a venda e o Mais, para a busca ficar no alto da tela. O troco aparece em destaque, com uma prévia
enquanto se digita o valor recebido e botões das notas mais comuns que o preenchem. Uma venda
simples em dinheiro fecha em até seis toques. Cancelar a venda e fechar o caixa, que não se
desfazem, pedem confirmação com o valor e a consequência, como as inativações e remoções do
cadastro: num diálogo do próprio aplicativo, e não no do navegador, com o verbo da ação no botão e o
foco em Voltar, para o Enter do leitor de código não confirmar.

No cartão, o PDV e a tela Fiado pedem o NSU do comprovante da maquininha, opcional ou obrigatório
conforme a Conta; o aparelho sabe da exigência pela identidade e recusa sem rede a parcela que viria
sem ele, e a lista de parcelas da comanda mostra o número gravado. O comprovante usa a impressão do
navegador e o compartilhamento do dispositivo quando disponível; o troco só aparece na venda com
dinheiro, e o desconto da venda só quando houve.

### Tela Fiado

Na Venda, o Cliente ativo, achado pelo nome com a ordem e o limite da busca de produto, aparece na
faixa de contexto do shell com seu saldo devedor e pode ser tirado enquanto não há parcela fiado; a
tela Fiado lista quem deve, com o total por cliente e as vendas dele, com a mesma busca pelo nome, e
registra recebimentos parciais na SessaoCaixa aberta de quem recebe. O comprovante da Venda indica o
valor pendente e cada recebimento pode ser impresso ou compartilhado.

### Tela Relatórios

Os relatórios abrem no dia do balcão e consultam um período escolhido pelo administrador, pelas
datas ou pelos atalhos Hoje, Ontem, 7 dias e Este mês; o período e o operador consultados aparecem
uma vez, acima dos resultados, e a consulta nova deixa os anteriores esmaecidos até chegar. Em cima
ficam os resumos, um cartão para cada: o faturamento, com o total, o ticket médio e uma tabela por
forma de pagamento, que somam o total; o fluxo de caixa da gaveta, com entradas, saídas e saldo; e
os dez que mais faturaram, numa tabela com a quantidade, a unidade e o valor. Quando a Conta tem
mais de um usuário, um seletor de operador, que lista também os inativos, filtra o faturamento e o
ranking; o fluxo de caixa é da gaveta inteira e avisa isso logo abaixo do título. Com o início igual
ao fim, que é como a tela abre, as conferências do cartão e do Pix do dia vêm embaixo, lado a lado
no tablet e no desktop, filtradas pelo mesmo operador, cada uma numa tabela que rola por dentro, com
o total no topo; com um período maior, uma linha diz que elas são de um dia só. A do cartão tem a
hora do balcão, o valor, o NSU ou a marca de que falta e quem lançou, um filtro só dos sem NSU e uma
frase que explica por que o total difere do faturamento em cartão. A do Pix tem o cliente pelo
cadastro do aparelho, o Pix integrado marcado e a cobrança que aguarda confirmação fora do total. A
origem e a situação da venda aparecem como marcas curtas. Logo abaixo do período, o cartão Resumo
mensal das vendas lista os doze meses que já terminaram, com o último escolhido, e baixa a planilha
do mês, para o negócio analisar ou mandar ao contador; sem rede, ele diz que o resumo precisa dela.

### Primeiros passos e ajuda

Três cartões de primeiros passos (abrir o caixa, vender e fechar) aparecem no alto da tela Caixa,
aonde a entrada leva quem está sem caixa aberto, até a pessoa pular ou fechar o primeiro caixa. Cada
passo conta como feito pelo estado do caixa dela, e o cartão abre no primeiro que falta; o menu os
reabre inteiros. Cada tela tem um botão de ajuda ao lado do título, com até três linhas, e a entrada
diz a quem pedir a senha esquecida.

### Tela Configuração

A tela Configuração liga e desliga a exigência do NSU e guarda a mudança na identidade do aparelho;
depois do primeiro movimento de estoque, ela diz que o controle não desliga mais e não oferece o
botão.

### Sessão e cliente HTTP

Um cliente HTTP só envia o token da sessão aberta nesta aba, troca-o pelo renovado quando a resposta
pertence à mesma sessão e traduz todo erro no mesmo objeto, lido do Problem Details. Toda chamada
tem tempo-limite: oito segundos, ou 45 nas que esperam o provedor do Pix, a consulta de senhas
vazadas ou o lote da fila. Respostas de uma Conta anterior são descartadas depois de uma troca de
sessão, inclusive entre abas. Com token guardado e ainda válido, o aplicativo abre sem rede no
shell, com a identidade guardada, e pergunta ao servidor quem está operando assim que a rede volta.

### Atualização

A versão nova é procurada ao abrir o aplicativo, ao voltar a ele e de hora em hora, para alcançar o
tablet que passa o dia aberto no balcão; ela avisa e espera o operador mandar atualizar. Com uma
venda aberta na tela, o aviso fica sem o botão até a venda sair dela, porque a recarga fecharia a
comanda e perderia o que foi digitado e ainda não lançado.

## Plano

O plano é um só, e a Conta nasce com 14 dias de teste dele, com tudo o que ele inclui. O pagamento
passa pela tela Plano, sem ninguém agir dentro da Conta: o administrador pede, manda o texto do
pedido pelo canal de atendimento e paga por Pix; depois de conferir o pagamento, o mantenedor
entrega um código que o registra. O fim do teste é o primeiro vencimento, e o plano vence todo mês
nesse dia, com aviso ao administrador antes. Depois de um vencimento sem pagamento há três dias
úteis de tolerância; no dia seguinte a Conta fica só leitura: não começa venda, não abre caixa e não
mexe no dinheiro dele, mas fecha o caixa aberto, consulta os relatórios e mantém o cadastro, até o
código do pagamento.

## Privacidade e termos de uso

O [aviso de privacidade](frontend/src/telas/TelaDePrivacidade.tsx) é público, inclusive antes do
login, e o rodapé de toda tela do aplicativo aponta para ele e para os termos. Diz quem trata cada
dado e com que papel, para que serve e com que base, onde fica e por quanto tempo, inclusive no
aparelho, com quem é compartilhado, os registros de segurança como tratamento próprio por legítimo
interesse, e a quem pedir cada direito do art. 18 da LGPD, com o prazo. O ADMIN pede o encerramento
da Conta pelo canal indicado nele. Os [termos de uso](frontend/src/telas/TelaDosTermos.tsx), com o
anexo de tratamento de dados, também são públicos: dizem o que o ECRX Caixa faz com os dados que a
Conta registra, onde eles ficam, o aviso de incidente e o fim do contrato, e o ADMIN os aceita antes
de usar o aplicativo. Uma versão nova dos termos chega com uma versão nova do aplicativo, e a tela
do aceite oferece atualizá-lo ali mesmo, porque o aplicativo instalado continua na versão antiga até
alguém pedir. O procedimento em [`operacao/exclusao/`](operacao/exclusao/) remove as linhas da Conta
e exige a retirada dos segredos da Conta; o registro externo das remoções é reaplicado quando uma
cópia do banco é restaurada.
