import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchTransactions, recategorize, type Account, type Category, type Txn } from '../api/ledger'
import { fmt } from '../format'

const STEP = 150 // the prototype's "Show more" step
const SAVE_FAILED = 'That change didn’t save. Check your connection and try again.'

interface Props {
  from: string
  to: string
  title: string
  /** All transactions in the period (any filter), to pick the right empty-state wording. */
  periodCount: number | null
  accounts: Account[]
  categories: Category[]
  onToast: (msg: string) => void
  /** Called after a successful recategorize so totals elsewhere can refresh. */
  onChanged?: () => void
}

interface Filters {
  q: string
  account: string
  category: string
  showTransfers: boolean
  reviewOnly: boolean
}

type Load =
  | { kind: 'loading' }
  | { kind: 'ok'; rows: Txn[]; total: number; next: string | null; loadingMore: boolean }
  | { kind: 'error' }

/** Ledger tab (SPEC §9): filters, rows grouped by day, recategorize, "Show more" paging. */
export function LedgerView({ from, to, title, periodCount, accounts, categories, onToast, onChanged }: Props) {
  const [filters, setFilters] = useState<Filters>({ q: '', account: '', category: '', showTransfers: false, reviewOnly: false })
  const [q, setQ] = useState('') // debounced copy of filters.q
  const [load, setLoad] = useState<Load>({ kind: 'loading' })
  const [reloadKey, setReloadKey] = useState(0)
  const reqId = useRef(0)

  useEffect(() => {
    const h = setTimeout(() => setQ(filters.q), 250)
    return () => clearTimeout(h)
  }, [filters.q])

  const query = useCallback(
    (cursor: string | null) =>
      fetchTransactions({
        from,
        to,
        q: q.trim() || undefined,
        account: filters.account || undefined,
        category: filters.category || undefined,
        includeTransfers: filters.showTransfers,
        needsReview: filters.reviewOnly,
        cursor,
        limit: STEP,
      }),
    [from, to, q, filters.account, filters.category, filters.showTransfers, filters.reviewOnly],
  )

  // Any change to period or filters starts over from the first page. Stale responses are dropped.
  useEffect(() => {
    const id = ++reqId.current
    query(null).then(
      (page) => id === reqId.current && setLoad({ kind: 'ok', rows: page.transactions, total: page.total, next: page.nextCursor, loadingMore: false }),
      () => id === reqId.current && setLoad({ kind: 'error' }),
    )
  }, [query, reloadKey])

  const showMore = () => {
    if (load.kind !== 'ok' || !load.next) return
    const id = reqId.current
    setLoad({ ...load, loadingMore: true })
    query(load.next).then(
      (page) =>
        id === reqId.current &&
        setLoad((cur) =>
          cur.kind === 'ok' ? { kind: 'ok', rows: [...cur.rows, ...page.transactions], total: page.total, next: page.nextCursor, loadingMore: false } : cur,
        ),
      () => {
        if (id !== reqId.current) return
        setLoad((cur) => (cur.kind === 'ok' ? { ...cur, loadingMore: false } : cur))
        onToast('More transactions couldn’t load. Check your connection and try again.')
      },
    )
  }

  /** Does a row still belong in the list under the current filters? (Mirrors the server's rules.) */
  const stillMatches = (t: Txn) =>
    (!filters.reviewOnly || t.needsReview) &&
    (!filters.category || t.category === filters.category) &&
    (filters.showTransfers || t.category !== 'Transfer' || filters.category === 'Transfer')

  const change = (t: Txn, value: string) => {
    if (load.kind !== 'ok') return
    const [category, subcategory] = value.split('|')
    const before = load
    const updated: Txn = { ...t, category, subcategory, categorySource: 'user', reviewed: true, needsReview: false }
    const keep = stillMatches(updated)
    setLoad({
      ...load,
      rows: keep ? load.rows.map((r) => (r.id === t.id ? updated : r)) : load.rows.filter((r) => r.id !== t.id),
      total: keep ? load.total : load.total - 1,
    })
    recategorize(t.id, category, subcategory).then(
      (saved) => {
        setLoad((cur) => (cur.kind === 'ok' ? { ...cur, rows: cur.rows.map((r) => (r.id === saved.id ? saved : r)) } : cur))
        onChanged?.()
      },
      () => {
        setLoad((cur) => (cur.kind === 'ok' ? before : cur))
        onToast(SAVE_FAILED)
      },
    )
  }

  const set = <K extends keyof Filters>(k: K, v: Filters[K]) => setFilters((f) => ({ ...f, [k]: v }))
  const ledgerAccounts = accounts.filter((a) => a.role === 'ledger' && !a.hidden)

  return (
    <section style={{ display: 'grid', gap: 12 }}>
      <div className="filters">
        <input type="search" placeholder="Search merchants" aria-label="Search merchants" value={filters.q} onChange={(e) => set('q', e.target.value)} />
        <select aria-label="Filter by account" value={filters.account} onChange={(e) => set('account', e.target.value)}>
          <option value="">All accounts</option>
          {ledgerAccounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
        <select aria-label="Filter by category" value={filters.category} onChange={(e) => set('category', e.target.value)}>
          <option value="">All categories</option>
          {categories.map((c) => (
            <option key={c.name}>{c.name}</option>
          ))}
        </select>
        <label className="chk">
          <input type="checkbox" checked={filters.showTransfers} onChange={(e) => set('showTransfers', e.target.checked)} /> Show transfers
        </label>
        <label className="chk">
          <input type="checkbox" checked={filters.reviewOnly} onChange={(e) => set('reviewOnly', e.target.checked)} /> Needs review only
        </label>
      </div>
      <div className="ledger">
        <Rows load={load} title={title} periodCount={periodCount} categories={categories} onChange={change} onShowMore={showMore} onRetry={() => setReloadKey((k) => k + 1)} />
      </div>
      <p className="note">
        Every transaction is auto-categorized. Ones marked <b>Review</b> were low-confidence guesses. Change any category with its dropdown; budgets
        update immediately and the change is saved.
      </p>
    </section>
  )
}

function Rows({
  load, title, periodCount, categories, onChange, onShowMore, onRetry,
}: {
  load: Load
  title: string
  periodCount: number | null
  categories: Category[]
  onChange: (t: Txn, value: string) => void
  onShowMore: () => void
  onRetry: () => void
}) {
  if (load.kind === 'loading') return <div className="empty">Loading transactions…</div>
  if (load.kind === 'error')
    return (
      <div className="empty" role="alert">
        Your transactions couldn’t load. Check your connection and try again.
        <button type="button" className="btn ghost small" onClick={onRetry}>
          Retry
        </button>
      </div>
    )
  if (!load.rows.length)
    return (
      <div className="empty">
        {periodCount === 0
          ? `No transactions in ${title} yet. New ones appear here as your accounts sync.`
          : 'No transactions match these filters.'}
      </div>
    )

  const out: React.ReactNode[] = []
  let last = ''
  for (const t of load.rows) {
    if (t.date !== last) {
      last = t.date
      out.push(
        <div className="day" key={'d' + t.date}>
          {new Date(t.date + 'T12:00').toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })}
        </div>,
      )
    }
    out.push(<Row key={t.id} t={t} categories={categories} onChange={onChange} />)
  }
  const remaining = load.total - load.rows.length
  return (
    <>
      {out}
      {load.next && remaining > 0 && (
        <button type="button" className="more" onClick={onShowMore} disabled={load.loadingMore}>
          {load.loadingMore ? 'Loading…' : `Show ${Math.min(STEP, remaining)} more of ${remaining} remaining`}
        </button>
      )}
    </>
  )
}

