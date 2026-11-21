import { Navigate, Outlet } from 'react-router'
import { temRecurso, type RecursoDoPlano } from '../sessao/Identidade'
import { useSessao } from '../sessao/useSessao'

type Props = {
  /** Além de administrador, exige que a conta tenha ligado o controle de estoque. */
  comEstoque?: boolean
  /**
   * Além de administrador, exige o recurso na lista da identidade. O servidor manda todos; só a
   * identidade guardada no tempo do plano gratuito não os tem, até a próxima leitura dela.
   */
  recurso?: RecursoDoPlano
}

/**
 * Rota só do administrador. É conveniência de navegação, não a autorização: quem recusa de
 * verdade é o caso de uso no servidor, com 403 pelo perfil.
 */
export function ExigeAdmin({ comEstoque = false, recurso }: Props) {
  const { identidade } = useSessao()
  if (!identidade) return <Navigate to="/entrar" replace />
  if (identidade.perfil !== 'ADMIN') return <Navigate to="/" replace />
  if (recurso && !temRecurso(identidade, recurso)) return <Navigate to="/plano" replace />
  if (comEstoque && !identidade.estoqueHabilitado) return <Navigate to="/" replace />
  return <Outlet />
}
