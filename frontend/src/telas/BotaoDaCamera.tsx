import { useEffect, useId, useRef, useState } from 'react'
import { avisarLeitura, FalhaDaCamera, lerPelaCamera, prepararAviso, temCamera } from './leitorDeCodigo'

type Props = {
  /** Recebe o código lido depois de a janela fechar, com o foco de volta no botão. */
  aoLer: (codigo: string) => void
  desligado?: boolean
}

/**
 * O botão que lê um código de barras pela câmera, uma leitura por toque: a câmera aberta leria de novo o mesmo
 * código parado na frente dela. Só aparece onde o navegador diz que há câmera, para o computador do balcão, que usa
 * o leitor USB, não ganhar um botão que não serve.
 *
 * É um código de barras desenhado, colado no campo que ele preenche: dentro do campo de texto, à direita, como o
 * olho da senha, e ao lado da lista de produtos, que já tem a seta à direita. Sem texto à vista, o nome do botão
 * fica no aria-label.
 */
export function BotaoDaCamera({ aoLer, desligado }: Props) {
  const [comCamera, setComCamera] = useState(false)
  const [aberta, setAberta] = useState(false)

  useEffect(() => {
    let montado = true
    void temCamera().then((sim) => { if (montado) setComCamera(sim) })
    return () => { montado = false }
  }, [])

  function fechar(codigo: string | undefined) {
    setAberta(false)
    if (codigo) aoLer(codigo)
  }

  if (!comCamera) return null
  return <>
    <button type="button" className="campo__camera" aria-label="Ler pela câmera" disabled={desligado}
      onClick={() => { prepararAviso(); setAberta(true) }}>
      <IconeDoCodigo />
    </button>
    {aberta && <JanelaDaCamera aoFechar={fechar} />}
  </>
}

/** Desenhado aqui, sem biblioteca de ícones, como o olho da senha: as barras de um código entre os cantos de um visor. */
function IconeDoCodigo() {
  return <svg className="campo__camera-icone" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"
    focusable="false" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"
    strokeLinejoin="round">
    <path d="M3 8V5a2 2 0 0 1 2-2h3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3" />
    <g fill="currentColor" stroke="none">
      <rect x="6.5" y="7.5" width="2" height="9" />
      <rect x="9.5" y="7.5" width="1" height="9" />
      <rect x="11.5" y="7.5" width="2" height="9" />
      <rect x="14.5" y="7.5" width="1" height="9" />
      <rect x="16.5" y="7.5" width="1" height="9" />
    </g>
  </svg>
}

/**
 * A janela modal da câmera, aberta ao montar. Toda saída passa pelo fechamento do diálogo: a leitura, o Fechar e o
 * Esc. O foco volta a quem abriu antes de o código seguir, e a câmera se apaga quando a janela some.
 */
function JanelaDaCamera({ aoFechar }: { aoFechar: (codigo: string | undefined) => void }) {
  const dialogo = useRef<HTMLDialogElement>(null)
  const video = useRef<HTMLVideoElement>(null)
  const fecharRef = useRef<HTMLButtonElement>(null)
  const quemAbriu = useRef<HTMLElement | null>(null)
  const lido = useRef<string | undefined>(undefined)
  const titulo = useId()
  const [lendo, setLendo] = useState(false)
  const [falha, setFalha] = useState<string>()

  useEffect(() => {
    const elemento = dialogo.current
    const imagem = video.current
    if (!elemento || !imagem) return
    // Já aberto é a segunda execução do efeito no modo estrito do React: quem abriu foi guardado na primeira.
    if (!elemento.open) {
      quemAbriu.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
      elemento.showModal()
      fecharRef.current?.focus()
    }
    const controle = new AbortController()
    lerPelaCamera(imagem, controle.signal).then((codigo) => {
      lido.current = codigo
      avisarLeitura()
      elemento.close()
    }).catch((erro: unknown) => {
      if (controle.signal.aborted) return
      setFalha(erro instanceof FalhaDaCamera ? erro.message : new FalhaDaCamera('outra').message)
    })
    return () => controle.abort()
  }, [])

  function aoFecharDialogo() {
    if (quemAbriu.current?.isConnected) quemAbriu.current.focus()
    aoFechar(lido.current)
  }

  return <dialog ref={dialogo} className="camera" aria-labelledby={titulo} onClose={aoFecharDialogo}>
    <h2 id={titulo} className="camera__titulo">Ler código de barras</h2>
    <video ref={video} className="camera__video" muted playsInline onPlaying={() => setLendo(true)} />
    {/* Os dois parágrafos ficam montados, para o leitor de tela anunciar quando o texto muda; a caixa vermelha
        só aparece com a falha. */}
    <p className="camera__estado" role="status">
      {falha ? '' : lendo ? 'Aponte a câmera para o código de barras.' : 'Abrindo a câmera...'}
    </p>
    <p className={falha ? 'mensagem-erro' : undefined} role="alert">{falha ?? ''}</p>
    <div className="camera__acoes">
      <button ref={fecharRef} type="button" className="botao botao--secundario" onClick={() => dialogo.current?.close()}>
        Fechar
      </button>
    </div>
  </dialog>
}
