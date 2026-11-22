import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { ErroDaApi, SemConexao } from '../api/cliente'
import {
  contas, type EstadoDoPlano, type PedidoDePlano, type PropostaDePlano, type TipoDePedido,
} from '../api/contas'
import { moeda } from '../formatos'
import { sessaoAtual } from '../sessao/armazenamento'
import type { Plano, SituacaoDoPlano } from '../sessao/Identidade'
import { useSessao } from '../sessao/useSessao'
import { useConexao } from '../shell/useConexao'
import { erroDeCadastro } from './erroDeCadastro'
import { ErroDoCampo, Mensagem } from './Mensagem'
import { useConfirmacao } from './useConfirmacao'
import { TituloComAjuda } from './TituloComAjuda'
import { AJUDA } from './textosDeAjuda'

const NOME_DO_PLANO: Record<Plano, string> = {
  GRATIS: 'Gratuito',
  COMPLETO: 'Completo',
}

const O_QUE_INCLUI = 'Cadastro, vendas e caixa, os relatórios de faturamento, mais vendidos e fluxo de'
  + ' caixa, o resumo de cada caixa fechado por e-mail, o controle de estoque, mais de um usuário e o Pix'
  + ' integrado, confirmado sem conferir o extrato.'

const NOME_DO_TIPO: Record<TipoDePedido, string> = {
  ADESAO: 'Adesão',
  RENOVACAO: 'Renovação',
}

// Sem mensalidade e suspenso não chegam do servidor; ficam porque o tipo da identidade guardada os tem.
const NOME_DA_SITUACAO: Record<SituacaoDoPlano, string> = {
  SEM_MENSALIDADE: 'Sem mensalidade',
  EM_DIA: 'Em dia',
  A_VENCER: 'Vence nos próximos dias',
  VENCIDO: 'Vencido, na tolerância',
  SOMENTE_LEITURA: 'Só leitura, sem o pagamento',
  SUSPENSO: 'Suspenso',
}

const PEDIDO_REGISTRADO = 'Pedido registrado. Mande o texto abaixo pelo canal de atendimento e faça o'
  + ' Pix do valor; o código chega depois da conferência.'

/** A recusa do código, e se ela é do que foi digitado: sem conexão, o campo não está errado. */
type ErroDoCodigo = { texto: string; recusado: boolean }

/**
 * O pagamento do plano pela própria Conta (RF31). O administrador pede, manda o texto do pedido
 * pelo canal de atendimento e faz o Pix; depois de conferir o Pix, o mantenedor entrega um código,
 * que o administrador aplica aqui. O pedido sozinho não muda nada: o pagamento só vale com o código.
 *
 * Tudo aqui pede conexão, porque o pedido e o código são conferidos no servidor. Aplicado o
 * código, a tela pergunta de novo quem está operando, para o aviso do plano e a venda voltarem
 * em todas as telas.
 */
