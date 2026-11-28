import type { BarcodeDetector, BarcodeFormat } from 'barcode-detector/ponyfill'
import urlDoLeitor from 'zxing-wasm/reader/zxing_reader.wasm?url'

/**
 * Os quatro das embalagens e o Code 128 das etiquetas que a própria loja imprime. Menos formatos é leitura mais
 * rápida e menos leitura errada. O QR Code fica de fora: o de um Pix ou de um site, lido sem querer, viraria uma
 * busca por um texto longo.
 */
export const FORMATOS: BarcodeFormat[] = ['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128']

/** O intervalo entre um quadro conferido e o seguinte: rápido para a pessoa, sem ocupar o aparelho o tempo todo. */
const INTERVALO_ENTRE_QUADROS_MS = 150

/**
 * A imagem pedida à câmera. Sem tamanho no pedido, o navegador entrega 640 por 480, e a câmera de notebook, de foco
 * fixo e lente aberta, só vê o código nítido de longe, onde cada barra fina fica com menos de um pixel. Ideal, e não
 * exigido: a câmera que não chega a isso entrega a maior que tiver, e nenhuma deixa de abrir.
 */
const IMAGEM_DA_CAMERA = { width: { ideal: 1920 }, height: { ideal: 1080 } }

export type MotivoDaFalha = 'permissao' | 'sem-camera' | 'ocupada' | 'biblioteca' | 'outra'

const FRASE_DA_FALHA: Record<MotivoDaFalha, string> = {
  permissao: 'O aparelho não deu acesso à câmera. Libere a câmera para este site nas configurações do navegador e tente de novo.',
  'sem-camera': 'Nenhuma câmera foi encontrada neste aparelho.',
  ocupada: 'A câmera está em uso por outro aplicativo. Feche o outro aplicativo e tente de novo.',
  biblioteca: 'Não foi possível preparar a leitura. Recarregue o aplicativo e tente de novo.',
  outra: 'Não foi possível abrir a câmera. Feche e tente de novo.',
}

/** A falha que a janela da câmera mostra, já com a frase de tela. */
export class FalhaDaCamera extends Error {
  readonly motivo: MotivoDaFalha

  constructor(motivo: MotivoDaFalha) {
    super(FRASE_DA_FALHA[motivo])
    this.name = 'FalhaDaCamera'
    this.motivo = motivo
  }
}

/**
 * O navegador diz quais tipos de aparelho existem antes de qualquer permissão, só sem o nome deles. Fora de contexto
 * seguro não há `mediaDevices`, e a câmera não abriria de qualquer jeito.
 */
export async function temCamera(): Promise<boolean> {
  if (!navigator.mediaDevices?.enumerateDevices) return false
  try {
    const aparelhos = await navigator.mediaDevices.enumerateDevices()
    return aparelhos.some((aparelho) => aparelho.kind === 'videoinput')
  } catch {
    return false
  }
}

let detectorPronto: Promise<BarcodeDetector> | undefined

/**
 * A biblioteca e o leitor em WebAssembly só carregam no primeiro uso, e o service worker já guardou os dois na
 * instalação, para a leitura funcionar sem rede. O leitor vem da própria origem: o endereço padrão da biblioteca é
 * de outro site, que a política de conteúdo barra. A falha não fica guardada, para o toque seguinte tentar de novo.
 */
function prepararDetector(): Promise<BarcodeDetector> {
  detectorPronto ??= (async () => {
    const { BarcodeDetector: Detector, prepareZXingModule } = await import('barcode-detector/ponyfill')
    await prepareZXingModule({
      overrides: {
        locateFile: (caminho: string, prefixo: string) => caminho.endsWith('.wasm') ? urlDoLeitor : prefixo + caminho,
      },
      fireImmediately: true,
    })
    return new Detector({ formats: FORMATOS })
  })().catch(() => {
    detectorPronto = undefined
    throw new FalhaDaCamera('biblioteca')
  })
  return detectorPronto
}