function Row({ t, categories, onChange }: { t: Txn; categories: Category[]; onChange: (t: Txn, value: string) => void }) {
  const value = t.category && t.subcategory ? t.category + '|' + t.subcategory : ''
  const tag = t.reviewed ? (
    <span className="tag edited">Reviewed</span>
  ) : t.category === null ? (
    <span className="tag review">Review · uncategorized</span>
  ) : t.needsReview ? (
    <span className="tag review">Review · auto {Math.round((t.confidence ?? 0) * 100)}%</span>
  ) : (
    <span className="tag auto">Auto</span>
  )
  return (
    <div className={'tx' + (t.category === 'Transfer' ? ' isxfer' : '')}>
      <div className="merchant">{t.merchant}</div>
      <div className="acct">
        <span className="dot" style={{ background: t.accountColor ?? 'var(--muted)' }} />
        {t.accountName}
      </div>
      <div className="catcell">
        <select aria-label={`Category for ${t.merchant}`} value={value} onChange={(e) => onChange(t, e.target.value)}>
          {!value && (
            <option value="" disabled>
              Uncategorized
            </option>
          )}
          {categories.map((c) => (
            <optgroup key={c.name} label={c.name}>
              {c.subcategories.map((s) => (
                <option key={s} value={c.name + '|' + s}>
                  {c.name} › {s}
                </option>
              ))}
            </optgroup>
          ))}
        </select>
        <div className="tags">
          {tag}
          {t.pending && <span className="tag pending">Pending</span>}
        </div>
      </div>
      <div className={'amt' + (t.amount > 0 ? ' in' : '')}>
        {t.amount > 0 ? '+' : ''}
        {fmt(t.amount)}
      </div>
    </div>
  )
}
