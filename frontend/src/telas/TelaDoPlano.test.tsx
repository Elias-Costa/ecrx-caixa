import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ErroDaApi } from '../api/cliente'
import { contas, type EstadoDoPlano, type PedidoDePlano } from '../api/contas'
import {
  fixarSessaoDaAba, gravarIdentidade, gravarToken, lerIdentidade, sessaoAtual,
} from '../sessao/armazenamento'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade } from '../sessao/Identidade'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { useSessao } from '../sessao/useSessao'
import { avisoDoPlano } from '../shell/avisoDoPlano'
import { confirmacaoAberta, responderConfirmacao } from './confirmacaoDeTeste'
import { TelaDoPlano } from './TelaDoPlano'

afterEach(() => vi.restoreAllMocks())

/** O dia do balcão em que a Conta, criada em 01/12/2026, já passou do teste e da tolerância. */
const HOJE = '2026-12-21'

/** No só leitura desde 19/12/2026: o teste venceu numa terça, 15/12, e a tolerância foi até a sexta. */
const adminSoLeitura: Identidade = {
  usuarioId: 'admin', nome: 'Ana', perfil: 'ADMIN', contaId: 'conta',
  nomeNegocio: 'Loja', estoqueHabilitado: false, plano: 'COMPLETO',
  situacaoDoPlano: 'SOMENTE_LEITURA', vencimentoDoPlano: '2026-12-15',
  inicioDaSuspensao: '2026-12-19', emTeste: true, recursos: ['RELATORIOS', 'ESTOQUE', 'MULTIUSUARIO'],
}

/** A mesma Conta depois do código aplicado no dia 21: o ciclo recomeçou nele. */
const adminEmDia: Identidade = {
  ...adminSoLeitura, situacaoDoPlano: 'EM_DIA', vencimentoDoPlano: '2027-01-21',
  inicioDaSuspensao: '2027-01-27', emTeste: false,
  recursos: ['RELATORIOS', 'ESTOQUE', 'MULTIUSUARIO', 'PIX_INTEGRADO'],
}

const daContaB: Identidade = {
  usuarioId: 'bia', nome: 'Bia', perfil: 'ADMIN', contaId: 'conta-b',
  nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false, plano: 'COMPLETO',
  situacaoDoPlano: 'EM_DIA', vencimentoDoPlano: '2027-01-05', inicioDaSuspensao: '2027-01-09',
  emTeste: true, recursos: ['RELATORIOS', 'ESTOQUE', 'MULTIUSUARIO', 'PIX_INTEGRADO'],
}

/** No teste, criada em 01/12/2026: vence em 15/12, e o primeiro mês pago começa nesse dia. */
const emTeste: EstadoDoPlano = {
  plano: 'COMPLETO', situacao: 'EM_DIA', emTeste: true, vencimento: '2026-12-15',
  inicioDaSuspensao: '2026-12-19', recursos: ['RELATORIOS', 'ESTOQUE', 'MULTIUSUARIO', 'PIX_INTEGRADO'],
  mensalidadeCompleto: 99.9,
  propostas: [{ tipo: 'RENOVACAO', plano: 'COMPLETO', valor: 99.9,
    periodoInicio: '2026-12-15', periodoFim: '2027-01-15' }],
}

const soLeitura: EstadoDoPlano = {
  ...emTeste, situacao: 'SOMENTE_LEITURA', recursos: ['RELATORIOS', 'ESTOQUE', 'MULTIUSUARIO'],
  propostas: [{ tipo: 'ADESAO', plano: 'COMPLETO', valor: 99.9 }],
}

const renovacao: PedidoDePlano = {
  id: '3c1f9a7e-2b4d-4e6f-8a9b-0c1d2e3f4a5b', tipo: 'RENOVACAO', plano: 'COMPLETO', valor: 99.9,
  periodoInicio: '2026-12-15', periodoFim: '2027-01-15', situacao: 'ABERTO',
  criadoEm: '2026-12-13T12:00:00Z',
}

const adesao: PedidoDePlano = {
  id: '8d5b2c1e-4f3a-4b6c-9d7e-1a2b3c4d5e6f', tipo: 'ADESAO', plano: 'COMPLETO', valor: 99.9,
  situacao: 'ABERTO', criadoEm: '2026-12-21T12:00:00Z',
}

/** O que o servidor responde ao código certo do pedido feito no só leitura. */
const retomado: EstadoDoPlano = {
  plano: 'COMPLETO', situacao: 'EM_DIA', emTeste: false, vencimento: '2027-01-21',
  inicioDaSuspensao: '2027-01-27', recursos: ['RELATORIOS', 'ESTOQUE', 'MULTIUSUARIO', 'PIX_INTEGRADO'],
  mensalidadeCompleto: 99.9,
  propostas: [{ tipo: 'RENOVACAO', plano: 'COMPLETO', valor: 99.9,
    periodoInicio: '2027-01-21', periodoFim: '2027-02-21' }],
}