export function TelaDoPlano() {
  const { atualizarIdentidade } = useSessao()
  // Pedir plano e aplicar código dependem do servidor responder, não só da rede do navegador.
  const online = useConexao() === 'com-servidor'
  const [estado, setEstado] = useState<EstadoDoPlano | null>(null)
  // O erro do alto é só o da leitura da tela. Cada resposta a um toque fica perto dele: a do pedido,
  // no bloco do pedido, acima do texto; a da cópia, junto dos botões; a do código, embaixo do campo;
  // e o código aplicado, no cartão do plano, porque o bloco do pedido some e é no cartão que a
  // situação e o vencimento novos aparecem.
  const [erro, setErro] = useState<string | null>(null)
  const [erroDoPedido, setErroDoPedido] = useState<string | null>(null)
  const [pedidoRegistrado, setPedidoRegistrado] = useState(false)
  const [avisoDaCopia, setAvisoDaCopia] = useState<string | null>(null)
  const [erroDoCodigo, setErroDoCodigo] = useState<ErroDoCodigo | null>(null)
  const [codigoAplicado, setCodigoAplicado] = useState(false)
  const [codigo, setCodigo] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [confirmar, dialogoDeConfirmacao] = useConfirmacao()

  const carregar = useCallback(async () => {
    try {
      setEstado(await contas.plano())
      setErro(null)
    } catch (falha) {
      setErro(mensagemDe(falha))
    }
  }, [])

  useEffect(() => { queueMicrotask(() => void carregar()) }, [carregar])

  function limparRespostas() {
    setErroDoPedido(null)
    setPedidoRegistrado(false)
    setAvisoDaCopia(null)
    setErroDoCodigo(null)
    setCodigoAplicado(false)
  }

  async function pedir(proposta: PropostaDePlano) {
    if (!estado) return
    if (estado.pedidoAberto) {
      const sim = await confirmar({
        titulo: 'Substituir o pedido aberto?',
        texto: 'O novo pedido substitui o aberto, e o código do aberto deixa de valer.',
        acao: 'Fazer o novo pedido',
      })
      if (!sim) return
    }
    setEnviando(true)
    limparRespostas()
    try {
      await contas.pedirPlano(proposta.plano)
      await carregar()
      setPedidoRegistrado(true)
    } catch (falha) {
      setErroDoPedido(mensagemDe(falha))
    } finally {
      setEnviando(false)
    }
  }

  async function aplicar(evento: FormEvent) {
    evento.preventDefault()
    const pedido = estado?.pedidoAberto
    if (!pedido) return
    setEnviando(true)
    limparRespostas()
    try {
      setEstado(await contas.aplicarCodigo(pedido.id, codigo))
      setCodigo('')
      setCodigoAplicado(true)
      await lerIdentidadeDoServidor()
    } catch (falha) {
      // O 400 é o código recusado ou em branco; o pedido substituído e a falta de conexão também
      // ficam embaixo do campo, que é onde se tocou, mas sem marcar como errado o que se digitou.
      setErroDoCodigo({
        texto: mensagemDe(falha),
        recusado: falha instanceof ErroDaApi && falha.status === 400,
      })
    } finally {
      setEnviando(false)
    }
  }

  async function lerIdentidadeDoServidor() {
    const sessao = sessaoAtual()
    if (!sessao) return
    try {
      const atual = await contas.identidade()
      if (sessaoAtual() === sessao) atualizarIdentidade(atual, sessao)
    } catch {
      // O plano já foi aplicado; o menu se atualiza na próxima abertura do aplicativo.
    }
  }

  async function copiar(texto: string) {
    try {
      await navigator.clipboard.writeText(texto)
      setAvisoDaCopia('Texto do pedido copiado.')
    } catch {
      setAvisoDaCopia('Não foi possível copiar. Selecione o texto do pedido e copie à mão.')
    }
  }

  async function compartilhar(texto: string) {
    try {
      await navigator.share({ text: texto })
    } catch {
      // Quem cancela o compartilhamento não precisa de aviso.
    }
  }

  return <section className="cadastro">
    <TituloComAjuda titulo="Plano" ajuda={AJUDA.plano} />
    {erro && <p className="mensagem-erro" role="alert">{erro}</p>}
    {!online && <p className="mensagem-info" role="status">Pedir plano e aplicar código precisam de conexão.</p>}

    {estado && <>
      <section className="relatorios__cartao">
        <h3>Plano {NOME_DO_PLANO[estado.plano]}{estado.emTeste ? ', em teste' : ''}</h3>
        <p>{NOME_DA_SITUACAO[estado.situacao]}</p>
        {estado.vencimento && (estado.emTeste
          ? <p>O teste {testeAcabou(estado.situacao) ? 'foi' : 'vai'} até {mostrarData(diaAnterior(estado.vencimento))}.</p>
          : <p>Vencimento: {mostrarData(estado.vencimento)}</p>)}
        {estado.situacao === 'VENCIDO' && estado.inicioDaSuspensao &&
          <p>Sem o pagamento, a Conta fica só leitura em {mostrarData(estado.inicioDaSuspensao)}.</p>}
        {estado.situacao === 'SOMENTE_LEITURA' && estado.inicioDaSuspensao &&
          <p>A Conta está só leitura desde {mostrarData(estado.inicioDaSuspensao)}: não começa venda nem
            abre caixa. Fechar o caixa aberto, os relatórios e o cadastro continuam. O pagamento
            devolve tudo, e o mês pago começa no dia em que o código for aplicado.</p>}
        <Mensagem tipo="sucesso" texto={codigoAplicado ? 'Código aplicado. O plano já vale.' : null} />
      </section>

      <h3>O que o plano inclui</h3>
      <p>{O_QUE_INCLUI} {moeda.format(estado.mensalidadeCompleto)} por mês.</p>
      <p>Depois do vencimento, a Conta tem três dias úteis para pagar; depois deles, fica só leitura
        até o pagamento.</p>
      <p>A emissão de NFC-e ainda não está disponível.</p>

      <h3>Pedir</h3>
      <p className="cadastro__apoio">O pedido não muda o plano. Mande o texto do pedido pelo canal
        de atendimento e faça o Pix do valor; depois da conferência, você recebe um código e o
        aplica aqui.</p>
      {/* Com um pedido aberto, a ação da vez é aplicar o código dele. Pedir de novo continua
          possível, porque substitui o aberto, mas sem disputar a atenção com o Aplicar código. */}
      <div className="cadastro__acoes">
        {estado.propostas.map((proposta) =>
          <button className={estado.pedidoAberto ? 'botao botao--secundario' : 'botao'} type="button"
            key={`${proposta.tipo}-${proposta.plano}`}
            disabled={enviando || !online} onClick={() => void pedir(proposta)}>
            {rotuloDaProposta(proposta, estado.emTeste)}
          </button>)}
      </div>
      <Mensagem tipo="erro" texto={erroDoPedido} />

      {estado.pedidoAberto && <PedidoAberto pedido={estado.pedidoAberto} enviando={enviando}
        online={online} codigo={codigo} definirCodigo={setCodigo}
        registrado={pedidoRegistrado ? PEDIDO_REGISTRADO : null} avisoDaCopia={avisoDaCopia}
        erroDoCodigo={erroDoCodigo}
        aplicar={(evento) => void aplicar(evento)} copiar={(texto) => void copiar(texto)}
        compartilhar={(texto) => void compartilhar(texto)} />}
    </>}
    {dialogoDeConfirmacao}
  </section>
}

