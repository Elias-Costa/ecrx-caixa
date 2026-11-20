import { createContext, useContext } from 'react'

export type ContextoDoShell = {
  definirFaixa: (texto: string | null) => void
  /** A tela de venda avisa quando mostra uma venda aberta, que a recarga da versão nova fecharia. */
  definirVendaAberta: (aberta: boolean) => void
  /**
   * A tela avisa que uma recusa mostrou o caixa de quem opera diferente do que ela via, como fechado noutro aparelho,
   * e a faixa do caixa aberto desde outro dia relê sem esperar a troca de tela.
   */
  relerOCaixa: () => void
}

export const ContextoDoShellReact = createContext<ContextoDoShell>({
  definirFaixa: () => undefined,
  definirVendaAberta: () => undefined,
  relerOCaixa: () => undefined,
})

export function useContextoDoShell() { return useContext(ContextoDoShellReact) }