/** O formato de moeda separa o símbolo do número com espaço inseparável; \s também o aceita. */
const pagarNoTeste = /^Pagar o Completo de 15\/12\/2026 a 14\/01\/2027 \(R\$\s99,90\)$/
const pagarNoSoLeitura = /^Pagar o Completo a partir do dia do código \(R\$\s99,90\)$/

function avisoDe(identidade: Identidade | null): string {
  return identidade ? avisoDoPlano(identidade, HOJE) ?? 'sem aviso' : 'ninguém'
}

function ComSessao({ inicial = adminSoLeitura }: { inicial?: Identidade }) {
  const [identidade, atualizarIdentidade] = useState(inicial)
  return <SessaoContext.Provider value={{
    identidade, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade, relerIdentidade: vi.fn(),
  }}>
    <p>Aviso: {avisoDe(identidade)}</p>
    <TelaDoPlano />
  </SessaoContext.Provider>
}

/** A Ana entra; a pergunta da abertura ao servidor fica sem resposta, e a identidade só muda pela tela. */
function abrirComoAna(): string {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 24 * 60 * 60 * 1000)))
  gravarIdentidade(adminSoLeitura)
  vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>(() => undefined)))
  return sessaoAtual()!
}

/** Outra aba entra na Conta B; esta continua presa à sessão com que abriu. */
function outraAbaEntraNaContaB(sessaoDestaAba: string): void {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 23 * 60 * 60 * 1000)))
  gravarIdentidade(daContaB)
  fixarSessaoDaAba(sessaoDestaAba)
}

function AvisoDaAba() {
  const { identidade } = useSessao()
  return <p>Aviso: {avisoDe(identidade)}</p>
}

/** A tela com o provedor de sessão de verdade, que grava a identidade da sessão da aba. */
function PlanoComProvedorReal() {
  return <SessaoProvider>
    <AvisoDaAba />
    <TelaDoPlano />
  </SessaoProvider>
}