type PropsDoPedido = {
  pedido: PedidoDePlano
  enviando: boolean
  online: boolean
  codigo: string
  definirCodigo: (codigo: string) => void
  /** A frase de logo depois do pedido, que fica acima do texto que ela manda mandar. */
  registrado: string | null
  avisoDaCopia: string | null
  erroDoCodigo: ErroDoCodigo | null
  aplicar: (evento: FormEvent) => void
  copiar: (texto: string) => void
  compartilhar: (texto: string) => void
}

function PedidoAberto(props: PropsDoPedido) {
  const texto = textoDoPedido(props.pedido)
  const podeCompartilhar = typeof navigator.share === 'function'
  const campoDoCodigo = useRef<HTMLInputElement>(null)

  // Com a recusa, o foco vai ao campo, para corrigir ou repetir sem procurar, e o leitor de tela lê
  // a recusa junto com ele.
  useEffect(() => {
    if (props.erroDoCodigo) campoDoCodigo.current?.focus()
  }, [props.erroDoCodigo])

  return <form className="cadastro__formulario" aria-labelledby="plano-pedido-titulo"
    onSubmit={props.aplicar}>
    <h3 id="plano-pedido-titulo">Pedido à espera do código</h3>
    <Mensagem tipo="sucesso" texto={props.registrado} />
    <textarea className="campo__entrada" readOnly rows={6} value={texto}
      aria-label="Texto do pedido" />
    <div className="cadastro__acoes">
      <button className="botao botao--secundario" type="button"
        onClick={() => props.copiar(texto)}>Copiar texto</button>
      {podeCompartilhar && <button className="botao botao--secundario" type="button"
        onClick={() => props.compartilhar(texto)}>Compartilhar</button>}
    </div>
    <Mensagem tipo="info" texto={props.avisoDaCopia} />
    {/* A mensagem do campo fica fora do rótulo, que continua sendo só o nome do campo. */}
    <div className="campo">
      <label className="campo__rotulo" htmlFor="plano-codigo">Código de ativação</label>
      <input ref={campoDoCodigo} id="plano-codigo" className="campo__entrada" value={props.codigo}
        required autoComplete="off" placeholder="XXXX-XXXX-XXXX-XXXX"
        aria-invalid={props.erroDoCodigo?.recusado || undefined}
        aria-describedby={props.erroDoCodigo ? 'plano-erro-codigo' : undefined}
        onChange={(evento) => props.definirCodigo(evento.target.value)} />
      <ErroDoCampo id="plano-erro-codigo" texto={props.erroDoCodigo?.texto} />
    </div>
    <button className="botao" disabled={props.enviando || !props.online}>
      {props.enviando ? 'Aplicando...' : 'Aplicar código'}
    </button>
  </form>
}

