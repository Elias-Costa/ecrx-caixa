# Arquitetura do ECRX Caixa

O desenho do sistema e o porquê de cada escolha: módulos, decisões estruturais, segurança, modelo de
dados, testes e implantação. A visão geral está no [README](README.md); o comportamento de cada
módulo, em [FUNCIONALIDADES.md](FUNCIONALIDADES.md); as rotas, em [API.md](API.md).

## Sumário

- [Módulos](#módulos)
  - [Camadas dentro do módulo](#camadas-dentro-do-módulo)
  - [Perguntas, eventos e ciclos](#perguntas-eventos-e-ciclos)
  - [Fronteira antes do código](#fronteira-antes-do-código)
  - [Padrões aplicados](#padrões-aplicados)
- [Decisões estruturais](#decisões-estruturais)
- [Segurança e isolamento](#segurança-e-isolamento)
- [Modelo de dados](#modelo-de-dados)
  - [Agregados](#agregados)
  - [Detalhes de modelagem que valem nota](#detalhes-de-modelagem-que-valem-nota)
- [Qualidade e testes](#qualidade-e-testes)
- [Implantação](#implantação)
- [Guia de leitura do código](#guia-de-leitura-do-código)

## Módulos

**Monolito modular, não microsserviços.** Cada módulo é um Bounded Context do DDD e um pacote
direto abaixo de `br.com.caixasimples`; o pacote e o artefato do Maven guardam o nome de trabalho do
projeto, Caixa Simples. As fronteiras são verificadas em teste pelo Spring Modulith: quebrar a
fronteira quebra o build. A responsabilidade de cada módulo está no [README](README.md#arquitetura), e o que cada um faz,
em detalhe, em [FUNCIONALIDADES.md](FUNCIONALIDADES.md).

### Camadas dentro do módulo

```
<modulo>/
├── domain/        regra de negócio pura, sem Spring e sem Jakarta
├── application/   casos de uso, transação, orquestração
├── web/           controllers REST
└── internal/      JPA, adapters e configuração, invisível aos outros módulos
```

Nem todo módulo tem as quatro: uma camada nasce quando há o que colocar nela. Hoje `contas`,
`cadastro`, `caixa`, `vendas`, `estoque` e `relatorios` têm `web/` com os endpoints que suas telas
consomem, `sincronizacao` tem o do lote e o da lista de revisões, e `shared` tem `web/` com o que é transversal, o
tratamento de erro e a entrega do aplicativo; `cadastro` acomoda `Cliente` inteiro em `internal/`,
porque um slice sem invariante não precisa de `domain/`; `relatorios` não tem `domain/` porque
não tem regra a proteger: ele lê colunas, soma e lista, e o rateio do desconto no resumo mensal é
uma conta do caso de uso, sem estado; e `sincronizacao` também não tem, porque a regra
de cada gesto é do módulo dono: ele guarda a ordem, a transação e o registro do resultado.

A autorização por perfil mora na camada `application/`, como a primeira linha de cada caso de uso
restrito, e lê quem chama de um contexto em `shared`, preenchido pelo mesmo filtro que resolve o
tenant. Não é anotação nem tabela de rotas: quem lê o caso de uso vê quem pode chamá-lo, e a regra
do próprio caixa precisa saber quem chama de qualquer modo. Nenhum caso de uso recebe perfil ou operador por parâmetro; um ouvinte
de evento, que roda sem usuário, só chama caso de uso que não pergunta.

### Perguntas, eventos e ciclos

Um módulo nunca importa de `internal/` de outro. Efeito colateral entre módulos é Domain Event;
consulta é chamada direta à API pública do pacote. A regra que resume as duas: *eventos anunciam
fatos, chamadas diretas fazem perguntas.* O Spring Modulith só expõe o pacote-base de cada módulo,
então a camada `application/` é exposta nomeadamente onde outro módulo precisa perguntar algo, e
`domain/` e `internal/` seguem ocultos: `vendas` copia o preço do produto, e pede o nome dele para
o comprovante, recebendo um record da camada de aplicação de `cadastro`, nunca a raiz de agregado
alheia, e pergunta à camada de aplicação de `contas` se a Conta exige o NSU no cartão. A exceção é `pagamentos`, que
expõe também o `domain/`: ele não tem agregado, e o que mora lá é a interface do Strategy e os dois
records imutáveis que são o contrato de pagar, o pedido e o resultado, sem os quais ninguém
consegue chamar o serviço de pagamento.

A verificação de fronteiras também recusa **ciclo** entre módulos, e ouvir um evento é depender de
quem o publica. Como o caixa ouve a venda concluída, a venda não pode chamar o caixa: a única
pergunta que ela faz, se a sessão está aberta, passa por uma interface declarada em `vendas` e
implementada em `caixa/internal`. A dependência entre os dois fica num sentido só, e o teste de
arquitetura garante isso por compilação, não por revisão.

Os listeners de evento moram em `internal/`, por serem adapters de entrada, e todos rodam dentro
da transação de quem publica. Os da venda concluída gravam a Venda, o dinheiro na gaveta e a baixa
de estoque juntos, e a sangria que vem depois, no mesmo lote enviado pelo dispositivo, já encontra
esse dinheiro. Os da venda cancelada e do fiado recebido fazem o mesmo com o estorno, a devolução
ao estoque e o recebimento. A sessão de caixa e o produto têm versão: se outra operação alterou a
mesma raiz no meio, a operação inteira é recusada com 409 e quem operou repete; depois do commit,
a mesma recusa derrubaria só o efeito, com a venda já cancelada. A conclusão com rede repete
sozinha, numa transação nova, porque a recusa desfez tudo e duas vendas do mesmo produto em caixas
diferentes são as duas válidas; o lote do aparelho recebe a recusa como erro transitório e reenvia.
Cada listener confere que o evento é da Conta da transação. Os da conclusão e do cancelamento declaram a ordem em que rodam, o caixa
antes do estoque, e o estoque grava os produtos em ordem de id: duas transações que disputam os
mesmos registros param no primeiro deles, e a segunda recebe o 409, em vez de cada uma segurar um
registro e esperar pelo outro até o banco derrubar uma delas. O primeiro acesso da Conta
também tem um ouvinte síncrono: marca e catálogo são gravados juntos, antes de o login responder.

A sincronização em lote segue o mesmo desenho de dependência num sentido só. O módulo
`sincronizacao` declara a porta `AplicadorDeOperacoes`, e `cadastro`, `caixa` e `vendas` a
implementam no próprio `internal/`, chamando os próprios casos de uso: nenhum deles expõe a camada
de aplicação por isso, e o módulo novo depende só de `shared`.

O mesmo evento tem dois ouvintes, e o segundo mostra o outro lado da regra de ciclo. O estoque
reage à venda concluída, mas o agregado que ele move, o produto com seu saldo, é do cadastro, e o
cadastro não pode ouvir o evento sem fechar um ciclo com a venda, que já lhe pergunta o preço. A
saída foi separar quem decide de quem executa: o módulo de estoque guarda a política, se a conta
ligou o controle e o que fazer com cada item, e pede a baixa ao cadastro pela camada de aplicação
dele. Ninguém depende de `estoque`, e é isso que permite a ele depender de três módulos.

### Fronteira antes do código

Todo módulo declarou a fronteira antes de ter código, e o `ModularityTests` a verificava desde
então. Foi assim que a primeira classe de `pagamentos` nasceu dentro de um limite que já existia,
em vez de criar o limite depois do código; `estoque` nasceu do mesmo jeito, com o ouvinte da venda
concluída entrando num pacote cuja fronteira já era testada; e `relatorios`, o último, nasceu com
o faturamento dentro de uma fronteira que já dizia que ele só lê. `estoque` não tem `domain/` nem
tabela própria, e não é omissão: ele é política, a conta participa ou não, e o agregado que ele
move é do cadastro. Os ouvintes em `internal/` e os casos de uso em `application/` decidem e
pedem; quem executa é o dono do agregado. `relatorios` também não tem `domain/` nem tabela: ele
lê as tabelas dos outros por mapeamentos próprios, toma dos módulos donos só os enums do
pacote-base, e ninguém depende dele. `sincronizacao` também não tem `domain/`: a regra de cada
gesto é do módulo dono, e são os donos que dependem dele, pela porta que ele declara. O
`frontend/` fica na raiz, e não dentro de `src/`, para
quem lê o repositório ver que existe um aplicativo sem entrar na árvore Java; o Maven o compila
e testa na fase de empacotamento e copia o resultado para dentro do jar, então a suíte Java roda
sem Node instalado e o artefato de produção é um só.

### Padrões aplicados

Nenhum padrão entra por simetria, e nenhum entra antes do problema que o justifica. O que está em
uso hoje:

- **Repository apenas por raiz de agregado.** Não existe `MovimentoCaixaRepository`,
  `ItemVendaRepository` nem `PagamentoRepository`: membro de agregado entra e sai pela raiz.
- **Strategy** por forma de pagamento, resolvido por mapa e sem `switch` em código de negócio:
  dinheiro, Pix integrado, cartão e FIADO têm uma classe cada. A parcela Pix integrada nasce
  PENDENTE; o Pix lançado à mão não passa pela estratégia, e sim por uma porta própria do serviço,
  que o confirma na hora.
- **Adapter** de Pix atrás de `PixGateway`, um por PSP, com a escolha pela configuração da Conta
  numa implementação da porta que repassa ao adapter dela: Efí e Inter usam OAuth e certificado por
  Conta, criam a cobrança com `txid` derivado do UUID da parcela e comparam os dados do PSP antes de
  mostrar o copia e cola ou confirmar um Pix recebido. A criação e a remoção seguem a API Pix padrão
  do Banco Central, numa classe que os dois usam. O Inter guarda o token por Conta, porque limita os
  tokens por minuto, e decide o pago pela lista de Pix recebidos do `txid`, porque no ambiente de
  testes dele a cobrança paga continua ativa. Ao cancelar, o servidor pede a remoção da cobrança
  ainda pendente e reconsulta antes de recusar a parcela; o PWA não reapresenta QR de cobrança
  removida. Pix pago continua confirmado e requer devolução fora do sistema.
  O webhook só fornece a correlação para a reconsulta.
- **Value object** para dinheiro, com o arredondamento visível em quem o chama.
- **Domain Events** para a venda concluída e a cancelada avisarem o caixa e o estoque sem acoplar
  os três, e para o fiado recebido avisar o caixa. Esses são ouvidos dentro da transação de quem
  publica: a Venda, o dinheiro na gaveta e a baixa confirmam juntos, e uma falha em qualquer um
  deles, inclusive o conflito de versão com outra operação na mesma sessão de caixa ou no mesmo
  produto, desfaz a operação inteira em vez de deixá-la sem efeito. Cada evento carrega o fato
  inteiro, itens e parcelas, e quem decide o que fazer com ele é quem ouve: o caixa lê as
  parcelas, o estoque lê os itens. O cancelamento é o espelho da conclusão, com dois ouvintes que
  desfazem o que os dois primeiros fizeram, lançando o movimento oposto em vez de apagar o
  original.
- **Outbox com ouvinte assíncrono** para o único efeito que não pode derrubar a operação: o resumo
  do caixa por e-mail. O fechamento publica um evento que fica gravado na tabela do outbox na mesma
  transação, e o ouvinte roda depois do commit, numa thread própria: põe a Conta do evento no
  contexto antes de abrir a transação, porque o Hibernate prende a sessão à Conta que vale na
  abertura, lê o resumo numa transação só de leitura e envia fora dela. A entrega que falha, ou que
  um reinício deixou no meio, volta por uma tarefa agendada, que só existe com servidor web, para
  as execuções avulsas terminarem; passadas 24 horas, o ouvinte desiste com uma linha de erro.
- **Evento síncrono no primeiro acesso da Conta** para o cadastro copiar o catálogo sugerido na
  mesma transação que grava a marca. Uma falha na cópia desfaz a marca, e dois logins simultâneos
  são serializados pela linha da Conta.
- **Dependency inversion entre módulos** onde uma pergunta e um evento cruzariam em sentidos
  opostos: a interface mora em quem pergunta, a implementação em quem responde.
- **Vertical slice** onde não há invariante a proteger, em vez de agregado por simetria.
- **Modelo de leitura próprio no módulo que só lê.** Os relatórios não remontam agregado nem
  importam a entidade de outro módulo: mapeiam a mesma tabela uma segunda vez, como entidade
  imutável com só as colunas que a consulta usa, e o repositório herda do marcador do Spring Data
  em vez do completo, sem `save` nem `delete`. O só-leitura fica garantido por construção, em três
  pontos, e não por revisão. A soma é feita no banco, em JPQL, que o filtro de conta alcança; o
  ranking junta três desses mapeamentos por id, na própria consulta, sem associação navegável, e
  arredonda cada item no banco do mesmo jeito que o domínio arredonda no comprovante. Os filtros
  combináveis entram nessas mesmas consultas como condição que some quando o parâmetro é nulo,
  e o filtro por forma de pagamento é uma consulta própria, que soma parcelas em vez de vendas.
- **Fitness function de arquitetura**, que transforma a regra de fronteira em teste.
- **Fila local no PWA** com UUID por gesto, dependências e estados explícitos no IndexedDB. A
  gravação termina antes de devolver o gesto à tela, e a leitura usa a Conta e o usuário da sessão
  guardada; um novo login dos mesmos recupera os gestos. Os retratos do catálogo e de Cliente
  pertencem à Conta, e só os gestos do usuário atual aparecem sobre eles. Cada gesto guarda de
  quais outros depende: a Venda depende da abertura do caixa e do Produto criado no dispositivo, e
  os gestos que mudam o esperado da gaveta formam uma fila por sessão, para que a sangria nunca
  chegue ao servidor antes da Venda em dinheiro que a tornou possível. O envio sai sozinho, uma
  rodada por vez com a trava do navegador entre as abas, e cada resultado recebe um número de
  ordem; comparado ao número guardado com cada retrato do servidor, ele impede a projeção local de
  somar de novo o que o servidor já contém.
- **Idempotência por operação** na sincronização: o resultado de cada gesto é gravado na mesma
  transação do efeito, com índice único pela Conta e pelo id da operação. O reenvio devolve o
  gravado, o mesmo id com outro conteúdo não é aplicado, e duas requisições com a mesma operação
  não aplicam duas vezes, porque o índice recusa a segunda e o efeito dela sai junto.

Um padrão considerado e deixado de fora: **Specification**, para os filtros combináveis dos
relatórios. O executor que o Spring Data oferece traz `update` e `delete` para dentro de um
repositório que não pode escrever, e não agrega, enquanto as consultas dos relatórios são somas;
os filtros entraram como parâmetro opcional nas próprias consultas.

## Decisões estruturais

- **Multi-tenancy por coluna.** Toda entidade de negócio tem `contaId` anotado com `@TenantId`, e o
  Hibernate aplica o filtro sozinho. Três exceções deliberadas: `ModeloProduto` (dado de referência
  da plataforma), `Conta` (o `id` dela *é* o tenant) e `Credencial` (consultada no login, antes de
  existir tenant). A tabela do outbox de eventos é do framework, não do negócio, e por isso fica
  fora da lista: a conta a que cada evento se refere viaja dentro dele.
- **Quem chama também é contexto, nunca parâmetro.** Ao lado do tenant, a requisição carrega o
  usuário e o perfil dele, lidos do banco a cada requisição e não do token, para que inativar
  alguém valha na hora. A verificação é uma chamada explícita na primeira linha do caso de uso
  restrito: o administrador faz tudo em qualquer caixa da conta; o operador só vende, cadastra
  cliente e opera o próprio caixa. O caixa e a venda são de quem os abriu.
- **Chave primária é UUID gerado na aplicação**, nunca auto-incremento. É o que permite criar um
  registro offline com identidade definitiva e sincronizar depois sem renumerar nada, tornando o
  reenvio de uma operação naturalmente idempotente.
- **O schema pertence ao Flyway.** O Hibernate roda com `ddl-auto: validate` e apenas confere se
  bate. Migration já publicada é imutável: correção é sempre versão nova, como a `V6` é para a `V5`,
  e como a `V7` acrescenta a `movimento_caixa` a chave estrangeira que a `V5` não podia criar,
  porque a tabela de venda ainda não existia. Vale até para tabela que não é do projeto: a do
  outbox do Modulith, na `V8`, foi gerada a partir da entidade do framework, e a suíte prova que o
  gerado bate, porque o contexto não subiria se não batesse.
- **Repositório só para raiz de agregado.** Membro de agregado (item de venda, pagamento,
  recebimento, movimento de caixa ou de estoque) entra e sai pela raiz, o que impede alterar um item sem recalcular o total
  que a raiz garante.
- **Soft delete**, nunca exclusão física, para preservar o histórico de vendas antigas.
- **Dinheiro é um value object `Money`**, `numeric(12,2)` no banco, com arredondamento a duas casas
  visível no ponto de uso. Nada de `double`.
- **O dia é o do balcão.** Timestamps gravados em UTC; a fronteira do dia vem de uma constante única
  da aplicação. Sem isso, todo caixa aberto depois das 21h cairia no dia seguinte do relatório, e
  nada denunciaria o erro.
- **DDD onde existe invariante para proteger.** `SessaoCaixa`, `Produto` e `Venda` têm domínio
  rico. `Cliente`, que não tem invariante, é um vertical slice em um arquivo só. A régua: se
  acrescentar um campo exigir tocar em mais de três ou quatro arquivos, é cerimônia demais.
- **Regra de negócio entra com o caso de uso, não com a tabela.** A montagem da comanda trouxe as
  regras do total; a conclusão trouxe as dos pagamentos; o cancelamento trouxe o estado final;
  o fiado trouxe o vínculo de Cliente e recebimentos pela raiz da Venda. Testes que precisam de um
  estado específico usam o mesmo método que a entidade usa para remontar o agregado do banco.
- **Concluir é um passo, não um efeito.** Registrar a parcela que fecha a conta não conclui a
  venda; quem finaliza chama a operação que diz isso. Um método de registrar pagamento que às vezes
  mudasse o status seria comportamento escondido no nome, e o evento de venda concluída nasce de
  um ponto só. Concluir exige o caixa em que a venda nasceu ainda aberto, porque é nele que o
  dinheiro entra; registrar parcela não exige, porque parcela não mexe na gaveta.
- **No caixa entra só o dinheiro.** O esperado da sessão é o que deveria haver na gaveta, e é
  contra ele que o operador confere o que contou. Pix e cartão nunca estiveram lá: uma venda paga
  sem dinheiro não gera movimento nenhum, e uma venda dividida lança só a parte em espécie.
- **Cancelar desfaz pelo oposto, e só com o caixa aberto.** Uma venda concluída cancelada
  devolve ao estoque o que levou e à gaveta o que trouxe, com um movimento novo de cada lado, e
  nunca apagando o original: o histórico continua contando que entrou e saiu. O estorno no caixa
  espelha o valor da venda, e é a sessão que sabe quanto foi, não quem cancela. Uma venda
  concluída só cancela com a sessão em que nasceu ainda aberta, porque a gaveta de uma sessão
  fechada já foi conferida; a comanda aberta sem cobrança Pix integrada cancela sem perguntar,
  porque nunca tocou a gaveta, e é a saída para a comanda que ficou presa quando o caixa fechou
  antes. Essa comanda não recebe mais item nem pagamento no balcão, com rede ou sem, para a recusa
  chegar antes do troco; o que o aparelho já tinha registrado sem rede entra pelo lote, e a
  conclusão recusada leva a venda à revisão. Pix lançado à mão e cartão confirmados ficam como estão: não há provedor a quem pedir
  estorno, e a devolução ao cliente acontece no balcão. Uma Venda com Pix integrado pendente só
  cancela após o PSP comprovar que a cobrança foi removida; falha ou resposta incerta mantém a
  Venda e permite repetir. Se o Pix já foi pago, a parcela segue CONFIRMADO e o PWA avisa que a
  devolução é manual e ainda exige conciliação. O estorno pode deixar o esperado negativo se houve
  sangria no meio, pelo mesmo motivo do saldo de estoque negativo: o cancelamento já aconteceu,
  e recusar só deixaria o caixa sem refletir o fato.
- **O preço nunca vem de quem chama.** Lançar um item recebe o id do produto, e o caso de uso
  consulta o preço vigente no cadastro na hora de gravar. Um preço vindo do pedido seria uma porta
  para vender por qualquer valor; a cópia feita ali é o que impede uma venda passada de mudar quando
  o produto é reajustado. A exceção é o item da Venda registrada sem rede: o cliente já pagou o
  preço que o operador viu, e ele fica; a divergência do vigente vai para revisão do
  administrador, nunca é aceita em silêncio.
- **Sincronizar é aplicar fatos em ordem, uma vez.** Venda, parcela, movimento de caixa e
  fechamento chegados do dispositivo são fatos, e não campos a sobrescrever: entram se a raiz aceita
  contra o estado que o servidor tem ao recebê-los. Cadastro de produto e cliente segue a última
  escrita aceita. Os instantes gravados são os do balcão, porque é deles que depende o dia do
  faturamento e do fluxo de caixa; o relógio adiantado vai para revisão, em vez de mudar o dia em
  silêncio, e o servidor confere tanto o instante da operação quanto o que cada gesto grava.
- **Toda alteração da Venda espera a anterior.** A linha da Venda não tem versão e grava o estado
  inteiro da raiz, então quem altera a comanda, com rede ou pelo lote, a lê com a trava da linha:
  a segunda escrita espera a primeira confirmar e decide sobre o que ela deixou. Duas adições
  valem as duas, e a edição que chega durante a conclusão é recusada, em vez de reabrir a venda
  com o dinheiro já na gaveta. As consultas leem sem trava e num único instante: a linha da venda
  e os membros vêm de consultas SQL separadas, e a escrita que confirmasse entre elas faria a raiz
  recusar a remontagem, com o dado gravado certo. Um teste de arquitetura recusa escrita que leia a
  Venda sem a trava e consulta que não leia num único instante.
- **O saldo de estoque pode ficar negativo.** A venda que levou mais do que o saldo registrava já
  aconteceu no balcão; recusar a baixa não a desfaria, só impediria de registrar a venda que
  aconteceu ou deixaria o estoque mentindo por omissão. O saldo negativo é o fato a corrigir, por um ajuste de contagem, e
  é o que o alerta de estoque baixo expõe. O controle nasce desligado por conta, e ligar não
  conta o que já está na prateleira: a contagem inicial é um ajuste manual.
- **O limiar de estoque baixo é por produto, e nasce em zero.** Baixo depende do item: dois quilos
  de queijo e duas garrafas de água não são o mesmo baixo. Sem número configurado, o alerta avisa
  quando o item acabou, zero ou negativo; quem informa um mínimo maior é avisado antes. Nunca há
  produto que não alerta, e por isso a coluna não é anulável. O alerta é uma consulta, a lista dos
  produtos ativos no mínimo ou abaixo, e não um evento: ninguém o ouviria hoje.

## Segurança e isolamento

O isolamento entre contas é tratado como requisito de segurança, e o desenho reflete isso nos
pontos abaixo. A autorização dentro da conta, por perfil, segue a mesma postura: verificada no caso
de uso, provada por teste negativo, e nunca dependente de um valor que o cliente informe.

- **O `contaId` nunca vem do corpo da requisição.** Ele sai do claim do token autenticado, então
  não existe parâmetro que um cliente possa forjar para alcançar dado de outra conta.
- **O filtro é do Hibernate, não de cada consulta.** Uma consulta nova nasce filtrada por
  construção; esquecer o `where` não é uma falha possível. Em contrapartida, o filtro não alcança
  SQL nativo, e por isso query nativa em código de negócio é proibida no projeto.
- **Todo dado persistido tem teste de isolamento**, no molde de gravar na conta A e provar que a
  conta B recebe vazio. Usuário, credencial, produto, cliente, sessão de caixa e venda têm o seu, e
  os membros de agregado (movimento de caixa, item, pagamento e recebimento da venda, movimento de estoque) têm
  um teste próprio, que prova que a coluna de conta deles vem do contexto e não da raiz por
  junção. O teste falha se a anotação de tenant for removida. Quem cancelou a venda e quem lançou
  o movimento atravessam o banco na Conta do registro, e a outra Conta não os alcança. O registro das operações
  sincronizadas também tem o seu, que cobre a lista de revisões e a conferência. O pedido de plano
  também: a Conta B não o encontra por nenhuma consulta, e o código certo dele, aplicado pela Conta
  B, responde como pedido inexistente, sem gastar o código; o pedido que a Conta B faz não substitui
  o aberto da A nem esbarra nele, e o código da A continua valendo. O aceite dos termos da Conta A não
tira a pendência da B, que não enxerga a linha, e o aceite da B grava a dela sem esbarrar na da A. O teste, o vencimento e o
  só leitura da Conta têm a prova por HTTP: a Conta em teste e a paga em dia não leem as datas de
  outra nem herdam o só leitura dela, que só a própria Conta recusa. O SQL do encerramento de Conta, que roda fora do filtro de
  tenant, é testado com uma linha em cada tabela da Conta nas duas pontas: a encerrada termina sem
  nenhuma, e a outra com as mesmas, tabela a tabela; o outbox, que não tem coluna de conta, perde só
  o evento da encerrada, achado pela Conta no texto do evento serializado. Na restauração, as
  remoções de Usuário reaplicadas com a Conta de um registro e o Usuário de outra não tocam ninguém.
  A remoção de
  Cliente e a anonimização e a correção do nome de Usuário são recusadas com id de outra Conta, e o
  nome do negócio corrigido numa Conta não muda o da outra, nem a chave Pix cadastrada numa aparece
  na identidade ou na configuração da outra. A versão de acesso do
  usuário também tem a sua: a Conta B não a trava, não a lê nem a revoga num usuário da A, o
  administrador da B não redefine a senha de um operador da A, e a troca de senha na A não derruba o
  token da B nem o de outro usuário da própria A. O lote prova por HTTP que duas Contas com os mesmos ids de
  operação recebem cada uma o seu resultado e que os ids de Produto, Venda, sessão de caixa e
  Cliente de uma Conta, usados pela outra para criar um registro, são recusados sem alterar nada,
  mesmo com o conteúdo idêntico ao do Produto alheio, que uma leitura sem filtro tomaria como já
  gravado; a edição e a inativação pelo id do Produto alheio também são recusadas, e a
  edição do Cliente numa Conta não esvazia a cópia que a outra gravou com o mesmo id.
  Os casos de uso do estoque têm o
  seu nas duas direções: a lista de estoque baixo de uma conta não traz o produto de outra, e o
  ajuste de uma conta não alcança o produto de outra. O comprovante tem a mesma prova nas duas
  pontas: a venda de uma conta é inexistente para a outra, e a consulta em lote dos nomes dos
  produtos, que ele faz ao cadastro, também. Os três relatórios têm a mesma prova:
  duas contas com dados equivalentes no mesmo dia recebem cada uma só o seu faturamento, o seu
  ranking e o seu fluxo de caixa, e uma terceira, sem nada, recebe zero ou vazio; as consultas
  agregadas em JPQL, inclusive a que junta três tabelas, passam pelo mesmo filtro que as outras.
  Os filtros por forma de pagamento e por operador têm a mesma prova: a conta que pede o
  faturamento ou o ranking pelo operador de outra recebe zero ou vazio, porque o id existe, mas
  não nela. O ranking pelo valor, com consulta própria, tem a mesma prova, com e sem operador. As
  rotas HTTP repetem a prova: nenhuma das três deixa uma Conta ler a outra, e o
  operador de fora dá zero ou vazio no faturamento e no ranking. O resumo mensal tem a prova pelo
  caso de uso e pela rota: as vendas, os recebimentos e os cancelamentos de uma Conta não aparecem
  no resumo de outra, e a Conta sem nada recebe zero em cada linha. A conferência do cartão tem a
  mesma prova, pelo caso de uso e por HTTP, com as junções ao recebimento e à sessão de caixa, e a
  exigência do NSU ligada numa Conta não alcança a venda nem o lote da outra. A conferência do Pix
  tem a prova por HTTP, com o operador de outra Conta dando lista vazia; as sessões abertas da Conta
  não trazem a de outra, e a entrada de estoque no produto de outra Conta recebe 404 sem mover o saldo. O cadastro HTTP também prova as listas de produtos e clientes vazias na outra Conta,
  inclusive a lista de clientes inativos, e o 404 ao tentar editar pelo id alheio. O primeiro
  acesso aplica a cada Conta uma cópia própria do catálogo sugerido.
  O caixa HTTP também prova que a Conta B não encontra a sessão da Conta A, recebe histórico vazio
  e não deduz um caixa aberto alheio, e que a sangria e o suprimento com o id de um movimento da A,
  pela rota ou pelo lote, são recusados sem levar o movimento ao caixa da B nem dizer que ele existe. O PDV HTTP responde 404 à Conta B que tenta abrir comanda na
  SessaoCaixa da Conta A e à Conta que tenta lançar item com produto de outra.
  O callback Pix autenticado pela configuração da Conta B não encontra o `txid` persistido na
  Conta A; a lista de conciliação também devolve somente Vendas da Conta autenticada, com as duas
  Contas tendo cada uma a sua. O teste
  HTTP de cancelamento recusa a tentativa da Conta B antes de chamar o PSP da Conta A. A Conta B
  que pede cobrança Pix com o id de uma parcela da Conta A é recusada pela chave primária, sem ler
  nem alterar a Conta A.
- **Os listeners da venda nunca trocam de conta.** Todos rodam na transação de quem publica e
  recusam o evento de outra conta, com teste para cada um que prova que a conta do evento não
  recebe nada quando ele é publicado sob outra. A conta vai dentro do evento, lida do contexto
  autenticado no ato da publicação. `FiadoServiceTest` prova o recebimento no caixa da Conta e o
  404 ao tentar receber a Venda pela Conta alheia. A tabela do outbox não tem coluna de conta,
  porque é do framework e nenhum código de negócio a lê; o evento do caixa fechado leva só os ids
  da Conta e da sessão, e o ouvinte do resumo por e-mail põe essa Conta no contexto antes de ler.
  `ResumoDoFechamentoTest` prova que o resumo de uma Conta não chega ao administrador de outra nem
  leva os números dela, mesmo com uma venda de outra Conta apontando para a sessão, que o evento
  anunciado por outra Conta não lê a sessão alheia, e que o operador nunca o recebe.
- **A pergunta sobre a conta não aceita conta.** A tabela de contas é a única sem filtro
  automático, porque o id dela é o tenant; em troca, o serviço que responde por ela lê a conta do
  contexto e nada mais, sem assinatura por onde perguntar sobre outra.
- **O perfil não vem do token nem de parâmetro.** O filtro que resolve o token lê o usuário no
  banco a cada requisição, pela chave, já sob o tenant: inativo recebe 401 antes de qualquer caso
  de uso, e o perfil que entra no contexto, e no token renovado, é o do banco. Cada caso de uso
  restrito tem teste de autorização negativa: o operador não cadastra produto, não mexe no
  estoque, não lê relatório, nem o filtrado por ele mesmo, e não gere usuários; não lança, fecha
  nem consulta o caixa do colega, não pede o histórico do colega nem o da conta inteira, não
  inicia venda no caixa do colega e não toca a venda dele por nenhum caso de uso. O administrador
  faz tudo isso, e há teste de que fecha o caixa do operador e cancela a venda dele. A gestão de
  usuários tem a prova de isolamento nos dois sentidos, e a prova de que o token que um operador
  já tinha deixa de entrar assim que ele é inativado.
- **O token pode ser revogado.** Ele leva a versão de acesso do usuário, e o mesmo filtro recusa o
  que não leva a versão gravada no banco, ou nenhuma. Trocar a senha, ter a senha redefinida pelo
  administrador ou sair de todos os aparelhos sobe a versão, e todos os tokens emitidos antes
  recebem 401 na requisição seguinte. O lote de sincronização de um aparelho revogado também: nada
  é aplicado, os gestos voltam à fila do aparelho e saem quando o mesmo usuário entrar de novo ali.
- **A Conta inteira pode ser suspensa.** O mesmo filtro pergunta antes se o acesso da Conta do token
  está suspenso, e recusa até o token com a versão de acesso atual, que um login simultâneo à
  suspensão poderia ter emitido. A suspensão sobe também a versão de todos os usuários, então o reverso
  não devolve o acesso a nenhum token de antes. Há teste de que a outra Conta segue entrando, de que o
  lote da suspensa não é aplicado e de que a senha certa dela não conta na contenção do login.

A autenticação usa JWT emitido e validado pelo próprio Spring Security, sem biblioteca de JWT de
terceiro, com API stateless e senha em BCrypt verificada contra bases de senhas vazadas. A política
de senha exige comprimento e nenhuma regra de composição, porque exigir símbolo e maiúscula empurra
o usuário para uma senha pior, anotada num papel no balcão. O máximo é de 72 bytes em UTF-8, o
limite do BCrypt, conferido antes da consulta à base de senhas vazadas, com a mensagem dizendo que a
letra acentuada conta como duas. Quando essa base não responde, quem escolheu a senha decide: a
escolhida pela própria pessoa entra, com um aviso no log sem nada que a identifique, porque a troca
pode ser a resposta a uma senha vazada; a escolhida por outra pessoa, que é provisória, espera a base
voltar. Enquanto a senha for provisória, o filtro do token, que lê a credencial a cada requisição,
responde 403 a toda rota da API fora da identidade, da troca da própria senha e da saída de todos os
aparelhos, por comparação exata do método e do caminho, e registra a recusa como evento de segurança,
com o motivo; o lote do aparelho volta à fila e sai depois da troca. Há teste para cada rota liberada,
para a recusada, para o lote não aplicado e para a redefinição pelo administrador, que volta a barrar.

O login conta as falhas de duas formas, cada uma numa janela de 15 minutos que começa na primeira
falha: as do mesmo e-mail vindas da mesma origem, até dez, e as da origem somando todos os e-mails,
até trinta, com folga para vários operadores atrás do mesmo IP. Esgotada qualquer uma, a tentativa
recebe 429 com o tempo de espera antes de consultar o banco ou conferir a senha, e nem a senha certa
entra até a janela acabar. Quem tenta de outra origem não alcança a contagem do usuário legítimo. A
tentativa é contada ao entrar e devolvida se não terminar em recusa de credencial, então pedidos
simultâneos não passam juntos pelo limite, o login certo não pesa na origem e uma queda do banco
não deixa ninguém bloqueado. A origem é o endereço lido da direita do cabeçalho do proxy, e o IPv6
conta pelo prefixo /64. A contagem fica na memória da instância única, sem gravar IP nem e-mail no
banco, e o aviso de bloqueio no log leva a origem, sem o e-mail. O e-mail inexistente passa pela mesma conferência
BCrypt que a senha errada, contra um hash gerado na partida, para o tempo da resposta não dizer
quais e-mails existem.

Os eventos de segurança vão ao log numa linha cada, no formato chave=valor com o nome fixo do evento
primeiro: o login certo e o recusado, o bloqueio do login, o token apresentado e recusado, por ser
inválido, vencido, anterior a uma revogação, de usuário inativo ou de Conta suspensa, a recusa por perfil, o aviso Pix
recusado, a troca e a redefinição de senha, a troca do e-mail de entrada e a recusa dela, a saída de
todos os aparelhos e as ações do administrador sobre usuários, inclusive a correção do nome, plano,
configuração, inclusive o nome do negócio, sem o nome, e remoção de Cliente, além da suspensão e do reverso de uma Conta e do cadastro do webhook Pix dela, sem origem, porque saem da execução avulsa na máquina. Cada linha leva o IP de origem, os ids de
Conta, de Usuário e do alvo e o motivo numa palavra fixa, nunca nome, e-mail, contato, senha ou token;
o id só se liga a uma pessoa pelo banco. Todo valor é limpo antes de entrar, para que um caminho ou
uma origem vindos do cliente não forjem outro campo ou outro evento. O pedido sem token não vai ao log,
porque é quase só robô procurando rota. O erro inesperado vai com o método, o caminho e a pilha, sem
as mensagens das exceções, que podem trazer o valor repetido de uma violação de unicidade. Toda
resposta 5xx, inclusive a indisponibilidade do Pix ou do registro de remoções, deixa uma linha em ERROR
com o status, o método e o caminho, sem a query. O PWA relata o erro que escapa de toda tela, a
exceção sem tratamento e a promessa rejeitada sem quem a espere, só com o que localiza o defeito, até
cinco por carga da página e cada um uma vez; a linha não leva Conta, usuário, origem nem a mensagem.

O corpo de cada requisição tem limite por rota, conferido antes de qualquer outro filtro: 4 KB no
login, 256 KB no aviso de pagamento Pix, 2 KB no relato de erro do aplicativo e 512 KB no resto, o lote de sincronização incluído, quatro
vezes o maior lote que os formulários do aplicativo produzem. Acima disso a resposta é 413, em
Problem Details. Sem limite, um único pedido anônimo grande virava uma árvore JSON de mais de dez
vezes o próprio tamanho e esgotava a memória da aplicação. O pedido que declara o tamanho é
recusado pelo cabeçalho, sem ser lido; o que chega em partes é contado enquanto é lido. O limite
vem do caminho que o servidor já decodificou, então escrever o login com uma letra codificada não
o troca. O aviso Pix só tem o corpo lido depois de conferidos o certificado e o segredo, e só como
JSON, e nenhuma rota lê formulário. O proxy da máquina tem um teto único acima do maior desses
limites, e o aplicativo trata o 413 no envio da fila como as outras recusas: o lote fica com a
falha visível e nada sai da fila.

Cada texto que vai a uma coluna `varchar` tem o tamanho conferido no domínio, antes do banco, pela
mesma função: nome, código, categoria e unidade do produto, nome e contato do cliente, nome e
e-mail do usuário, motivo da sangria, do suprimento e do ajuste de estoque, e o NSU. A conta é a do
Java, depois do corte dos espaços das pontas, a mesma do limite dos campos do aplicativo. A rota
responde 400 com o campo, o tamanho e o limite, e o gesto do lote que traz o mesmo texto vira recusa
com a mesma mensagem, porque os dois chamam os mesmos casos de uso. A mensagem nunca leva o texto,
que pode ser o nome de uma pessoa e iria ao registro gravado do lote. Sem essa conferência, o banco
recusaria a linha, mas a resposta seria um 500 com a pilha no log ou um 409 que mandaria procurar um
registro repetido que não existe.

A validade do token, de 72 horas, conta a partir do último contato com o servidor e não do login:
toda resposta autenticada devolve um token renovado. Na prática, é a janela de resistência que a
operação offline exige. O aparelho parado não faz contato nenhum, e por isso o aplicativo aberto, com
a tela à vista e o servidor respondendo, pergunta a identidade a cada hora; sem resposta, o cabeçalho
avisa nas últimas 24 horas. Vencido o token pelo relógio do aparelho, o aplicativo vai à entrada e diz
por quê, de qualquer tela, e a fila fica guardada até essa pessoa entrar de novo. Inativar alguém derruba o token dele na requisição seguinte, e trocar a senha ou sair de
todos os aparelhos derruba os tokens de quem pediu, pela versão de acesso. No aplicativo, o token e a identidade de quem entrou ficam guardados no
dispositivo, e é isso que permite abrir o aplicativo sem rede. Cada login inicia uma sessão nova
no navegador; token e identidade ficam vinculados a ela, cada aba só usa a sessão com que abriu,
e uma troca em outra aba remove a identidade anterior da tela; a entrada diz se alguém saiu ou entrou
na outra aba, ou se o servidor recusou o token dela. O que uma escrita começada antes de
uma troca de sessão ainda faria no aparelho, como marcar e reler o caixa ou guardar a identidade,
não alcança a sessão que entrou depois, na mesma aba ou em outra. O custo aceito, e escrito, é que
quem pega o tablet destravado entra até o token expirar. O cliente nunca envia conta nem perfil em
requisição nenhuma, e a navegação por perfil apenas esconde o que o servidor recusaria: a
autorização continua sendo do caso de uso.

Como o token fica no aparelho, ao alcance de qualquer script da origem, toda resposta sai com uma
política de conteúdo aplicada: script, estilo, imagem, fonte, conexão, manifest e service worker só
da própria origem, nada embutido no HTML, nenhum objeto e nenhuma moldura por outro site. Um script
injetado no futuro, por dependência ou por código novo, não carrega nada de fora nem envia o token
para outra origem. O build do aplicativo não tem script nem estilo embutido, então a política não
precisa de exceção. Nenhum `Referer` sai, nem para a própria origem, e a política de permissões nega
câmera, microfone, localização, pagamento e USB, deixando à própria origem a área de transferência e
o compartilhamento, que o copia e cola do Pix e o comprovante usam. Os cabeçalhos saem da cadeia de
segurança da aplicação, ao lado do HSTS, então valem atrás de qualquer proxy e no jar sozinho.

Usuário novo nasce pela gestão de usuários da conta, feita pelo administrador: um `Usuario`, que
carrega o perfil e o tenant, e uma `Credencial`, que carrega e-mail e senha, gravados na mesma
transação. A senha inicial passa pela mesma política de qualquer senha, inclusive a verificação de
vazamento, que recusa se não puder verificar; criar usuário é raro, quem cria é o dono e ele está
conectado. A conta nunca fica sem um
administrador ativo. A inativação trava a linha da conta antes de contar os administradores, então
duas inativações simultâneas não passam juntas pela guarda: a segunda espera a primeira confirmar,
conta de novo e é recusada antes de gravar o registro externo da remoção. A trava não atrasa vendas
nem cadastros, que só conferem a chave da conta. A correção do nome de um usuário trava a linha dele,
como a troca de senha: a gravação escreve a linha inteira, e sem a trava levaria de volta a versão de
acesso lida antes de uma revogação simultânea, e um teste prova a espera.

Nenhum segredo mora no repositório. A chave de assinatura dos tokens, o segredo dos códigos de
plano e as credenciais vêm de variável de ambiente, e a aplicação recusa subir sem eles, em vez de
cair num valor padrão que seria idêntico em toda instalação. No servidor, eles ficam num arquivo
que só o administrador lê, fora do repositório, e os que nascem lá, as senhas do banco, a chave dos
tokens e o segredo dos códigos, são gerados na própria máquina, sem passar por ninguém; o segredo dos
códigos é guardado também com o mantenedor, porque ele precisa dele para gerar os códigos.
A mensalidade também vem do ambiente, e nenhum preço está no código. O build da imagem também não leva
configuração local, chave ou certificado esquecidos nas pastas do código, e cada publicação espera a
verificação dos avisos de segurança das dependências e das imagens.

Atrás do proxy da máquina, que descarta o cabeçalho mandado pelo cliente e põe no lugar o endereço
de quem se conectou a ele, o endereço de origem sai do `X-Forwarded-For` lido da direita para a
esquerda, atravessando só a rede interna e os proxies que uma variável declarar: o que o cliente
escreve no cabeçalho nunca vira origem, e um salto desconhecido vira a origem em vez de ser
atravessado. O HTTPS é reconhecido pelo `X-Forwarded-Proto`, e é com ele que o
`Strict-Transport-Security` sai. Do Actuator, só o health é exposto por HTTP. A cópia do banco sai
do contêiner já cifrada, e a tarefa que a faz não consegue decifrá-la nem gravar por cima de uma
cópia existente.

No servidor, a aplicação conecta ao banco com um papel que é dono dele e não tem nenhum atributo de
superusuário: mesmo uma consulta injetada não executaria comando na máquina do banco, não leria
arquivo dela e não criaria papel. O mesmo papel aplica as migrations, faz a cópia e a restauração; o
superusuário serve só para criar o banco e para a manutenção de dentro do contêiner, e nenhum serviço
conecta com ele.

## Modelo de dados

Toda tabela de negócio carrega `conta_id`. Enums são `varchar` com `CHECK`, e não o tipo `ENUM` do
Postgres, porque acrescentar valor a um `ENUM` exige `ALTER TYPE` fora de transação. Valores
monetários são `numeric(12,2)` e timestamps são `timestamptz` gravados em UTC. Texto é `varchar`
com limite, a última linha de defesa contra uma escrita que fuja da aplicação; a aplicação confere
o mesmo limite antes, para a recusa ser um 400 que diz o campo, e não uma violação do banco.

### Agregados

| Agregado | Raiz | Membros | O que a raiz garante | Estado |
|---|---|---|---|---|
| **Venda** | `Venda` | `ItemVenda`, `Pagamento`, `Recebimento` | `valor_total` reflete a soma dos itens menos o desconto; numa Venda concluída, pagamentos confirmados e FIADO pendente cobrem o total, e os recebimentos não excedem o FIADO | Implementado: regras conferidas também ao remontar o agregado do banco. CANCELADA é estado final, com itens, parcelas e recebimentos preservados |
| **Caixa** | `SessaoCaixa` | `MovimentoCaixa` | `valor_fechamento_esperado` reflete o valor de abertura mais a soma assinada dos movimentos | Implementado |
| **Produto** | `Produto` | `MovimentoEstoque` | `estoque_atual` reflete a soma dos movimentos, atualizado na mesma transação | Implementado para os três tipos, a saída por venda, a entrada do cancelamento e o ajuste manual: o único método que escreve o saldo exige o movimento junto, o ajuste sem motivo não passa pela raiz, e não se devolve o que não saiu |
| Entidade única | `PedidoDePlano` | nenhum | um pedido aberto por Conta, por índice único parcial; aplicar o código muda o plano e o vencimento da Conta na mesma transação, com a linha dela travada, e o reenvio não muda nada | Implementado |
| Entidade única | `AceiteDosTermos` | nenhum | uma linha por versão aceita na Conta, por índice único, gravada com a linha da Conta travada; só nasce, porque é a prova do aceite | Implementado |
| Entidade única | `Conta`, `Usuario`, `Cliente` | nenhum | são agregados de uma entidade só; a marca do primeiro acesso da Conta só avança uma vez; a chave Pix da Conta e a cidade do QR são gravadas e tiradas juntas; a remoção do Cliente anonimiza a linha e a inativação do Usuário apaga sua credencial, ou a guarda até ser concluída quando o registro externo das remoções está fora do ar | Implementado |

Referência que cruza agregado é sempre por ID, nunca um `@ManyToOne` navegável. É o que impede
editar um agregado através de outro.

### Detalhes de modelagem que valem nota

- **O preparado na hora é serviço, sem estoque.** O tipo do produto continua com dois valores, e o
  item que nunca gera movimento de estoque é o mesmo para um corte de cabelo e para um café coado: a
  tela os chama de sem estoque, serviço ou preparado na hora, e o catálogo inicial da cafeteria já
  nasce assim. Uma marca à parte, controla estoque, seria a segunda forma de dizer a mesma coisa. O
  tipo continua fixo depois do cadastro, para nenhum movimento ficar sem o produto que o explica.
- **Chave Pix normalizada, com o tipo lido da forma.** A Conta guarda a chave do Pix manual na
  forma que o QR leva, conferida pelo tipo que o administrador escolhe: um CPF e um celular têm os
  dois onze dígitos, e só o tipo diz qual foi digitado. O CPF e o CNPJ, inclusive o alfanumérico,
  passam pelos dígitos verificadores. As formas gravadas não se confundem, então o tipo volta da
  própria chave, sem coluna; a cidade do recebedor só existe com ela, e o banco recusa uma sem a
  outra. O QR estático é montado no aparelho, com o CRC do BR Code, e funciona sem rede.
- **Código de ativação sem coluna.** O pedido de plano guarda tipo, plano, valor e período, e o
  código que o ativa é a assinatura do id do pedido, do plano e do valor com um segredo do
  servidor: a aplicação a recalcula com o que gravou para conferir, então o banco não guarda código
  válido nenhum, e um texto de pedido alterado no caminho, com outro plano ou outro valor, leva a um
  código recusado. O mesmo cálculo roda no script de operação do mantenedor, conferido pelo mesmo
  vetor fixo dos dois lados.
- **Catálogo inicial marcado na Conta.** O primeiro login de administrador bloqueia a linha da
  Conta, marca a aplicação do catálogo e publica o evento que copia os itens, tudo na mesma
  transação. Um segundo login não duplica os produtos, mesmo se o catálogo estiver vazio por não
  haver sugestões para aquele tipo de negócio.
- **Índice único parcial** no código do produto, válido apenas entre os registros ativos, para que
  um item inativado não bloqueie a reutilização do código. A unicidade é por conta, e ignora
  maiúsculas, porque o operador digita rápido no balcão e não pode perder a venda por causa disso.
  Reativar o item cujo código passou a outro ativo é recusado, com o nome do outro, em vez de tirar
  o código em silêncio.
- **Uma sessão de caixa aberta por operador**, garantida por índice único parcial sobre as sessões
  em aberto. Dois atendentes podem ter caixas simultâneos no mesmo negócio; o mesmo operador com
  dois, não. O índice é a rede embaixo da checagem do caso de uso, para o caso de duas requisições
  passarem juntas.
- **`MovimentoCaixa` tem um campo `tipo`** único para venda, estorno, sangria e suprimento, o que
  mantém o fechamento de caixa como uma soma simples em vez de juntar quatro tabelas. O valor
  gravado é sempre positivo, e quem carrega o sinal é o tipo. O movimento de venda nasce do
  evento de venda concluída, na mesma transação e com o instante da conclusão, vale só a parte
  paga em dinheiro, e a mesma venda não entra duas vezes na mesma sessão: a raiz recusa a
  duplicata. O estorno nasce do evento de venda cancelada, na transação do cancelamento, aponta
  para a mesma venda, vale exatamente o que a venda trouxe e sai uma vez só, com a mesma recusa na
  raiz; um índice único parcial por sessão, venda e tipo é a rede embaixo dos dois.
- **A trilha de quem fez fica no banco, não no log.** A venda cancelada guarda quem a cancelou e
  quando, e cada movimento de caixa guarda quem o lançou, sempre o usuário autenticado, também no
  gesto que chega pelo lote. O operador cancela a própria venda concluída para desfazer o erro no
  balcão sem chamar o dono, e é a trilha que deixa o dono descobrir, no caixa que ele já confere,
  quem cancelou a venda que devolveu dinheiro da gaveta. O evento de domínio leva o autor, e o
  ouvinte do caixa o grava sem ler o contexto; na venda concluída pelo aviso do Pix, que chega sem
  ninguém autenticado, o autor é o operador dela. O log de segurança fica com o que é segurança, e
  as linhas gravadas antes da trilha ficam sem autor.
- **`estoque_atual` é consolidado na raiz**, e não somado do histórico a cada leitura, para o alerta
  de estoque baixo não pagar esse preço. Levado às últimas consequências: a raiz não carrega o
  histórico, ao contrário da sessão de caixa, cujo extrato é um expediente. O histórico de um
  produto cresce a cada venda e o produto é lido em toda venda, então a coleção é preguiçosa, o
  domínio conhece só o saldo, e cada baixa devolve o movimento que a explica para que os dois sejam
  gravados juntos. A pergunta sobre a duplicata, se a mesma venda já baixou este produto, vai ao
  repositório por consulta derivada, e um índice único parcial é a rede embaixo.
- **`MovimentoEstoque` segue o mesmo molde do movimento de caixa**: entrada, saída e ajuste numa
  tabela só, quantidade sempre positiva na entrada e na saída, com o sinal no tipo. O ajuste é a
  exceção: serve aos dois sentidos, então o sinal vai na quantidade, e um `-2` no histórico se lê
  sozinho como uma perda de dois. O schema deixou essa convenção em aberto até o ajuste existir,
  para a decisão ser tomada com o caso de uso na mão em vez de presa numa migration imutável, e a
  migration seguinte reemitiu os comentários das colunas com a convenção decidida.
- **O estoque mínimo é caso de uso próprio, não campo do formulário de cadastro.** O limiar é
  política de estoque, não descrição do item, e só faz sentido com o controle ligado; num salão o
  formulário carregaria um campo morto. Por isso passa pelo módulo de estoque, que decide se a
  conta participa, e o cadastro não muda de forma.
- **`preco_unitario` do item é cópia** do preço do produto no momento da venda, nunca leitura viva:
  reajustar o produto depois não pode alterar o valor de uma venda passada. Há teste que reajusta o
  produto e prova que a venda gravada não muda.
- **O arredondamento é por item, e o método que multiplica diz isso no nome.** Setecentos e
  cinquenta gramas a 39,90 dão 29,925, que vira 29,93 na linha; o total é a soma de linhas já
  arredondadas, para que o comprovante feche com o total impresso.
- **Desconto nunca passa do valor.** Nem o do item sobre o bruto do item, nem o da venda sobre a soma
  dos itens; a raiz recusa a operação, em vez de gravar um subtotal negativo ou limitar em silêncio.
  Brinde é preço zero, não desconto acima do valor.
- **Item lançado não se edita.** Quantidade e desconto entram junto com o item; corrigir é remover
  e lançar de novo, e o mesmo produto pode aparecer em duas linhas da mesma venda.
- **Fração só fora da unidade "un".** A quantidade tem até três casas, para o que se vende por peso
  ou por hora, mas o produto de unidade "un", ou sem unidade, só aceita quantidade inteira. A
  unidade é texto livre no cadastro, e uma lista do que se fraciona erraria toda unidade escrita de
  outro jeito; por isso só essas duas formas fecham a fração, sem diferença de maiúscula nem de
  espaço nas pontas. Recusam o lançamento com rede, o gesto que chega pelo lote e a montagem da
  venda no aparelho sem rede, com a mesma frase.
- **`Pagamento` é entidade própria**, e não colunas na venda, porque uma venda pode ser dividida
  entre formas: metade em dinheiro e metade no cartão são dois registros, cada um com o seu valor e
  o seu estado.
- **Parcela nunca passa do que falta pagar, e o total nunca fica abaixo do já pago.** Lançar uma
  parcela maior que o saldo é recusado como erro de digitação; remover item ou aplicar desconto
  que deixasse o total menor que as parcelas lançadas também. Uma parcela pendente, à espera de um
  provedor, reserva o lugar dela na conta; uma recusada não ocupa lugar. Parcela lançada não se
  desfaz: o valor errado se corrige cancelando a venda.
- **A Venda só conclui quando os pagamentos confirmados e o FIADO pendente cobrem exatamente o total**, e nunca sem
  item. O troco é calculado pelo módulo de pagamentos, devolvido a quem chamou para a tela mostrar
  no ato, e gravado na parcela, para o comprovante sair igual numa reimpressão. Zero fora de
  dinheiro, e o banco recusa troco em Pix ou cartão.
- **A venda guarda dois instantes:** quando a comanda abriu e quando os pagamentos fecharam a
  conta. O segundo é a data do comprovante, porque numa comanda os dois podem estar horas
  distantes; nasce na conclusão, e o cancelamento não o apaga. É também o que delimita o dia do
  faturamento: a venda conta no dia da conclusão, inclusive com FIADO pendente, e uma comanda aberta às 23h50 e paga
  às 00h10 é do dia seguinte. A sessão de caixa, por sua vez, é do dia em que abriu, porque um
  expediente pode atravessar a meia-noite e continua sendo um só. Na Venda e no caixa registrados
  sem rede, todos esses instantes são os do balcão, e não os da chegada ao servidor, que fica no
  registro da operação.
- **O registro das operações sincronizadas** guarda, por Conta, cada gesto como o dispositivo o
  enviou, o autor, o resultado, o motivo da revisão ou da recusa e o instante de recebimento. É
  gravado na mesma transação do efeito. Depois, o ADMIN pode conferir a revisão ou recusa, e a
  remoção de um Cliente anonimiza o payload dos gestos dele. O índice único pela Conta e pelo id da
  operação é o que torna o reenvio inofensivo. Não é agregado de domínio: não tem regra de negócio,
  só a memória do que já foi aplicado.
- **O NSU do cartão é anotação da parcela, e a exigência é da Conta.** O número do comprovante da
  maquininha não passa pela estratégia de pagamento, que decide status e troco; a parcela e o
  recebimento o guardam como texto livre de até 40 caracteres, sem os espaços das pontas, e o
  recusam fora do cartão, e o banco repete as regras. Se a Conta exige o NSU, quem confere é o caso
  de uso com rede, depois do reenvio igual, porque ligar a exigência não alcança o que já foi
  gravado; a parcela feita sem rede que chega sem ele é gravada e vai para revisão, porque o
  pagamento aconteceu. Repetido não é recusado: o número só é único dentro de cada operadora.
- **As tabelas de venda, item de venda, produto, pagamento, recebimento, sessão de caixa e movimento
  de caixa têm um segundo mapeamento, somente leitura**, no módulo de relatórios: imutável, com as colunas que o relatório usa e
  nenhuma outra, sem construtor que a instancie. O esquema é o contrato entre os dois
  mapeamentos, versionado nas migrations, e o Hibernate valida os dois na subida: renomear uma
  coluna no módulo dono derruba a aplicação no deploy, que é o modo certo de falhar, e não com
  relatório em branco. Os mapeamentos de produto e de pagamento nem têm repositório: existem para
  ser alvo de junção; o de produto traz o nome atual para o ranking mesmo depois de o produto ser
  inativado, e o de pagamento é o que permite somar por forma o valor de cada parcela, e não o
  total da venda que a usou. Os de recebimento e de sessão de caixa também não têm: servem à
  conferência do cartão, cujo operador do fiado recebido é o dono do caixa em que ele entrou.
- **A conferência do cartão é do dia do lançamento, não da conclusão.** A parcela passada às 23h58
  de uma venda concluída à 0h02 fica no dia anterior, como no extrato da maquininha, e entram as
  vendas abertas e canceladas, com a situação, porque a cobrança delas também está no extrato.
- **O fluxo de caixa é o da gaveta, e o dia é o do movimento.** O caixa só registra dinheiro em
  espécie, então entradas são vendas em dinheiro e reforços de troco, saídas são retiradas e
  estornos de venda cancelada, e o saldo é a diferença; o troco inicial da sessão não é
  movimento e fica de fora. O período é delimitado pelo instante em que cada movimento foi
  lançado, e não pelo dia da sessão: um expediente que vira a meia-noite reparte os movimentos
  entre os dois dias, enquanto o histórico do caixa o mantém inteiro no dia em que abriu. As duas
  perguntas são diferentes, e as duas respostas convivem.
- **O comprovante é dado, não desenho.** O caso de uso devolve um record com as linhas já
  calculadas, o nome que o produto tem hoje, os descontos, as parcelas confirmadas e o troco, e,
  só na venda fiada, o nome de hoje de quem deve, porque o papel que prova a dívida diz de quem ela é;
  quem desenha, imprime e compartilha é a tela, que precisa fazer isso também sem conexão. Só
  venda concluída tem comprovante, e ele não se parece com documento fiscal.
- **Estado divergente não vira agregado.** Ao remontar uma venda do banco, a raiz confere as duas
  regras e recusa a linha cujo total não bate com os itens, ou concluída sem os pagamentos que a
  fecham. Uma linha gravada por fora do código deixa de ser legível pelo domínio até ser corrigida,
  em vez de circular com um total que ninguém confere.
- **A venda nasce aberta.** É o estado da comanda em montagem e também o da venda que espera a
  confirmação de um pagamento que chega depois, como uma cobrança de Pix gerada por provedor. Os
  dois são o mesmo estado porque em ambos a venda existe e ainda não está paga.
- **Toda coluna numérica tem restrição de sinal no banco.** Valor e preço nunca negativos, quantidade
  sempre positiva, desconto ausente gravado como zero e não como nulo. O domínio repete as mesmas
  guardas; a restrição no banco existe para um script de correção não gravar o que o código recusa.

## Qualidade e testes

A suíte roda contra um **PostgreSQL real** provisionado por Testcontainers, nunca contra banco em
memória: o projeto usa `JSONB`, índice GIN, índice único parcial e `timestamptz`, e um banco
substituto validaria um schema que não é o de produção.

Quatro tipos de teste sustentam o projeto:

| Tipo | O que garante |
|---|---|
| **Fitness function de arquitetura** | As fronteiras entre módulos continuam intactas, e a violação quebra o build |
| **Teste de isolamento** | Nenhum dado novo vaza entre contas, verificado nos dois sentidos |
| **Teste de invariante** | Toda raiz de agregado recusa a operação que quebraria a regra que ela protege |
| **Teste de caso de uso** | O comportamento observável de cada operação, incluindo os casos de borda decididos explicitamente |

Nenhuma etapa fecha com teste vermelho. Regra do projeto: dado persistido novo exige teste de
isolamento, raiz de agregado tocada exige teste que tenta violar a invariante e espera falha, e
caso de uso restrito por perfil exige teste de autorização negativa, que chama como operador e
espera a recusa. Todo teste que chama um caso de uso executa como alguém: a fixture de conta
define o tenant e o usuário juntos, como o filtro do token faz numa requisição de verdade, e um
caso de uso chamado sem usuário falha fechado. A exceção é a entrada do webhook Pix: ela não
representa uma pessoa, resolve a Conta pela configuração autenticada da URL e só pode confirmar
uma parcela já persistida depois de reconsultar o PSP.

Os eventos de domínio são testados de verdade. A conclusão, o cancelamento e o recebimento de
fiado são publicados com a conta no contexto, e o teste confere logo em seguida o movimento no
caixa e o saldo no estoque, que mudam na mesma transação; o mesmo fato publicado de novo é recusado
pela raiz ou pelo cadastro, o evento de outra conta é recusado pelo ouvinte, e nenhum deles passa
pelo registro de publicação. Do lado do estoque, a conta desligada não devolve nada, e a que ligou
o controle depois da venda não devolve o que nunca saiu.

A disputa com outra transação pela mesma raiz também é testada, sem pausa: uma sangria, um
fechamento, outra venda do mesmo produto ou um suprimento fica gravado e sem confirmar, a
operação sob teste roda em outra thread, e a transação concorrente só confirma quando o PostgreSQL
mostra uma conexão parada na trava da linha. O cancelamento ou o recebimento é recusado inteiro
por conflito de versão, sem efeito pela metade, e repetido dá certo, ou esbarra no caixa que já
fechou. Duas vendas do mesmo produto concluídas ao mesmo tempo, em caixas de operadores diferentes,
passam pelo mesmo desenho: com rede, a segunda é recusada na primeira tentativa, repete sozinha e
conclui; pelo lote, volta como erro transitório, sem nada gravado, e o reenvio é aplicado; sem
controle de estoque, nada é disputado.

Duas escritas na mesma Venda também: uma transação prende a linha da comanda, e as operações
começam uma depois da outra, cada uma quando a anterior já está parada numa trava. Duas adições
valem as duas, duas parcelas do total não passam dele, e a edição que chega durante a conclusão,
com rede ou pelo lote, é recusada sem reabrir a venda nem mexer no caixa e no estoque. Duas
conclusões, e dois cancelamentos, com os mesmos produtos em ordens diferentes terminam em conflito
de versão para a segunda, nunca em impasse. Testes de arquitetura recusam escrita em `VendaService`
que leia a Venda sem a trava, consulta que não leia num único instante e ouvinte da conclusão ou do
cancelamento sem ordem declarada.

A consulta durante a escrita também: uma transação prende a tabela do membro, a leitura passa pela
linha da Venda e para nela, e a escrita confirma nessa mesma transação. A lista do caixa, o detalhe
da venda e as dívidas em aberto saem como estavam antes da escrita, inteiros, e a leitura seguinte já
a vê.

Duas inativações de administrador também: uma transação prende a linha do usuário que a primeira
grava, e a segunda começa com a primeira parada ali. Quando um administrador inativa o outro ao
mesmo tempo, uma inativação confirma e a outra é recusada sem gravar o registro externo da remoção;
a mesma inativação pedida duas vezes grava um registro só; e a trava de uma conta não faz esperar a
inativação de outra.

Duas aplicações do mesmo código de plano também: uma transação prende a linha do pedido que a
primeira aplicação grava, a segunda espera na linha da Conta e, solta a primeira, encontra o pedido
já aplicado, sem gravar por cima. Dois pedidos de plano da mesma Conta ao mesmo tempo também: o
segundo espera na linha da Conta e substitui o aberto do primeiro, e um segundo pedido aberto
gravado sem passar pelo serviço esbarra no índice. O ciclo do plano é testado com datas fixas, sem
relógio: o último dia dos meses curtos, o fevereiro bissexto, a volta ao dia original, o fim do
teste, os limites do aviso, e a tolerância de três dias úteis com o vencimento numa sexta, no fim de
semana e na véspera de um feriado. A Conta recusa a renovação de período já pago e a retomada fora do
só leitura, e o banco recusa, gravada por fora do código, a Conta fora do plano único, sem as datas do
ciclo ou sem a marca do teste; o pedido de plano recusa aplicar ou substituir o que não está aberto.
As migrations que deixaram dois planos e depois um rodam num banco à parte, levado até a versão
anterior e com as linhas de antes: a Conta do plano que saiu passa ao completo com o mesmo
vencimento, a do gratuito passa ao teste, e os pedidos que perderam o sentido saem ou são
substituídos. O só leitura tem a prova pela rota e pelo lote, com o gesto feito antes dele aplicado
e o feito depois recusado na revisão do administrador. Um teste fixa a lista de gestos sem rede, para
um gesto novo não entrar sem decidir o que o só leitura faz com ele.

O lote de sincronização é testado de ponta a ponta por HTTP, com os gestos que o PWA grava: um
dia inteiro sem rede num envio só, com a sangria que só cabe por causa da Venda do mesmo lote; o
mesmo lote enviado três vezes com o mesmo estado; o mesmo id com outro conteúdo; duas Contas com
os mesmos ids; e, no serviço, o mesmo lote enviado por duas requisições ao mesmo tempo, sem efeito
em dobro. A lista de revisões é testada por HTTP com a revisão e a recusa de outro usuário da
Conta, a conferência que não muda quando repetida e o reenvio da operação conferida. No PWA, o
envio é testado com um servidor falso que grava o resultado por id: a ordem e os lotes de até cem,
os quatro desfechos, a resposta perdida, a falha repetida na rodada seguinte, o 401 que preserva a
fila e a fila de uma Conta que não sai com outra autenticada.

A leitura dos cabeçalhos dos proxies é testada contra o servidor de verdade, numa porta real,
porque é o Tomcat que a faz, antes de a requisição chegar ao Spring: atrás do proxy da máquina,
a origem é o cliente e a requisição conta como HTTPS; o prefixo que o cliente forja é
ignorado; um salto da rede interna é atravessado e um salto público não declarado vira a origem; o
proxy declarado pela variável é atravessado; e o HSTS só sai quando o proxy diz HTTPS. Na mesma
porta real, a política de conteúdo, a de referência e a de permissões saem, uma vez cada, no shell
pela raiz e por rota do cliente, num arquivo de `assets/`, no manifest, no 404 de arquivo, e na API
com e sem token, sem tirar os cabeçalhos que o Spring Security já escrevia. As rotas de
vida e de prontidão respondem sem token e sem consultar o banco. A execução avulsa que cria uma
Conta tem teste próprio, sem servidor web, como roda em produção, e as que redefinem a senha do
administrador e suspendem e reativam uma Conta têm o seu, contra o banco real; o da suspensão confere
também o efeito pela API.

A cada push no `main` e a cada pull request, o GitHub Actions roda a suíte Java, os testes e o
build do front-end, o lint e o teste do script do código de plano, e, em paralelo, constrói as duas imagens do commit e confere os avisos
de segurança publicados para as bibliotecas Java dentro delas, para os pacotes das imagens e para o
`package-lock` do front-end, inclusive as dependências de build. Um aviso alto ou crítico que já
tenha correção barra a publicação; a exceção fundamentada entra num arquivo versionado, com data de
revisão de no máximo 90 dias, e vencida volta a barrar. A mesma verificação roda uma vez por semana,
sem publicar, e na máquina pelo mesmo script. As Actions do CI ficam presas pelo SHA do commit, as
imagens base e as de terceiros pelo digest, e a distribuição do Maven pelo checksum, que o wrapper
confere ao baixá-la; uma vez por mês, o Dependabot propõe as versões novas das Actions e das imagens
num pull request que passa pelo mesmo CI. Só o commit em que tudo passou tem as imagens
publicadas e vai ao ar, de madrugada ou por um botão de urgência. Fora da suíte, uma carga de
correção roda contra a produção em miniatura, pelo mesmo compose do servidor e com os limites dele: em três
Contas ao mesmo tempo, cem Vendas com rede e um lote de gestos sem rede enviado duas vezes,
conferidos contra a lista de Vendas, o relatório do dia e o fechamento do caixa, que tem de dar
diferença zero.

## Implantação

Nenhum dado pessoal sai do Brasil: o banco, a aplicação e a cópia, mesmo cifrada, ficam em
servidores no país. A única exceção é o pedido de encerramento da Conta, que o ADMIN manda, só com a
identificação da Conta, ao endereço do Gmail indicado no aviso de privacidade, que informa a
transferência; ela acaba quando o e-mail de atendimento num provedor no Brasil existir. Tudo roda em contêineres numa VPS da HostGator no Brasil, e a cópia vai para o
armazenamento de objetos da AWS, também no Brasil, um segundo fornecedor, que também envia, do
Brasil, o resumo do caixa por e-mail.

O artefato é um só: o jar com a API e o aplicativo na mesma origem, numa imagem Docker que roda
sem root. O build da imagem só enxerga o que compila o jar: configuração local, chave, certificado
ou registro de execução deixados nas pastas do código ficam fora dele, para não entrarem no jar nem
serem servidos pela aplicação.

Um compose versionado descreve a máquina inteira, com os segredos só pelo nome: o proxy do HTTPS, que
obtém e renova o certificado do domínio e comprime as respostas com zstd ou gzip, e num subdomínio só
do aviso Pix exige o certificado de cliente do PSP, a aplicação, o PostgreSQL 17 e as execuções avulsas do seed,
da senha provisória para o administrador que perdeu o acesso, da suspensão imediata de uma Conta e do
reverso dela, do cadastro do webhook Pix de uma Conta, da cópia, da restauração e do encerramento de Conta. Só o proxy publica porta, e só em IPv4. Um script
versionado prepara a máquina: atualização automática de segurança, firewall, SSH só por chave, swap,
logs com prazo de sete dias, Docker e um limite de 50 conexões simultâneas por endereço de origem às
portas do proxy, contado pelo kernel antes de o proxy aceitar a conexão, para que uma única máquina
não esgote a memória dele. As imagens de terceiros que vão ao ar, a do proxy e as bases da
aplicação, do banco e da cópia, ficam presas por digest, e a verificação de avisos examina
exatamente o que está no ar.

O CI publica as imagens no registro do GitHub só no commit em que tudo passou, e o commit vai também
para dentro do aplicativo: a tela de entrada e Minha conta mostram os sete primeiros caracteres dele
como a versão que o aparelho roda, e o build feito na máquina diz local. De madrugada, uma
tarefa agendada da própria máquina pergunta à API pública do GitHub qual é o último commit aprovado e
chama o script de deploy; a agenda do GitHub atrasou horas todos os dias e cairia fora da janela. Para
urgência, um botão do CI entra no servidor por uma chave que só roda o script de deploy, e o script
só aceita um commit que já esteja no `main`. As duas versões da aplicação não cabem juntas na
memória, então a troca para a aplicação por uns 40 segundos, com o proxy segurando as requisições
novas; uma requisição em andamento no instante da troca volta com erro, e por isso o deploy de rotina
é de madrugada, e o script recusa fora da janela de manutenção o commit com migration ou que muda o
compose ou a imagem do banco. Nenhuma sonda consulta o banco: uma queda dele não pode tirar do ar uma
aplicação sem defeito.

De hora em hora, uma tarefa agendada da máquina faz o dump do banco direto para o `age`, cifrado para
a chave pública do mantenedor, e o envia para fora da máquina com uma conferência de contagens e
totais, também cifrada; a cópia de uma hora da madrugada vai para um destino guardado por mais tempo.
A tarefa não tem a chave que decifra o que guardou, e a credencial dela só grava nome novo, sem ler,
listar, apagar nem gravar por cima. Depois de cada cópia, e a cada cinco minutos com a aplicação
respondendo pelo domínio, a máquina manda a um vigia externo um sinal sem corpo; o sinal que não chega
vira um aviso ao mantenedor. A cada cinco minutos, outra tarefa conta no log as recusas dos últimos
cinco minutos e, acima de 50, manda o sinal de falha a um terceiro check: a força bruta distribuída e
a enxurrada de token falso ou de aviso Pix, que nenhum limite por origem segura, viram aviso, sem
nenhuma linha do log sair da máquina. Uma terceira tarefa conta as linhas em ERROR e, a partir de uma,
manda o sinal de falha a um quarto check: o erro da aplicação e o do aplicativo chegam ao mantenedor sem
esperar o relato do cliente. A restauração decifra direto para o `pg_restore`, num banco vazio, e só
termina com sucesso quando a conferência do banco restaurado bate com a da origem; é por ela que a
máquina se recria do zero. O passo a passo, do preparo da máquina ao ensaio local, está em
[operacao/README.md](operacao/README.md), junto com o procedimento de incidente de segurança e o
encaminhamento à Conta dos pedidos sobre dados pessoais que chegam ao mantenedor. Os comandos que
recebem o e-mail ou o nome de um negócio os leem sem que fiquem no histórico do shell.

A imagem, o CI que publica as imagens, a configuração da VPS, com o proxy do HTTPS, a aplicação e o
PostgreSQL 17 em contêineres na mesma máquina no Brasil, o deploy da madrugada por um timer da
própria máquina, a cópia cifrada de hora em hora e a restauração foram ensaiados na máquina local,
com os limites de memória da VPS (a aplicação com 1 GB, o PostgreSQL com 512 MB, o proxy com 128 MB
e a cópia com 256 MB) e a CPU dividida como no pior caso de lá: mil conexões anônimas simultâneas de
uma origem não derrubam o proxy com o limite de conexões por origem, e a recriação da máquina a
partir da cópia foi ensaiada. Tudo isso está no ar, e a instalação publicada confirmou o que o
ensaio não alcançava: o preparo numa máquina nova, o certificado do domínio, o endereço de origem
real, com o cabeçalho forjado pelo cliente descartado pelo proxy, a carga de correção em três Contas
em paralelo sem nenhum erro do servidor, a cópia chegando cifrada ao armazenamento externo sem
conseguir sobrescrever outra, e a cópia restaurada num PostgreSQL vazio, na máquina local e na
própria VPS, conferindo com a origem e com as remoções feitas depois dela reaplicadas.

## Guia de leitura do código

Atalhos para avaliar o código sem percorrer o repositório inteiro.

| O que olhar | Onde | Por que vale a leitura |
|---|---|---|
| Fronteira de módulo verificada por teste | [ModularityTests.java](src/test/java/br/com/caixasimples/ModularityTests.java) | A arquitetura falha o build quando é violada, em vez de depender de disciplina |
| PSP confinado ao adapter | [FronteiraPspTest.java](src/test/java/br/com/caixasimples/FronteiraPspTest.java) | Tipos da Efí e do Inter e dependências de `pagamentos.internal` não chegam ao contrato nem aos casos de uso; o teste prova que a regra detecta um vazamento |
| Concorrência reproduzida sem pausa | [CancelamentoERecebimentoSobConcorrenciaTest.java](src/test/java/br/com/caixasimples/vendas/CancelamentoERecebimentoSobConcorrenciaTest.java) e [EscritasConcorrentesNaVendaTest.java](src/test/java/br/com/caixasimples/vendas/EscritasConcorrentesNaVendaTest.java) | Outra transação segura a linha da raiz e só confirma quando o PostgreSQL mostra as conexões esperando por ela; o cancelamento e o recebimento de fiado são recusados inteiros por conflito de versão, sem efeito pela metade, e duas escritas na mesma Venda esperam uma pela outra em vez de gravar uma por cima da outra |
| Isolamento entre contas provado nos dois sentidos | [IsolamentoEntreContasTest.java](src/test/java/br/com/caixasimples/contas/IsolamentoEntreContasTest.java) | Segurança testada, não presumida: grava na conta A e prova que a conta B não enxerga |
| O mecanismo por trás desse isolamento | [MultiTenancyConfiguration.java](src/main/java/br/com/caixasimples/shared/internal/MultiTenancyConfiguration.java) | Filtro no Hibernate, não em cada consulta, e o que acontece quando não há tenant no contexto |
| Autorização explícita, sem anotação | [UsuarioContext.java](src/main/java/br/com/caixasimples/shared/UsuarioContext.java) | Quem chama vem do contexto, nunca de parâmetro, e cada caso de uso restrito pergunta na primeira linha se é o administrador ou o dono do caixa; o porquê de não haver `@PreAuthorize` nem tabela de rotas está escrito no lugar. [IdentidadeDoTokenFilter.java](src/main/java/br/com/caixasimples/contas/internal/IdentidadeDoTokenFilter.java) preenche os dois contextos e lê o usuário no banco a cada requisição, para inativar valer na hora e o token de versão de acesso antiga ser recusado |
| Contenção de abuso no login | [ContencaoDeLogin.java](src/main/java/br/com/caixasimples/contas/internal/ContencaoDeLogin.java) | A tentativa é contada antes do banco e do BCrypt, num passo só para as duas contagens, e devolvida se não terminar em recusa de credencial: pedidos simultâneos não furam o limite e ninguém de outra origem bloqueia o usuário legítimo. [AutenticacaoService.java](src/main/java/br/com/caixasimples/contas/application/AutenticacaoService.java) confere o e-mail inexistente contra um hash sintético, para a recusa custar o mesmo que a senha errada |
| Limite do corpo por rota | [LimiteDoCorpoFilter.java](src/main/java/br/com/caixasimples/shared/web/LimiteDoCorpoFilter.java) | O pedido que declara o tamanho é recusado pelo cabeçalho, sem ser lido, e o que chega em partes só é contado enquanto alguém o lê, sem leitura adiantada antes da recusa por falta de token; a rota sai do caminho que o servidor já decodificou, para uma letra codificada não trocar o limite do login |
| Eventos de segurança sem dado pessoal | [EventoDeSeguranca.java](src/main/java/br/com/caixasimples/shared/EventoDeSeguranca.java) e [EventosDeSegurancaHttpTest.java](src/test/java/br/com/caixasimples/contas/web/EventosDeSegurancaHttpTest.java) | Uma linha chave=valor por evento, com os ids, o motivo numa palavra fixa e a origem; todo valor vindo do cliente é limpo antes de entrar, e o teste confere cada evento pela API e a ausência de e-mail, nome, contato e senha no log |
| Gestão de usuários | [UsuarioService.java](src/main/java/br/com/caixasimples/contas/application/UsuarioService.java) | Criar usuário grava duas linhas numa transação, passa pela política de senha e pela unicidade global de e-mail; inativar nunca deixa a conta sem administrador, nem com duas inativações ao mesmo tempo, porque a linha da conta é travada antes da contagem; corrigir o nome lê o usuário com a trava da linha, para não gravar de volta a versão de acesso lida antes de uma troca de senha simultânea |
| Pagamento do plano sem guardar o código | [PlanoService.java](src/main/java/br/com/caixasimples/contas/application/PlanoService.java) e [CicloDeVencimento.java](src/main/java/br/com/caixasimples/contas/internal/CicloDeVencimento.java) | O tipo do pedido sai do estado da Conta, e não do pedido; o código é a assinatura do pedido, recalculada para conferir e nunca gravada; o teste, o ciclo mensal e os dias úteis da tolerância são só data, testáveis sem relógio; e duas aplicações do mesmo código se ordenam pela linha da Conta |
| Administração da Conta no PWA | [UsuarioController.java](src/main/java/br/com/caixasimples/contas/web/UsuarioController.java), [ConfiguracaoDaContaController.java](src/main/java/br/com/caixasimples/contas/web/ConfiguracaoDaContaController.java), [TelaDeUsuarios.tsx](frontend/src/telas/TelaDeUsuarios.tsx) e [TelaDeConfiguracao.tsx](frontend/src/telas/TelaDeConfiguracao.tsx) | A API usa a Conta do contexto e recusa o operador; o botão de ligar e desligar atualiza a identidade guardada e o menu sem recarga; o cadastro responde se a Conta já tem movimento antes de permitir desligar o controle |
| Erro do aplicativo sem dado pessoal | [ErroDoAplicativoController.java](src/main/java/br/com/caixasimples/shared/web/ErroDoAplicativoController.java) e [registroDeErros.ts](frontend/src/registroDeErros.ts) | O PWA manda só o tipo, o local no código, a tela e a versão, sem a mensagem, por um pedido que nunca põe o aparelho como sem servidor; o servidor aceita só o conjunto de caracteres que não forja outra linha e grava sem Conta, usuário nem origem; o alerta de erros conta essa linha junto com toda resposta 5xx |
| Um contrato de erro só, para o framework e para a aplicação | [TratamentoDeErrosHttp.java](src/main/java/br/com/caixasimples/shared/web/TratamentoDeErrosHttp.java) | Estende o tratador do Spring MVC em vez de substituí-lo, então corpo ilegível e recusa por perfil saem na mesma forma; cada status tem o porquê escrito no lugar, e o 404 de cada módulo fica no módulo, sem hierarquia de exceção. O molde do controller, com validação do pedido e a exceção própria traduzida no lugar, é [AutenticacaoController.java](src/main/java/br/com/caixasimples/contas/web/AutenticacaoController.java) |
| O primeiro login aplicando o catálogo uma vez | [ContaService.java](src/main/java/br/com/caixasimples/contas/application/ContaService.java) | Bloqueia a linha da Conta, marca o primeiro acesso e publica um evento síncrono para o cadastro copiar os itens na mesma transação; [PrimeiroAcessoDaContaListener.java](src/main/java/br/com/caixasimples/cadastro/internal/PrimeiroAcessoDaContaListener.java) reage sem criar dependência de Contas para o catálogo |
| Cadastro HTTP consumido pelas telas | [ProdutoController.java](src/main/java/br/com/caixasimples/cadastro/web/ProdutoController.java) e [ClienteController.java](src/main/java/br/com/caixasimples/cadastro/web/ClienteController.java) | A API conserva a Conta e o perfil fora do corpo, faz inativação lógica, com reativação, e devolve 404 para id de outra Conta; o ADMIN também remove dados pessoais do Cliente sem apagar sua linha; [TelaDeProdutos.tsx](frontend/src/telas/TelaDeProdutos.tsx) e [TelaDeClientes.tsx](frontend/src/telas/TelaDeClientes.tsx) exercitam o contrato no PWA |
| Caixa HTTP e conferência no PWA | [SessaoCaixaController.java](src/main/java/br/com/caixasimples/caixa/web/SessaoCaixaController.java) e [TelaDeCaixa.tsx](frontend/src/telas/TelaDeCaixa.tsx) | A sessão aberta vem do usuário autenticado, o histórico preserva o filtro por perfil, e só a consulta de uma sessão carrega os movimentos com a Venda de origem |
| Venda HTTP e PDV | [VendaController.java](src/main/java/br/com/caixasimples/vendas/web/VendaController.java) e [TelaDeVenda.tsx](frontend/src/telas/TelaDeVenda.tsx) | A comanda é recuperada após recarga, o pagamento dividido mostra o troco e a tela apresenta o comprovante não fiscal para imprimir ou compartilhar |
| As regras da Venda conferidas sem rede | [raizDaVenda.ts](frontend/src/offline/raizDaVenda.ts) e [vendaLocal.ts](frontend/src/offline/vendaLocal.ts) | O dispositivo aplica as regras da raiz antes de gravar cada gesto, em centavos inteiros e com o arredondamento por item do servidor; a Venda nova fica na fila quando falta rede, quando o servidor não responde ou quando o caixa já tem gesto pendente, e a Venda aberta com rede não é copiada para o dispositivo |
| Sincronização idempotente por operação | [SincronizacaoService.java](src/main/java/br/com/caixasimples/sincronizacao/application/SincronizacaoService.java) e [AplicadorDeOperacoes.java](src/main/java/br/com/caixasimples/sincronizacao/AplicadorDeOperacoes.java) | Uma transação por gesto, com o resultado gravado junto e um índice único pela Conta e pelo id da operação: o reenvio devolve o gravado e o envio simultâneo não aplica duas vezes. A ordem vem das dependências, e o que repete ou vai para revisão está escrito no lugar. Cada módulo aplica os próprios gestos pela porta, como em [GestosDaVenda.java](src/main/java/br/com/caixasimples/vendas/internal/GestosDaVenda.java), sem expor os casos de uso |
| O envio da fila e a projeção que não conta duas vezes | [envio.ts](frontend/src/offline/envio.ts) e [fila.ts](frontend/src/offline/fila.ts) | Uma rodada por vez com a trava do navegador, lotes em ordem de dependência, e cada resposta vira um estado com o porquê: sem resposta é falha, porque o servidor pode ter aplicado. O número de ordem de cada resultado, comparado ao guardado com o retrato, diz o que o servidor já contém, e o retrato lido com gesto incerto não é guardado |
| Callback Pix e conciliação | [WebhookPixController.java](src/main/java/br/com/caixasimples/vendas/web/WebhookPixController.java) e [VendaPixService.java](src/main/java/br/com/caixasimples/vendas/application/VendaPixService.java) | O aviso autenticado localiza a parcela da Conta, reconsulta o PSP e deixa a raiz confirmar uma vez; a lista de conciliação mostra ao ADMIN dinheiro recebido após fechamento ou cancelamento |
| Um rateio que fecha com o total | [RateioDoDescontoDaVenda.java](src/main/java/br/com/caixasimples/relatorios/application/RateioDoDescontoDaVenda.java) e [ArquivoDoResumoMensal.java](src/main/java/br/com/caixasimples/relatorios/web/ArquivoDoResumoMensal.java) | O desconto da venda inteira se reparte entre os itens pelo subtotal, com uma divisão que arredonda uma vez só e o centavo que sobra no maior item, o primeiro da comanda no empate, para mercadoria e serviço somarem o faturamento; o arquivo explica cada escolha do CSV que o Excel em português abre direto e por que nenhum texto digitado entra nele |
| Relatórios HTTP e no PWA | [FaturamentoController.java](src/main/java/br/com/caixasimples/relatorios/web/FaturamentoController.java) e [TelaDeRelatorios.tsx](frontend/src/telas/TelaDeRelatorios.tsx) | A tela pede o mesmo período aos três relatórios de uma vez e monta a quebra por forma de pagamento com o filtro da rota, uma chamada por forma, e pede a conferência do cartão quando o período é um dia; os casos de uso restringem as consultas ao administrador, ao plano e à Conta autenticada |
| Estoque HTTP e no PWA | [EstoqueController.java](src/main/java/br/com/caixasimples/estoque/web/EstoqueController.java) e [TelaDeEstoque.tsx](frontend/src/telas/TelaDeEstoque.tsx) | A API exige controle ligado e perfil ADMIN; a tela mostra o saldo de cada produto, com o negativo marcado e no alto do alerta, escolhe nos formulários o item tocado e calcula a diferença da contagem antes do ajuste |
| O servidor entregando o aplicativo | [PwaConfiguration.java](src/main/java/br/com/caixasimples/shared/web/PwaConfiguration.java) | Fallback de página única escrito à mão, com o porquê de cada caso: rota do cliente recebe o shell, arquivo que não existe e rota da API que não existe recebem 404, e os cabeçalhos de cache são explícitos porque o cache heurístico do navegador seguraria uma página antiga. Fora de `/api` tudo é público; a razão, e as políticas de conteúdo e de permissões que toda resposta leva, estão em [SecurityConfiguration.java](src/main/java/br/com/caixasimples/contas/internal/SecurityConfiguration.java) |
| O cliente HTTP do aplicativo | [cliente.ts](frontend/src/api/cliente.ts) | Um lugar só envia o token, aceita a renovação da sessão que fez a chamada e traduz o Problem Details em um erro com status, detalhe e mensagem por campo; nenhuma tela precisa lembrar disso. A sessão no dispositivo, e o que acontece na abertura sem rede ou na troca entre abas, está em [SessaoProvider.tsx](frontend/src/sessao/SessaoProvider.tsx); a navegação por perfil, que esconde o que a API recusaria mas não decide autorização, em [menu.ts](frontend/src/shell/menu.ts) |
| Raiz de agregado sem framework | [SessaoCaixa.java](src/main/java/br/com/caixasimples/caixa/domain/SessaoCaixa.java) | Regra de negócio e invariantes isoladas de Spring e de JPA |
| Invariante viva, recalculada a cada operação | [Venda.java](src/main/java/br/com/caixasimples/vendas/domain/Venda.java) | O total nunca fica negativo nem diverge dos itens; a Venda CONCLUIDA é coberta por pagamentos confirmados e FIADO pendente. Recebimentos não excedem o fiado; o estado remontado do banco passa pela mesma conferência |
| Pergunta entre módulos sem expor o agregado | [VendaService.java](src/main/java/br/com/caixasimples/vendas/application/VendaService.java) | A venda copia o preço do cadastro por chamada à camada de aplicação dele, recebendo um record e nunca a raiz alheia; confere o caixa por uma interface que ela mesma declara; e, ao concluir ou cancelar, publica o evento em vez de chamar quem reage |
| Comprovante como dado, não como desenho | [Comprovante.java](src/main/java/br/com/caixasimples/vendas/application/Comprovante.java) | O servidor garante o dado certo, com as linhas arredondadas pelo domínio e as parcelas gravadas; quem desenha, imprime e compartilha é a tela, que precisa fazer isso também sem conexão. O porquê de não haver PDF nem HTML está escrito no lugar |
| Efeito colateral entre módulos por evento | [VendaConcluidaListener.java](src/main/java/br/com/caixasimples/caixa/internal/VendaConcluidaListener.java) | O caixa reage à venda concluída sem que a venda o conheça: só o dinheiro em espécie entra na gaveta, com o instante da conclusão, e o lançamento acontece dentro da transação que concluiu, com o porquê escrito no lugar. [VendaCanceladaListener.java](src/main/java/br/com/caixasimples/caixa/internal/VendaCanceladaListener.java) é o oposto exato, também na transação de quem publica: o estorno espelha o que entrou, quem sabe quanto foi é a sessão, e outra operação na mesma sessão ao mesmo tempo desfaz o cancelamento inteiro em vez de perder o estorno |
| Um módulo que decide e outro que executa | [BaixaDeEstoqueListener.java](src/main/java/br/com/caixasimples/estoque/internal/BaixaDeEstoqueListener.java) | O estoque ouve o mesmo evento e decide, pela conta, se há o que baixar; o agregado é do cadastro, então a baixa é pedida pela API pública dele, e a fronteira continua verificada por compilação. [EstoqueService.java](src/main/java/br/com/caixasimples/estoque/application/EstoqueService.java) repete o desenho para o que uma pessoa aciona: ajuste, mínimo e alerta |
| Agregado que não carrega o próprio histórico | [ProdutoEntity.java](src/main/java/br/com/caixasimples/cadastro/internal/ProdutoEntity.java) | O saldo é coluna viva e o único método que a escreve exige o movimento junto; a coleção é preguiçosa de propósito, com o custo aceito escrito no lugar, porque o histórico de um produto cresce a cada venda e o produto é lido em toda venda |
| Dependência num sentido só | [CaixaParaVenda.java](src/main/java/br/com/caixasimples/vendas/CaixaParaVenda.java) | As perguntas da venda ao caixa são uma interface declarada em vendas e implementada no caixa, porque o caixa já depende de vendas para ouvir o evento e a verificação de fronteiras recusa ciclo |
| Schema gerado do framework, não adivinhado | [V8\_\_event_publication.sql](src/main/resources/db/migration/V8__event_publication.sql) | A tabela do outbox é mapeada por entidade do Modulith; a migration nasceu do DDL gerado dela, com a receita para repetir numa atualização e os dois ajustes tirados do schema oficial |
| Ouvinte assíncrono que põe a Conta antes da transação | [ResumoDoFechamentoListener.java](src/main/java/br/com/caixasimples/relatorios/internal/ResumoDoFechamentoListener.java) | O resumo do caixa por e-mail sai depois do commit, pelo outbox, sem a anotação composta do Modulith, que abriria a transação sem a Conta; a falha já anotada volta só para a entrega ser reenviada, e desiste com uma linha de erro depois de 24 horas |
| Value object monetário | [Money.java](src/main/java/br/com/caixasimples/shared/Money.java) | Arredondamento explícito no ponto de uso, sem `double` e sem construtor que reescreve valor em silêncio |
| Uma decisão explicada onde ela mora | [FusoDeReferencia.java](src/main/java/br/com/caixasimples/shared/FusoDeReferencia.java) | Por que o fuso é constante única e não coluna, e o que precisa acontecer para reabrir a decisão |
| Strategy sem `switch` | [PaymentService.java](src/main/java/br/com/caixasimples/pagamentos/application/PaymentService.java) | Forma de pagamento nova é classe nova; duas estratégias para a mesma forma derrubam a aplicação na subida, em vez de uma sobrescrever a outra em silêncio |
| A regra do troco no domínio | [ResultadoPagamento.java](src/main/java/br/com/caixasimples/pagamentos/domain/ResultadoPagamento.java) | A conta mora numa fábrica nomeada de um tipo sem framework, e não no componente do Spring que a chama |
| Membro de agregado invisível de fora | [VendaEntity.java](src/main/java/br/com/caixasimples/vendas/internal/VendaEntity.java) | As entidades de item e pagamento têm visibilidade de pacote, então a proibição de repositório para elas não depende de disciplina |
| Um módulo que só lê, garantido por construção | [VendaParaRelatorio.java](src/main/java/br/com/caixasimples/relatorios/internal/VendaParaRelatorio.java) | A mesma tabela mapeada uma segunda vez, imutável e só com o que o relatório usa, para os relatórios lerem sem importar o pacote interno de vendas nem remontar o agregado; o repositório ao lado não tem método de escrita, é onde os filtros entram como parâmetro opcional da consulta, e explica por que o executor de Specification do Spring Data ficou de fora; o porquê de o dia ser o da conclusão e de a venda dividida se repartir por parcela está em [FaturamentoService.java](src/main/java/br/com/caixasimples/relatorios/application/FaturamentoService.java) |
| Uma consulta que junta três mapeamentos e arredonda como o domínio | [ItemVendaParaRelatorioRepository.java](src/main/java/br/com/caixasimples/relatorios/internal/ItemVendaParaRelatorioRepository.java) | O ranking dos mais vendidos em duas JPQL, uma pela quantidade e outra pelo valor, escritas inteiras em vez de montadas com a ordem como parâmetro, com junções por id escritas na consulta, sem associação navegável, e o valor por item arredondado no banco pela mesma regra do comprovante; o porquê das duas ordens e de o fluxo de caixa ser o da gaveta está em [MaisVendidosService.java](src/main/java/br/com/caixasimples/relatorios/application/MaisVendidosService.java) e [FluxoDeCaixaService.java](src/main/java/br/com/caixasimples/relatorios/application/FluxoDeCaixaService.java) |
| Atributos variáveis em `JSONB` | [V2\_\_produto.sql](src/main/resources/db/migration/V2__produto.sql) | Índice GIN `jsonb_path_ops` e índice único parcial que só vale entre registros ativos |
| Vertical slice deliberado | [ClienteService.java](src/main/java/br/com/caixasimples/cadastro/internal/ClienteService.java) | Onde o projeto decide **não** aplicar DDD, porque não há invariante a proteger |

O javadoc do projeto explica **por que**, não o que o código já diz. As classes acima são um bom
exemplo disso.
