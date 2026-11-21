import type { Identidade } from '../sessao/Identidade'

/**
 * O aviso do plano que aparece em toda tela, ou nulo quando não há o que avisar.
 *
 * No só leitura, os dois perfis veem o aviso, porque o operador também deixa de vender e precisa
 * saber por quê. Antes dele, só o administrador, que é quem paga: no teste, a partir de três dias
 * antes do fim; num período pago, de sete. Depende só da identidade que a abertura do aplicativo já
 * busca, e do dia de hoje no balcão, que quem chama informa.
 */
export function avisoDoPlano(identidade: Identidade, hoje: string): string | null {
  const somenteLeitura = avisoDoSomenteLeitura(identidade, hoje)
  if (somenteLeitura) return somenteLeitura
  if (identidade.perfil !== 'ADMIN') return null
  const vencimento = identidade.vencimentoDoPlano
  const inicio = identidade.inicioDaSuspensao
  if (!vencimento || !inicio) return null

  const quem = identidade.emTeste ? 'O teste do plano' : 'O plano'
  switch (identidade.situacaoDoPlano) {
    case 'A_VENCER':
      return `${quem} vence em ${mostrarData(vencimento)}.`
    case 'VENCIDO':
      return `${quem} venceu em ${mostrarData(vencimento)}. Sem o pagamento, a Conta fica só leitura `
        + `em ${mostrarData(inicio)}.`
    default:
      return null
  }
}

/**
 * A frase do só leitura, a mesma que o servidor devolve ao recusar a venda ou o caixa, ou nula fora
 * dele. É ela que as telas mostram no lugar de começar venda, abrir caixa e receber fiado.
 *
 * A situação do servidor basta com rede. A data também conta, porque a identidade guardada é a da
 * última leitura: o aplicativo aberto de um dia para o outro, ou sem rede, chega ao início do só
 * leitura com a situação de ontem. A identidade guardada no tempo do plano gratuito não tem a data,
 * e não fica só leitura por ela.
 */
export function avisoDoSomenteLeitura(identidade: Identidade, hoje: string): string | null {
  const inicio = identidade.inicioDaSuspensao
  if (!inicio || !somenteLeitura(identidade, hoje)) return null
  return `A Conta está só leitura desde ${mostrarData(inicio)}, sem o pagamento do plano. Para vender`
    + ' e mexer no caixa de novo, o administrador aplica o código do pagamento na tela Plano.'
}

/** Se a Conta está só leitura hoje, para as telas tirarem o que o servidor recusaria. */
export function somenteLeitura(identidade: Identidade | null | undefined, hoje: string): boolean {
  const inicio = identidade?.inicioDaSuspensao
  if (!identidade || !inicio) return false
  return identidade.situacaoDoPlano === 'SOMENTE_LEITURA' || hoje >= inicio
}

function mostrarData(dia: string): string {
  const [ano, mes, data] = dia.split('-')
  return `${data}/${mes}/${ano}`
}
