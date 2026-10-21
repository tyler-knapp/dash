import { useCallback, useEffect, useMemo, useState } from 'react'
import { AccountsView } from './accounts/AccountsView'
import { fetchAccounts, fetchCategories, fetchTransactions, type Account, type Category } from './api/ledger'
import { fetchHealth, type Health } from './api/health'
import { PeriodBar } from './components/PeriodBar'
import { Toast } from './components/Toast'
import { LedgerView } from './ledger/LedgerView'
import { periodMonths, periodTitle, spanLabel, todayInNewYork, type Period } from './period'
import { loadViewState, saveViewState, type Tab } from './viewState'

const TAB_LABELS: [Tab, string][] = [
  ['budget', 'Budget'],
  ['ledger', 'Ledger'],
  ['networth', 'Net worth'],
  ['accounts', 'Accounts'],
]

type HealthState = { kind: 'loading' } | { kind: 'ok'; health: Health } | { kind: 'error'; message: string }

/** Layout from the prototype: eyebrow, period title + span, badge, period bar, tabs. Summary strip arrives in Phase 3. */
export default function App() {
  const today = useMemo(() => todayInNewYork(), [])
  const initial = useMemo(() => loadViewState(today), [today])
  const [period, setPeriod] = useState<Period>(initial.period)
  const [tab, setTab] = useState<Tab>(initial.tab)
  const [health, setHealth] = useState<HealthState>({ kind: 'loading' })
  const [accounts, setAccounts] = useState<Account[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [periodCount, setPeriodCount] = useState<number | null>(null)
  const [countKey, setCountKey] = useState(0)
  const [toast, setToast] = useState<string | null>(null)

  const months = periodMonths(period, today)
  const from = months[0]
  const to = months[months.length - 1]
  const title = periodTitle(period, months)

  useEffect(() => saveViewState({ period, tab }), [period, tab])

  useEffect(() => {
    let live = true
    fetchHealth().then(
      (h) => live && setHealth({ kind: 'ok', health: h }),
      (e: unknown) => live && setHealth({ kind: 'error', message: e instanceof Error ? e.message : String(e) }),
    )
    fetchCategories().then((c) => live && setCategories(c), () => {})
    fetchAccounts().then((a) => live && setAccounts(a), () => {})
    return () => {
      live = false
    }
  }, [])

  // Header count: every transaction in the period, transfers included (as the prototype counts them).
  useEffect(() => {
    let live = true
    fetchTransactions({ from, to, includeTransfers: true, limit: 1 }).then(
      (p) => live && setPeriodCount(p.total),
      () => live && setPeriodCount(null),
    )
    return () => {
      live = false
    }
  }, [from, to, countKey])

  const refreshAccounts = useCallback(() => {
    fetchAccounts().then(setAccounts, () => {})
    setCountKey((k) => k + 1)
  }, [])
  const clearToast = useCallback(() => setToast(null), [])

  const sample = accounts.some((a) => a.isSample)
  const sub = spanLabel(months, today) + (periodCount === null ? '' : ` · ${periodCount} transaction${periodCount === 1 ? '' : 's'}`)

  return (
    <div className="wrap">
      <header>
        <div>
          <div className="eyebrow">Personal Finance</div>
          <h1>{title}</h1>
          <div className="sub">{sub}</div>
        </div>
        {sample && <span className="sample">Sample data</span>}
      </header>

      <HealthBanner state={health} />

      <PeriodBar period={period} today={today} onChange={setPeriod} />

      <nav role="tablist">
        {TAB_LABELS.map(([t, label]) => (
          <button key={t} role="tab" type="button" id={'tab-' + t} aria-selected={tab === t} onClick={() => setTab(t)}>
            {label}
          </button>
        ))}
      </nav>

      {tab === 'budget' && <div className="banner">Budgets arrive in the next build phase.</div>}
      {tab === 'ledger' && (
        <LedgerView
          from={from}
          to={to}
          title={title}
          periodCount={periodCount}
          accounts={accounts}
          categories={categories}
          onToast={setToast}
        />
      )}
      {tab === 'networth' && <div className="banner">Net worth arrives in a later build phase.</div>}
      {tab === 'accounts' && <AccountsView onChanged={refreshAccounts} />}

      <Toast message={toast} onDone={clearToast} />
    </div>
  )
}

/** Only shown when something's wrong; a healthy server needs no banner. */
function HealthBanner({ state }: { state: HealthState }) {
  if (state.kind === 'error')
    return (
      <div className="banner" role="alert">
        Couldn’t reach the server ({state.message}). Check that the backend is running.
      </div>
    )
  if (state.kind === 'ok' && state.health.status !== 'ok')
    return (
      <div className="banner" role="alert">
        The server is up but its database isn’t responding.
      </div>
    )
  return null
}
