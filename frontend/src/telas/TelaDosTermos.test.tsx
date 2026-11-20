import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { RegisterSWOptions } from 'virtual:pwa-register/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { gravarIdentidade, gravarToken, lerIdentidade } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { ExigeSessao } from '../shell/ExigeSessao'
import { TelaDeLogin } from './TelaDeLogin'
import { TelaDosTermos } from './TelaDosTermos'
import { VERSAO_DOS_TERMOS } from './versaoDosTermos'

// O registro do service worker só existe no build do Vite. O falso guarda as opções que a tela
// passou, para o teste entregar o registro, e diz se há versão nova esperando.
const versao = vi.hoisted(() => ({
  esperando: false,
  opcoes: undefined as RegisterSWOptions | undefined,
  atualizar: vi.fn((_recarregar?: boolean) => Promise.resolve()),
}))
vi.mock('virtual:pwa-register/react', () => ({
  useRegisterSW: (opcoes?: RegisterSWOptions) => {
    versao.opcoes = opcoes
    return {
      needRefresh: [versao.esperando, () => undefined],
      offlineReady: [false, () => undefined],
      updateServiceWorker: versao.atualizar,
    }
  },
}))

beforeEach(() => {
  versao.esperando = false
  versao.opcoes = undefined
  versao.atualizar.mockClear()
})

afterEach(() => vi.restoreAllMocks())

const bia: Identidade = {
  usuarioId: 'bia', nome: 'Bia', perfil: 'ADMIN', contaId: 'conta',
  nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
}

const ana: Identidade = { ...bia, usuarioId: 'ana', nome: 'Ana', perfil: 'OPERADOR' }