function falhaAoAbrir(falha: unknown): FalhaDaCamera {
  const nome = falha instanceof DOMException ? falha.name : ''
  if (nome === 'NotAllowedError' || nome === 'SecurityError') return new FalhaDaCamera('permissao')
  if (nome === 'NotFoundError' || nome === 'OverconstrainedError') return new FalhaDaCamera('sem-camera')
  if (nome === 'NotReadableError') return new FalhaDaCamera('ocupada')
  return new FalhaDaCamera('outra')
}

function esperar(milissegundos: number, sinal: AbortSignal): Promise<void> {
  return new Promise((resolver) => {
    const relogio = setTimeout(resolver, milissegundos)
    sinal.addEventListener('abort', () => { clearTimeout(relogio); resolver() }, { once: true })
  })
}

/**
 * Abre a câmera no vídeo dado e devolve o primeiro código lido. A câmera fecha ao devolver, ao falhar e quando o
 * sinal é abortado, que é o Fechar da janela: luz da câmera acesa sem a janela aberta seria a pessoa vigiada sem
 * saber. Abortada, a promessa é recusada com o motivo do sinal.
 */
export async function lerPelaCamera(video: HTMLVideoElement, sinal: AbortSignal): Promise<string> {
  const detector = await prepararDetector()
  if (sinal.aborted) throw sinal.reason
  let fluxo: MediaStream
  try {
    // A traseira é a que aponta para o produto; no computador, que só tem a da frente, vale a que houver.
    fluxo = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'environment' }, ...IMAGEM_DA_CAMERA },
      audio: false,
    })
  } catch (falha) {
    throw falhaAoAbrir(falha)
  }
  try {
    if (sinal.aborted) throw sinal.reason
    video.srcObject = fluxo
    await video.play()
    while (!sinal.aborted) {
      // Antes do primeiro quadro não há imagem para conferir.
      if (video.readyState >= video.HAVE_CURRENT_DATA) {
        const lido = (await detector.detect(video)).find((achado) => achado.rawValue)
        if (lido) return lido.rawValue
      }
      await esperar(INTERVALO_ENTRE_QUADROS_MS, sinal)
    }
    throw sinal.reason
  } finally {
    for (const trilha of fluxo.getTracks()) trilha.stop()
    video.srcObject = null
  }
}

/**
 * Se o vídeo vem da câmera traseira, a que celular e tablet têm com foco automático. A de notebook, que aponta para a
 * pessoa e tem foco fixo, só vê nítido de longe, onde o código de barras comum fica pequeno demais: de perto, as
 * barras finas se fundem. A câmera que não diz para onde aponta, como a webcam ligada por USB, conta como não traseira.
 */
export function cameraTraseira(video: HTMLVideoElement): boolean {
  const fluxo = video.srcObject
  if (!fluxo || !('getVideoTracks' in fluxo)) return false
  return fluxo.getVideoTracks()[0]?.getSettings().facingMode === 'environment'
}

let somDoAviso: AudioContext | undefined

/**
 * O iPhone só toca o som criado ou retomado num toque da pessoa, e a leitura chega depois, sem toque nenhum. Por isso
 * o som se prepara no toque que abre a câmera.
 */
export function prepararAviso() {
  if (typeof AudioContext === 'undefined') return
  somDoAviso ??= new AudioContext()
  somDoAviso.resume().catch(() => undefined)
}

/**
 * A vibração da leitura feita, um pulso curto como o do leitor de balcão. Com a economia de bateria ligada, o Android
 * não vibra pelo navegador, e só o bipe avisa.
 */
const VIBRACAO_DA_LEITURA_MS = 100

/**
 * O bipe do leitor de balcão, que se ouve sem olhar para a tela, e uma vibração curta onde o navegador deixa vibrar;
 * o iPhone não deixa.
 */
export function avisarLeitura() {
  if ('vibrate' in navigator) navigator.vibrate(VIBRACAO_DA_LEITURA_MS)
  if (!somDoAviso) return
  const oscilador = somDoAviso.createOscillator()
  const volume = somDoAviso.createGain()
  oscilador.frequency.value = 1500
  volume.gain.value = 0.2
  oscilador.connect(volume).connect(somDoAviso.destination)
  const agora = somDoAviso.currentTime
  oscilador.start(agora)
  oscilador.stop(agora + 0.12)
}
