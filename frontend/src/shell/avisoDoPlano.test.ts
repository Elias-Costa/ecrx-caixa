import { describe, expect, it } from 'vitest'
import type { Identidade } from '../sessao/Identidade'
import { avisoDoPlano, avisoDoSomenteLeitura, somenteLeitura } from './avisoDoPlano'

/** Num período pago que vence numa terça, 10/03/2026: a tolerância vai até a sexta, 13/03. */
const admin: Identidade = {
  usuarioId: '1',
  nome: 'Ana',
  perfil: 'ADMIN',
  contaId: '2',
  nomeNegocio: 'Cafeteria Aurora',
  estoqueHabilitado: false,
  plano: 'COMPLETO',
  situacaoDoPlano: 'EM_DIA',
  vencimentoDoPlano: '2026-03-10',
  inicioDaSuspensao: '2026-03-14',
  emTeste: false,
  recursos: ['RELATORIOS', 'ESTOQUE', 'MULTIUSUARIO', 'PIX_INTEGRADO'],
}

const operador: Identidade = { ...admin, perfil: 'OPERADOR' }

const SO_LEITURA = 'A Conta está só leitura desde 14/03/2026, sem o pagamento do plano. Para vender e'
  + ' mexer no caixa de novo, o administrador aplica o código do pagamento na tela Plano.'

describe('avisoDoPlano', () => {
  it('não avisa com o plano em dia', () => {
    expect(avisoDoPlano(admin, '2026-02-20')).toBeNull()
  })

  it('avisa o administrador antes do vencimento e na tolerância, com a data do só leitura', () => {
    expect(avisoDoPlano({ ...admin, situacaoDoPlano: 'A_VENCER' }, '2026-03-05'))
      .toBe('O plano vence em 10/03/2026.')
    expect(avisoDoPlano({ ...admin, situacaoDoPlano: 'VENCIDO' }, '2026-03-11'))
      .toBe('O plano venceu em 10/03/2026. Sem o pagamento, a Conta fica só leitura em 14/03/2026.')
  })

  it('no teste, o aviso fala do teste', () => {
    const noTeste: Identidade = { ...admin, emTeste: true }
    expect(avisoDoPlano({ ...noTeste, situacaoDoPlano: 'A_VENCER' }, '2026-03-08'))
      .toBe('O teste do plano vence em 10/03/2026.')
    expect(avisoDoPlano({ ...noTeste, situacaoDoPlano: 'VENCIDO' }, '2026-03-12'))
      .toBe('O teste do plano venceu em 10/03/2026. Sem o pagamento, a Conta fica só leitura em 14/03/2026.')
  })

  it('o operador não vê o vencimento, porque pagar é do administrador', () => {
    expect(avisoDoPlano({ ...operador, situacaoDoPlano: 'A_VENCER' }, '2026-03-05')).toBeNull()
    expect(avisoDoPlano({ ...operador, situacaoDoPlano: 'VENCIDO' }, '2026-03-11')).toBeNull()
  })

  it('no só leitura, os dois perfis veem a frase que o servidor devolve na recusa', () => {
    const soLeitura: Identidade = { ...admin, situacaoDoPlano: 'SOMENTE_LEITURA', recursos: ['RELATORIOS'] }
    expect(avisoDoPlano(soLeitura, '2026-03-14')).toBe(SO_LEITURA)
    expect(avisoDoPlano({ ...soLeitura, perfil: 'OPERADOR' }, '2026-03-14')).toBe(SO_LEITURA)
  })

  it('a identidade guardada antes da virada do dia fica só leitura pela data', () => {
    const lidaOntem: Identidade = { ...operador, situacaoDoPlano: 'VENCIDO' }
    expect(somenteLeitura(lidaOntem, '2026-03-13')).toBe(false)
    expect(somenteLeitura(lidaOntem, '2026-03-14')).toBe(true)
    expect(avisoDoSomenteLeitura(lidaOntem, '2026-03-14')).toBe(SO_LEITURA)
  })

  it('a identidade guardada no tempo do plano gratuito não avisa nem fica só leitura', () => {
    const gratis: Identidade = {
      ...admin, plano: 'GRATIS', situacaoDoPlano: 'SEM_MENSALIDADE', vencimentoDoPlano: undefined,
      inicioDaSuspensao: undefined, emTeste: undefined, recursos: [],
    }
    expect(avisoDoPlano(gratis, '2030-01-01')).toBeNull()
    expect(somenteLeitura(gratis, '2030-01-01')).toBe(false)
  })

  it('a identidade guardada com a suspensão antiga fica só leitura, que já começou antes dela', () => {
    const suspensa: Identidade = { ...operador, situacaoDoPlano: 'SUSPENSO', inicioDaSuspensao: '2026-03-18' }
    expect(somenteLeitura(suspensa, '2026-03-18')).toBe(true)
  })

  it('identidade guardada antes dos campos do plano não avisa', () => {
    expect(avisoDoPlano({
      ...admin, situacaoDoPlano: undefined, vencimentoDoPlano: undefined,
      inicioDaSuspensao: undefined, emTeste: undefined,
    }, '2026-03-14')).toBeNull()
    expect(somenteLeitura(null, '2026-03-14')).toBe(false)
  })
})