/**
 * O que o administrador manda pelo canal de atendimento: o bastante para o mantenedor achar o
 * pedido e conferir o Pix, e nada além disso. Sem nome do negócio nem de pessoa.
 */
function textoDoPedido(pedido: PedidoDePlano): string {
  const periodo = pedido.periodoInicio && pedido.periodoFim
    ? `de ${mostrarData(pedido.periodoInicio)} a ${mostrarData(diaAnterior(pedido.periodoFim))}`
    : 'começa no dia em que o código for aplicado'
  return [
    'Pedido de plano',
    `Pedido: ${pedido.id}`,
    `Tipo: ${NOME_DO_TIPO[pedido.tipo]}`,
    `Plano: ${NOME_DO_PLANO[pedido.plano]}`,
    `Valor: ${moeda.format(pedido.valor)}`,
    `Período: ${periodo}`,
  ].join('\n')
}

/**
 * A adesão é o pagamento de quem está só leitura, e o mês começa no dia do código. No teste, o
 * primeiro mês pago é uma renovação para o servidor, mas para quem paga é o primeiro pagamento.
 */
function rotuloDaProposta(proposta: PropostaDePlano, emTeste: boolean): string {
  const valor = moeda.format(proposta.valor)
  const nome = NOME_DO_PLANO[proposta.plano]
  if (proposta.tipo === 'ADESAO') return `Pagar o ${nome} a partir do dia do código (${valor})`
  const ate = proposta.periodoFim ? mostrarData(diaAnterior(proposta.periodoFim)) : ''
  const de = proposta.periodoInicio ? mostrarData(proposta.periodoInicio) : ''
  return `${emTeste ? 'Pagar' : 'Renovar'} o ${nome} de ${de} a ${ate} (${valor})`
}

/** Vencido ou só leitura, o teste já terminou, e a frase vai ao passado. */
function testeAcabou(situacao: SituacaoDoPlano): boolean {
  return situacao === 'VENCIDO' || situacao === 'SOMENTE_LEITURA'
}

function mensagemDe(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Sem conexão com o servidor. O plano precisa de conexão.'
  return erroDeCadastro(falha)
}

function mostrarData(dia: string): string {
  const [ano, mes, data] = dia.split('-')
  return `${data}/${mes}/${ano}`
}

/** O fim do período é exclusive, o próprio vencimento: o último dia pago é o anterior. */
function diaAnterior(dia: string): string {
  const [ano, mes, data] = dia.split('-').map(Number)
  return new Date(Date.UTC(ano, mes - 1, data - 1)).toISOString().slice(0, 10)
}