describe('tela do plano', () => {
  it('no teste, mostra até quando ele vai, o que o plano inclui, a mensalidade e o aviso de NFC-e', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValue(emTeste)
    render(<ComSessao inicial={{ ...adminSoLeitura, situacaoDoPlano: 'EM_DIA' }} />)

    expect(await screen.findByText('Plano Completo, em teste')).toBeInTheDocument()
    expect(screen.getByText('O teste vai até 14/12/2026.')).toBeInTheDocument()
    expect(screen.getByText(/os relatórios de faturamento/))
      .toHaveTextContent('mais de um usuário e o Pix integrado, confirmado sem conferir o extrato. R$ 99,90 por mês')
    expect(screen.getByText(/três dias úteis para pagar/)).toBeInTheDocument()
    expect(screen.getByText('A emissão de NFC-e ainda não está disponível.')).toBeInTheDocument()
    expect(screen.queryByText(/Gratuito/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: pagarNoTeste })).toBeInTheDocument()
  })

  it('na tolerância do teste, diz em que dia a Conta fica só leitura', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...emTeste, situacao: 'VENCIDO' })
    render(<ComSessao />)

    expect(await screen.findByText('Sem o pagamento, a Conta fica só leitura em 19/12/2026.'))
      .toBeInTheDocument()
  })

  it('pede o primeiro mês no teste e mostra o texto do pedido, sem nome do negócio nem de pessoa', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValueOnce(emTeste)
      .mockResolvedValueOnce({ ...emTeste, pedidoAberto: renovacao })
    const pedir = vi.spyOn(contas, 'pedirPlano').mockResolvedValue(renovacao)
    render(<ComSessao />)

    fireEvent.click(await screen.findByRole('button', { name: pagarNoTeste }))

    await waitFor(() => expect(pedir).toHaveBeenCalledWith('COMPLETO'))
    const campo = await screen.findByLabelText('Texto do pedido')
    // O formato de moeda separa o símbolo do número com espaço inseparável.
    const texto = (campo as HTMLTextAreaElement).value.replace(/[^\S\n]/g, ' ')
    expect(texto).toBe([
      'Pedido de plano',
      `Pedido: ${renovacao.id}`,
      'Tipo: Renovação',
      'Plano: Completo',
      'Valor: R$ 99,90',
      'Período: de 15/12/2026 a 14/01/2027',
    ].join('\n'))
    expect(texto).not.toContain('Loja')
    expect(texto).not.toContain('Ana')
  })

  it('com um pedido aberto, pedir de novo pede confirmação antes de substituir', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...emTeste, pedidoAberto: renovacao })
    const pedir = vi.spyOn(contas, 'pedirPlano').mockResolvedValue(renovacao)
    render(<ComSessao />)

    fireEvent.click(await screen.findByRole('button', { name: pagarNoTeste }))

    expect(await confirmacaoAberta()).toHaveTextContent('substitui o aberto')
    await responderConfirmacao('Voltar')
    expect(pedir).not.toHaveBeenCalled()
  })

  it('no só leitura, diz desde quando, o que continua e oferece o pagamento que recomeça o mês', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValueOnce(soLeitura)
      .mockResolvedValueOnce({ ...soLeitura, pedidoAberto: adesao })
    vi.spyOn(contas, 'pedirPlano').mockResolvedValue(adesao)
    render(<ComSessao />)

    expect(await screen.findByText('Só leitura, sem o pagamento')).toBeInTheDocument()
    expect(screen.getByText('O teste foi até 14/12/2026.')).toBeInTheDocument()
    expect(screen.getByText(/A Conta está só leitura desde 19\/12\/2026: não começa venda nem abre caixa/))
      .toHaveTextContent('Fechar o caixa aberto, os relatórios e o cadastro continuam.')
    expect(screen.getAllByRole('button', { name: /^(Pagar|Renovar)/ })).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: pagarNoSoLeitura }))

    const campo = await screen.findByLabelText('Texto do pedido')
    const texto = (campo as HTMLTextAreaElement).value
    expect(texto).toContain('Tipo: Adesão')
    expect(texto).toContain('Período: começa no dia em que o código for aplicado')
  })

  it('aplica o código, relê a identidade e o aviso do só leitura sai', async () => {
    gravarToken('token-admin')
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...soLeitura, pedidoAberto: adesao })
    const aplicar = vi.spyOn(contas, 'aplicarCodigo').mockResolvedValue(retomado)
    vi.spyOn(contas, 'identidade').mockResolvedValue(adminEmDia)
    render(<ComSessao />)
    expect(screen.getByText(/^Aviso:/)).toHaveTextContent('A Conta está só leitura desde 19/12/2026')

    fireEvent.change(await screen.findByLabelText('Código de ativação'),
      { target: { value: '2jsy pfpp bna2 yxnn' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar código' }))

    await waitFor(() => expect(aplicar).toHaveBeenCalledWith(adesao.id, '2jsy pfpp bna2 yxnn'))
    expect(await screen.findByText('Plano Completo')).toBeInTheDocument()
    expect(screen.getByText('Vencimento: 21/01/2027')).toBeInTheDocument()
    expect(screen.getByRole('button',
      { name: /^Renovar o Completo de 21\/01\/2027 a 20\/02\/2027 \(R\$\s99,90\)$/ }))
      .toBeInTheDocument()
    expect(await screen.findByText('Aviso: sem aviso')).toBeInTheDocument()
  })

  it('pelo provedor de sessão real, o código aplicado leva o pagamento à identidade da sessão', async () => {
    abrirComoAna()
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...soLeitura, pedidoAberto: adesao })
    vi.spyOn(contas, 'aplicarCodigo').mockResolvedValue(retomado)
    vi.spyOn(contas, 'identidade').mockResolvedValue(adminEmDia)
    render(<PlanoComProvedorReal />)
    expect(screen.getByText(/^Aviso:/)).toHaveTextContent('só leitura')

    fireEvent.change(await screen.findByLabelText('Código de ativação'),
      { target: { value: '2jsy pfpp bna2 yxnn' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar código' }))

    expect(await screen.findByText('Aviso: sem aviso')).toBeInTheDocument()
    expect(lerIdentidade()).toEqual(adminEmDia)
  })

  it('não grava a identidade relida na sessão da Conta que outra aba abriu', async () => {
    const sessaoDaAna = abrirComoAna()
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...soLeitura, pedidoAberto: adesao })
    vi.spyOn(contas, 'aplicarCodigo').mockResolvedValue(retomado)
    // O cliente HTTP recusaria a releitura, saída depois que outra aba trocou a sessão; aqui ela
    // responde mesmo assim, para provar que o provedor também recusa a identidade.
    const releitura = vi.spyOn(contas, 'identidade').mockResolvedValue(adminEmDia)
    render(<PlanoComProvedorReal />)
    const campo = await screen.findByLabelText('Código de ativação')

    outraAbaEntraNaContaB(sessaoDaAna)
    fireEvent.change(campo, { target: { value: '2jsy pfpp bna2 yxnn' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar código' }))

    await waitFor(() => expect(screen.getByRole('button', { name: /^Renovar o Completo/ })).toBeEnabled())
    expect(releitura).toHaveBeenCalled()
    expect(lerIdentidade()).toEqual(daContaB)
  })

  it('mostra a recusa do código sem perder o pedido aberto', async () => {
    vi.spyOn(contas, 'plano').mockResolvedValue({ ...soLeitura, pedidoAberto: adesao })
    vi.spyOn(contas, 'aplicarCodigo').mockRejectedValue(
      new ErroDaApi(400, 'Bad Request', 'Este código não é o deste pedido; confira o código que você recebeu.', {}))
    render(<ComSessao />)

    fireEvent.change(await screen.findByLabelText('Código de ativação'),
      { target: { value: '0000-0000-0000-0000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Aplicar código' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Este código não é o deste pedido; confira o código que você recebeu.')
    expect(screen.getByLabelText('Texto do pedido')).toBeInTheDocument()
  })
})
