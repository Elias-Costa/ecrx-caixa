import { ZXING_WASM_VERSION as versaoDaBiblioteca } from 'barcode-detector/ponyfill'
import { ZXING_WASM_VERSION as versaoDoLeitorServido } from 'zxing-wasm/reader'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { avisarLeitura, cameraTraseira, FORMATOS, lerPelaCamera, temCamera } from './leitorDeCodigo'

// O leitor em WebAssembly não roda no jsdom: a biblioteca fica com a versão de verdade e um detector que não lê nada.
vi.mock('barcode-detector/ponyfill', async (original) => ({
  ...await original<typeof import('barcode-detector/ponyfill')>(),
  prepareZXingModule: vi.fn(async () => undefined),
  BarcodeDetector: class { detect = async () => [] },
}))

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

  // Sem tamanho no pedido, o navegador entrega 640 por 480, e a câmera de notebook não lê o código.
  it('pede à câmera a imagem de 1920 por 1080, a traseira onde houver', async () => {
    const pedir = vi.fn(async () => { throw new DOMException('negado', 'NotAllowedError') })
    comAparelhos({ getUserMedia: pedir })

    await expect(lerPelaCamera(document.createElement('video'), new AbortController().signal))
      .rejects.toMatchObject({ motivo: 'permissao' })

    expect(pedir).toHaveBeenCalledWith({
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 }, height: { ideal: 1080 } },
      audio: false,
    })
  })

  it('só a câmera que diz apontar para trás conta como traseira', () => {
    const video = document.createElement('video')
    const comCamera = (facingMode?: string) => Object.defineProperty(video, 'srcObject', {
      value: { getVideoTracks: () => [{ getSettings: () => ({ facingMode }) }] }, configurable: true,
    })

    comCamera('environment')
    expect(cameraTraseira(video)).toBe(true)
    comCamera('user')
    expect(cameraTraseira(video)).toBe(false)
    comCamera(undefined)
    expect(cameraTraseira(video)).toBe(false)
    Object.defineProperty(video, 'srcObject', { value: null, configurable: true })
    expect(cameraTraseira(video)).toBe(false)
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
