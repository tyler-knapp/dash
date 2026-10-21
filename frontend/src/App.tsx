import { useEffect, useState } from 'react'
import { AccountsView } from './accounts/AccountsView'
import { fetchHealth, type Health } from './api/health'

type State = { kind: 'loading' } | { kind: 'ok'; health: Health } | { kind: 'error'; message: string }

/** Shell: prototype header, backend status and linked accounts. Budget/Ledger/Net worth tabs arrive in Phases 2–5. */
export default function App() {
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    let live = true
    fetchHealth()
      .then((health) => live && setState({ kind: 'ok', health }))
      .catch((e: unknown) => live && setState({ kind: 'error', message: e instanceof Error ? e.message : String(e) }))
    return () => {
      live = false
    }
  }, [])

  return (
    <div className="wrap">
      <header>
        <div>
          <div className="eyebrow">Personal Finance</div>
          <h1>Getting set up</h1>
          <div className="sub">{subline(state)}</div>
        </div>
        {state.kind === 'ok' && <span className="sample">{state.health.plaidEnv}</span>}
      </header>
      <StatusBanner state={state} />
      {state.kind === 'ok' && <AccountsView />}
    </div>
  )
}

function subline(state: State): string {
  switch (state.kind) {
    case 'loading':
      return 'Checking the server…'
    case 'ok':
      return state.health.status === 'ok' ? 'Server and database are up' : 'Server is up, database is not'
    case 'error':
      return 'Server unreachable'
  }
}

function StatusBanner({ state }: { state: State }) {
  if (state.kind === 'loading') return <div className="banner">Checking the server…</div>
  if (state.kind === 'error')
    return (
      <div className="banner" role="alert">
        Couldn’t reach the server ({state.message}). Check that the backend is running.
      </div>
    )
  const { config } = state.health
  const missing = [
    !config.plaid && 'Plaid keys',
    !config.tokenEncKey && 'token encryption key',
    !config.anthropic && 'Anthropic API key',
  ].filter(Boolean)
  return (
    <div className="banner" role="status">
      Database: {state.health.db}. Plaid: {state.health.plaidEnv}.{' '}
      {missing.length ? `Not configured yet: ${missing.join(', ')}.` : 'All keys configured.'}
    </div>
  )
}