function respostaJson(status: number, corpo: unknown) {
  return new Response(JSON.stringify(corpo), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/**
 * O servidor de mentira: a pergunta da abertura fica sem resposta, para a identidade só mudar pela
 * tela, e as outras rotas respondem o que o teste mandar.
 */
function servidor(respostas: Record<string, () => Promise<Response>>) {
  const chamadas: { caminho: string; corpo: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn((caminho: string, opcoes?: RequestInit) => {
    chamadas.push({ caminho, corpo: opcoes?.body ? JSON.parse(String(opcoes.body)) : undefined })
    const resposta = respostas[caminho]
    return resposta ? resposta() : new Promise<Response>(() => undefined)
  }))
  return chamadas
}

function entrarComo(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

function abrir(caminho: string) {
  render(
    <MemoryRouter initialEntries={[caminho]}>
      <SessaoProvider>
        <Routes>
          <Route path="/entrar" element={<TelaDeLogin />} />
          <Route path="/termos" element={<TelaDosTermos />} />
          <Route element={<ExigeSessao />}>
            <Route path="/" element={<p>dentro do aplicativo</p>} />
            <Route path="/vender" element={<p>no balcão</p>} />
          </Route>
        </Routes>
      </SessaoProvider>
    </MemoryRouter>,
  )
}

const aceite = () => screen.getByRole('button', { name: 'Aceito os termos' })

function recusaDaVersao() {
  return respostaJson(409, {
    title: 'Conflict', detail: 'os termos mudaram desde que o aplicativo foi aberto',
  })
}

/** Como o navegador faz depois de registrar o service worker: entrega o registro à tela. */
function entregarRegistro(registro: object) {
  act(() => versao.opcoes?.onRegisteredSW?.('/sw.js', registro as ServiceWorkerRegistration))
}

describe('TelaDosTermos', () => {
  it('é pública, mostra a data da versão e não pede aceite a quem só lê', () => {
    servidor({})
    abrir('/termos')

    expect(screen.getByRole('heading', { name: 'Termos de uso' })).toBeInTheDocument()
    const [ano, mes, dia] = VERSAO_DOS_TERMOS.split('-')
    expect(screen.getByText(new RegExp(`Versão de ${dia}/${mes}/${ano}`))).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Anexo: tratamento de dados pessoais' }))
      .toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Aceito os termos' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar para o acesso' })).toBeInTheDocument()
  })

  it('o login aponta para os termos, ao lado do aviso de privacidade', () => {
    servidor({})
    abrir('/entrar')

    expect(screen.getByRole('link', { name: 'Termos de uso' })).toHaveAttribute('href', '/termos')
    expect(screen.getByRole('link', { name: 'Aviso de privacidade' })).toBeInTheDocument()
  })

  it('com a pendência, o administrador só vê os termos, em qualquer rota, até aceitar a versão mostrada', async () => {
    entrarComo({ ...bia, termosPendentes: true })
    const chamadas = servidor({
      '/api/conta/termos': () => Promise.resolve(new Response(null, { status: 204 })),
    })
    abrir('/vender')

    expect(screen.getByRole('heading', { name: 'Termos de uso' })).toBeInTheDocument()
    expect(screen.queryByText('no balcão')).not.toBeInTheDocument()

    fireEvent.click(aceite())

    expect(await screen.findByText('no balcão')).toBeInTheDocument()
    expect(chamadas.find((chamada) => chamada.caminho === '/api/conta/termos')?.corpo)
      .toEqual({ versao: VERSAO_DOS_TERMOS })
    expect(lerIdentidade()?.termosPendentes).toBe(false)
  })

  it('o operador entra mesmo com a pendência: o aceite é do administrador', () => {
    entrarComo({ ...ana, termosPendentes: true })
    servidor({})
    abrir('/vender')

    expect(screen.getByText('no balcão')).toBeInTheDocument()
  })

  it('a senha provisória vem antes dos termos', () => {
    entrarComo({ ...bia, senhaProvisoria: true, termosPendentes: true })
    servidor({})
    abrir('/')

    expect(screen.getByRole('heading', { name: 'Escolha a sua senha' })).toBeInTheDocument()
  })

  it('sem a marca, que a identidade antiga não tem, o administrador entra direto', () => {
    entrarComo(bia)
    servidor({})
    abrir('/')

    expect(screen.getByText('dentro do aplicativo')).toBeInTheDocument()
  })

  it('a versão que o servidor já não aceita troca o aceite por atualizar o aplicativo', async () => {
    entrarComo({ ...bia, termosPendentes: true })
    const update = vi.fn(() => Promise.resolve())
    servidor({ '/api/conta/termos': () => Promise.resolve(recusaDaVersao()) })
    abrir('/')
    entregarRegistro({ update })

    fireEvent.click(aceite())

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('Há uma versão nova do aplicativo com os termos atualizados.')
    const atualizar = screen.getByRole('button', { name: 'Atualizar o aplicativo' })
    await waitFor(() => expect(atualizar).toHaveFocus())
    expect(screen.queryByRole('button', { name: 'Aceito os termos' })).not.toBeInTheDocument()
    // A versão nova começa a baixar já na recusa, antes do clique.
    expect(update).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('dentro do aplicativo')).not.toBeInTheDocument()
    expect(lerIdentidade()?.termosPendentes).toBe(true)
  })

  it('com a versão nova já baixada, a tela mostra no alto o aviso do cabeçalho, que a aplica', () => {
    versao.esperando = true
    entrarComo({ ...bia, termosPendentes: true })
    servidor({})
    abrir('/')

    expect(screen.getByRole('status')).toHaveTextContent('Nova versão disponível.')
    fireEvent.click(screen.getByRole('button', { name: 'Atualizar' }))

    expect(versao.atualizar).toHaveBeenCalledWith(true)
  })

  it('depois da recusa, com a versão nova já baixada, o botão a aplica e recarrega', async () => {
    versao.esperando = true
    entrarComo({ ...bia, termosPendentes: true })
    servidor({ '/api/conta/termos': () => Promise.resolve(recusaDaVersao()) })
    abrir('/')

    fireEvent.click(aceite())
    fireEvent.click(await screen.findByRole('button', { name: 'Atualizar o aplicativo' }))

    await waitFor(() => expect(versao.atualizar).toHaveBeenCalledWith(true))
    // O aviso do alto sai com a recusa: a saída é uma só, junto do aceite.
    expect(screen.queryByRole('button', { name: 'Atualizar' })).not.toBeInTheDocument()
  })

  it('depois da recusa, sem a versão nova ainda, o botão a procura e a aplica quando chega', async () => {
    entrarComo({ ...bia, termosPendentes: true })
    const registro = { installing: null as object | null, waiting: null, update: vi.fn() }
    // A primeira procura, na recusa, ainda não acha nada; a do clique acha e começa a baixar.
    registro.update
      .mockImplementationOnce(() => Promise.resolve())
      .mockImplementationOnce(() => {
        registro.installing = {}
        versao.esperando = true
        return Promise.resolve()
      })
    servidor({ '/api/conta/termos': () => Promise.resolve(recusaDaVersao()) })
    abrir('/')
    entregarRegistro(registro)

    fireEvent.click(aceite())
    fireEvent.click(await screen.findByRole('button', { name: 'Atualizar o aplicativo' }))

    expect(await screen.findByRole('button', { name: 'Atualizando...' })).toBeDisabled()
    await waitFor(() => expect(versao.atualizar).toHaveBeenCalledWith(true))
    expect(registro.update).toHaveBeenCalledTimes(2)
  })

  it('sem rede, avisa que o aceite precisa dela', async () => {
    entrarComo({ ...bia, termosPendentes: true })
    servidor({ '/api/conta/termos': () => Promise.reject(new TypeError('Failed to fetch')) })
    abrir('/')

    fireEvent.click(aceite())

    expect(await screen.findByRole('alert')).toHaveTextContent('Aceitar os termos precisa de rede')
    expect(lerIdentidade()?.termosPendentes).toBe(true)
  })
})
