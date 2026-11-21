export type Perfil = 'ADMIN' | 'OPERADOR'

/** O gratuito só aparece na identidade guardada por uma versão anterior: o plano hoje é um só. */
export type Plano = 'GRATIS' | 'COMPLETO'

/**
 * Onde a Conta está no teste ou no ciclo, no dia de hoje do balcão. Sem mensalidade e suspenso só
 * aparecem na identidade guardada por uma versão anterior; o servidor manda os outros quatro.
 */
export type SituacaoDoPlano =
  | 'SEM_MENSALIDADE' | 'EM_DIA' | 'A_VENCER' | 'VENCIDO' | 'SOMENTE_LEITURA' | 'SUSPENSO'

export type RecursoDoPlano = 'RELATORIOS' | 'ESTOQUE' | 'MULTIUSUARIO' | 'PIX_INTEGRADO'

/**
 * Quem está operando e em que negócio, como o servidor responde em /api/auth/eu.
 *
 * Os campos do plano são opcionais porque a identidade guardada no aparelho por uma versão
 * anterior não os tem; a abertura do aplicativo pergunta de novo ao servidor e a substitui. As
 * datas vêm como AAAA-MM-DD e faltam na identidade guardada no tempo do plano gratuito.
 */
export type Identidade = {
  usuarioId: string
  nome: string
  perfil: Perfil
  contaId: string
  nomeNegocio: string
  tipoNegocio?: string
  estoqueHabilitado: boolean
  /**
   * Se o cartão exige o NSU do comprovante da maquininha. Guardado para o aparelho recusar a parcela
   * sem ele antes de gravá-la sem rede; falta na identidade guardada antes dele, e então não exige.
   */
  nsuObrigatorio?: boolean
  plano?: Plano
  situacaoDoPlano?: SituacaoDoPlano
  /** O fim do teste ou do último período pago: o primeiro dia sem pagamento. */
  vencimentoDoPlano?: string
  /** O primeiro dia em só leitura, se o pagamento não chegar até lá. */
  inicioDaSuspensao?: string
  /** Os recursos que valem hoje; no só leitura, sem o Pix integrado. */
  recursos?: RecursoDoPlano[]
  /**
   * Se nenhum período foi pago ainda, e o vencimento é o fim do teste. Falta na identidade guardada
   * antes da marca, e então o aviso fala do plano, e não do teste.
   */
  emTeste?: boolean
  /**
   * A senha foi escolhida por outra pessoa, que a conhece, e o aplicativo só mostra a tela de troca
   * até a pessoa escolher a dela. Falta na identidade guardada antes da marca, e então não pede.
   */
  senhaProvisoria?: boolean
  /**
   * A Conta ainda não aceitou a versão vigente dos termos de uso, e o aplicativo só mostra a tela dos
   * termos ao administrador até o aceite; o operador entra normalmente. Falta na identidade guardada
   * antes da marca, e então não pede: a abertura do aplicativo pergunta de novo ao servidor.
   */
  termosPendentes?: boolean
  /**
   * A chave Pix da Conta, que o aparelho põe no QR estático do Pix manual, inclusive sem rede. Falta
   * enquanto o administrador não cadastra uma, e então o Pix manual é lançado sem QR.
   */
  chavePix?: string
  /** A cidade do recebedor no QR; falta junto com a chave. */
  cidadePix?: string
  /**
   * A Conta pode cobrar pelo Pix integrado hoje: não está só leitura e o provedor está configurado. Falta na identidade guardada antes da marca, e então não oferece: o Pix manual
   * vale em todo caso.
   */
  pixIntegrado?: boolean
}

/**
 * Se o plano dá o recurso hoje, para o menu e as rotas decidirem o que oferecer.
 *
 * Sem a lista, que a identidade guardada antes dela não tem, responde que sim: quem recusa de
 * verdade é o servidor, e a resposta da abertura do aplicativo traz a lista logo em seguida.
 */
export function temRecurso(identidade: Identidade, recurso: RecursoDoPlano): boolean {
  return identidade.recursos === undefined || identidade.recursos.includes(recurso)
}
