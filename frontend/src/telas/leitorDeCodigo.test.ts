import { ZXING_WASM_VERSION as versaoDaBiblioteca } from 'barcode-detector/ponyfill'
import { ZXING_WASM_VERSION as versaoDoLeitorServido } from 'zxing-wasm/reader'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { avisarLeitura, FORMATOS, temCamera } from './leitorDeCodigo'

function comAparelhos(mediaDevices: unknown) {
  Object.defineProperty(navigator, 'mediaDevices', { value: mediaDevices, configurable: true })
}

afterEach(() => {
  Reflect.deleteProperty(navigator, 'mediaDevices')
  Reflect.deleteProperty(navigator, 'vibrate')
})

describe('leitor de código pela câmera', () => {
  it('lê só os formatos das embalagens e o Code 128 das etiquetas, sem o QR Code', () => {
    expect(FORMATOS).toEqual(['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128'])
  })

  // O .wasm servido pelo aplicativo sai do zxing-wasm instalado ao lado da biblioteca, e a cola em
  // JavaScript dela só funciona com o binário da mesma versão. Com versões diferentes, o npm daria
  // à biblioteca uma cópia própria, e as duas versões abaixo deixariam de bater.
  it('o leitor em WebAssembly servido é da versão que a biblioteca usa', () => {
    expect(versaoDoLeitorServido).toBe(versaoDaBiblioteca)
  })

  it('sem acesso a aparelhos, que é o caso fora de contexto seguro, não há câmera', async () => {
    await expect(temCamera()).resolves.toBe(false)
  })

  it('há câmera só quando o navegador lista uma entrada de vídeo', async () => {
    comAparelhos({ enumerateDevices: async () => [{ kind: 'audioinput' }, { kind: 'audiooutput' }] })
    await expect(temCamera()).resolves.toBe(false)

    comAparelhos({ enumerateDevices: async () => [{ kind: 'audioinput' }, { kind: 'videoinput' }] })
    await expect(temCamera()).resolves.toBe(true)
  })

  it('a leitura feita vibra por 100 ms onde o navegador deixa vibrar', () => {
    const vibrar = vi.fn(() => true)
    Object.defineProperty(navigator, 'vibrate', { value: vibrar, configurable: true })

    avisarLeitura()

    expect(vibrar).toHaveBeenCalledWith(100)
  })

  it('sem vibração no navegador, o aviso da leitura não falha', () => {
    expect(() => avisarLeitura()).not.toThrow()
  })

  it('a lista recusada pelo navegador vale como sem câmera', async () => {
    comAparelhos({ enumerateDevices: async () => { throw new DOMException('negado', 'NotAllowedError') } })
    await expect(temCamera()).resolves.toBe(false)
  })
})
