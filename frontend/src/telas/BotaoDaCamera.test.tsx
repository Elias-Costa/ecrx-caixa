import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { BotaoDaCamera } from './BotaoDaCamera'
import { avisarLeitura, FalhaDaCamera, lerPelaCamera, prepararAviso, temCamera } from './leitorDeCodigo'

// O jsdom não tem câmera nem WebAssembly: a leitura é simulada, e o que se prova é a janela.
vi.mock('./leitorDeCodigo', async (original) => ({
  ...(await original<typeof import('./leitorDeCodigo')>()),
  temCamera: vi.fn(),
  lerPelaCamera: vi.fn(),
  prepararAviso: vi.fn(),
  avisarLeitura: vi.fn(),
}))

beforeEach(() => {
  vi.mocked(temCamera).mockReset().mockResolvedValue(true)
  vi.mocked(lerPelaCamera).mockReset()
  vi.mocked(prepararAviso).mockReset()
  vi.mocked(avisarLeitura).mockReset()
})

/** Uma leitura que só termina quando o teste manda, para ver a janela aberta antes. */
function leituraPendente() {
  let entregar: (codigo: string) => void = () => undefined
  let sinal: AbortSignal | undefined
  vi.mocked(lerPelaCamera).mockImplementation((_video, recebido) => {
    sinal = recebido
    return new Promise((resolver) => { entregar = resolver })
  })
  return { entregar: (codigo: string) => entregar(codigo), sinal: () => sinal }
}

describe('botão da câmera', () => {
  it('sem câmera no aparelho, o botão não aparece', async () => {
    vi.mocked(temCamera).mockResolvedValue(false)
    render(<BotaoDaCamera aoLer={vi.fn()} />)

    await act(async () => undefined)
    expect(temCamera).toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Ler pela câmera' })).not.toBeInTheDocument()
  })

  it('o toque abre a janela; a leitura avisa, fecha a janela e entrega o código com o foco de volta no botão', async () => {
    const leitura = leituraPendente()
    const aoLer = vi.fn()
    render(<BotaoDaCamera aoLer={aoLer} />)

    const botao = await screen.findByRole('button', { name: 'Ler pela câmera' })
    botao.focus()
    fireEvent.click(botao)
    expect(prepararAviso).toHaveBeenCalled()
    expect(await screen.findByRole('dialog', { name: 'Ler código de barras' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Abrindo a câmera...')
    expect(screen.getByRole('button', { name: 'Fechar' })).toHaveFocus()

    await act(async () => leitura.entregar('7891000311004'))

    await waitFor(() => expect(aoLer).toHaveBeenCalledWith('7891000311004'))
    expect(avisarLeitura).toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(botao).toHaveFocus()
    expect(leitura.sinal()?.aborted).toBe(true)
  })

  it('a câmera recusada mostra a frase da permissão e não entrega nada', async () => {
    vi.mocked(lerPelaCamera).mockRejectedValue(new FalhaDaCamera('permissao'))
    const aoLer = vi.fn()
    render(<BotaoDaCamera aoLer={aoLer} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Ler pela câmera' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O aparelho não deu acesso à câmera. Libere a câmera para este site nas configurações do navegador e tente de novo.')
    expect(screen.getByRole('status')).toHaveTextContent('')
    fireEvent.click(screen.getByRole('button', { name: 'Fechar' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(aoLer).not.toHaveBeenCalled()
    expect(avisarLeitura).not.toHaveBeenCalled()
  })

  it('uma falha que não é da câmera usa a frase geral', async () => {
    vi.mocked(lerPelaCamera).mockRejectedValue(new Error('inesperado'))
    render(<BotaoDaCamera aoLer={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Ler pela câmera' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível abrir a câmera. Feche e tente de novo.')
  })

  it('o Fechar apaga a câmera e não entrega nada', async () => {
    const leitura = leituraPendente()
    const aoLer = vi.fn()
    render(<BotaoDaCamera aoLer={aoLer} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Ler pela câmera' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Fechar' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(leitura.sinal()?.aborted).toBe(true)
    // A leitura que chegasse depois do Fechar não vale mais.
    await act(async () => leitura.entregar('7891000311004'))
    expect(aoLer).not.toHaveBeenCalled()
  })
})
