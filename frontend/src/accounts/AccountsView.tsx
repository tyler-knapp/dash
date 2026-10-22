import { useCallback, useEffect, useState } from 'react'
import { createManualAccount, enterBalance, isOwedType, patchAccount, type ManualType } from '../api/accounts'
import { exchangePublicToken, fetchItems, syncNow, type ItemView } from '../api/items'
import { fetchAccounts, type Account } from '../api/ledger'
import { fmt } from '../format'
import { todayInNewYork } from '../period'
import { PALETTE } from './palette'
import { PlaidLinkButton } from './PlaidLinkButton'

type Load = { kind: 'loading' } | { kind: 'ok'; items: ItemView[]; accounts: Account[] } | { kind: 'error'; message: string }

const STATUS: Record<ItemView['status'], { cls: string; label: string }> = {
  ok: { cls: 'ok', label: 'Connected' },
  login_required: { cls: 'warn', label: 'Needs reconnect' },
  error: { cls: 'over', label: 'Sync error' },
}


const errMsg = (e: unknown) => (e instanceof Error ? e.message : String(e))
const todayIso = () => {
  const t = todayInNewYork()
  return `${t.ym}-${String(t.day).padStart(2, '0')}`
}
const shortDate = (iso: string) => new Date(iso + 'T12:00').toLocaleDateString('en-US', { month: 'short', day: 'numeric' })

/** Accounts screen (SPEC §9): linked institutions, Sync now, Link, Reconnect, manual accounts and balances, rename/color/hide. */
export function AccountsView({ onChanged }: { onChanged?: () => void } = {}) {
  const [load, setLoad] = useState<Load>({ kind: 'loading' })
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)

  const fetchAll = () => Promise.all([fetchItems(), fetchAccounts()])
  const refresh = useCallback(() => {
    onChanged?.()
    return fetchAll().then(
      ([items, accounts]) => setLoad({ kind: 'ok', items, accounts }),
      (e: unknown) => setLoad({ kind: 'error', message: errMsg(e) }),
    )
  }, [onChanged])

  useEffect(() => {
    let live = true
    fetchAll().then(
      ([items, accounts]) => live && setLoad({ kind: 'ok', items, accounts }),
      (e: unknown) => live && setLoad({ kind: 'error', message: errMsg(e) }),
    )
    return () => {
      live = false
    }
  }, [])

  const runSync = async (msgPrefix = '') => {
    setBusy('Syncing…')
    try {
      const results = await syncNow()
      const added = results.reduce((a, r) => a + r.added, 0)
      const bad = results.filter((r) => r.outcome !== 'ok' && r.outcome !== 'skipped')
      setNotice(
        msgPrefix +
          (bad.length
            ? `${bad.length} institution${bad.length === 1 ? '' : 's'} couldn’t sync. See below.`
            : `Synced. ${added} new transaction${added === 1 ? '' : 's'}.`),
      )
    } catch (e) {
      setNotice(`Sync failed: ${errMsg(e)}`)
    } finally {
      setBusy(null)
      await refresh()
    }
  }

  const onLinked = async (publicToken: string | null) => {
    if (publicToken === null) {
      await runSync('Reconnected. ') // update mode: nothing to exchange; sync clears the status
      return
    }
    setBusy('Linking…')
    try {
      const { item, firstSync } = await exchangePublicToken(publicToken)
      setNotice(
        `Linked ${item.institutionName ?? 'account'}: ${item.accounts.length} account${item.accounts.length === 1 ? '' : 's'}` +
          (firstSync.added ? `, ${firstSync.added} transactions so far.` : '. Transactions will appear as Plaid finishes pulling history.'),
      )
    } catch (e) {
      setNotice(`Linking failed: ${errMsg(e)}`)
    } finally {
      setBusy(null)
      await refresh()
    }
  }

  const onError = (message: string) => setNotice(message)
  const saved = (msg: string) => {
    setNotice(msg)
    void refresh()
  }

  return (
    <section className="accounts" aria-label="Accounts">
      <div className="toolbar">
        <PlaidLinkButton label="Link bank or card" kind="bank" onSuccess={onLinked} onError={onError} />
        <PlaidLinkButton label="Link retirement account" kind="retirement" className="btn ghost" onSuccess={onLinked} onError={onError} />
        <button type="button" className="btn ghost" onClick={() => runSync()} disabled={!!busy}>
          {busy ?? 'Sync now'}
        </button>
        <button type="button" className="btn ghost" aria-expanded={adding} onClick={() => setAdding((v) => !v)}>
          Add manual account
        </button>
      </div>
      {adding && (
        <AddManualForm
          onCancel={() => setAdding(false)}
          onDone={(name) => {
            setAdding(false)
            saved(`Added ${name}.`)
          }}
          onError={onError}
        />
      )}
      {notice && (
        <p className="note" role="status">
          {notice}
        </p>
      )}
      <Groups load={load} onLinked={onLinked} onError={onError} onSaved={saved} />
    </section>
  )
}

function Groups({ load, onLinked, onError, onSaved }: {
  load: Load
  onLinked: (t: string | null) => void
  onError: (m: string) => void
  onSaved: (m: string) => void
}) {
  if (load.kind === 'loading') return <div className="banner">Loading your accounts…</div>
  if (load.kind === 'error')
    return (
      <div className="banner" role="alert">
        Your accounts couldn’t load ({load.message}).
      </div>
    )
  const { items, accounts } = load
  const manual = accounts.filter((a) => a.isManual && !a.isSample)
  const sample = accounts.filter((a) => a.isSample)
  if (!items.length && !manual.length && !sample.length)
    return <div className="banner">No accounts linked yet. Link a bank to start pulling transactions and balances, or add a manual account.</div>

  return (
    <>
      {items.map((item) => {
        const st = STATUS[item.status]
        const pulling = item.products.includes('transactions') && item.transactionsUpdateStatus !== 'HISTORICAL_UPDATE_COMPLETE'
        return (
          <div className="group" key={item.id}>
            <h2>
              <span>{item.institutionName ?? 'Institution'}</span>
              <span className={`state ${st.cls}`}>{st.label}</span>
            </h2>
            <div className="item-meta">
              <span>{item.lastSyncedAt ? `Last synced ${syncedAgo(item.lastSyncedAt)}` : 'Not synced yet'}</span>
              {pulling && item.status === 'ok' && <span> · Pulling transaction history…</span>}
              {item.status === 'error' && item.lastErrorCode && <span> · {item.lastErrorCode}</span>}
              {item.status === 'login_required' && (
                <PlaidLinkButton label="Reconnect" itemId={item.id} className="btn small" onSuccess={onLinked} onError={onError} />
              )}
            </div>
            {accounts.filter((a) => a.itemId === item.id).map((a) => (
              <AccountRow key={a.id + a.name + a.displayColor + a.hidden} a={a} onSaved={onSaved} onError={onError} />
            ))}
          </div>
        )
      })}
      {manual.length > 0 && (
        <div className="group">
          <h2>
            <span>Manual accounts</span>
          </h2>
          {manual.map((a) => (
            <AccountRow key={a.id + a.name + a.displayColor + a.hidden + a.balanceAsOf + a.currentBalance} a={a} onSaved={onSaved} onError={onError} />
          ))}
        </div>
      )}
      {sample.length > 0 && (
        <div className="group">
          <h2>
            <span>Sample data</span>
            <span className="sample">Sample</span>
          </h2>
          <div className="item-meta">Fake accounts from the build kit, for trying the app. They’re removed automatically when you link your first real account.</div>
          {sample.map((a) => (
            <AccountRow key={a.id + a.name + a.displayColor + a.hidden + a.balanceAsOf + a.currentBalance} a={a} onSaved={onSaved} onError={onError} />
          ))}
        </div>
      )}
    </>
  )
}

function AccountRow({ a, onSaved, onError }: { a: Account; onSaved: (m: string) => void; onError: (m: string) => void }) {
  const [mode, setMode] = useState<'view' | 'edit' | 'balance'>('view')
  return (
    <div className={'arow acctrow' + (a.hidden ? ' is-hidden' : '')}>
      <div className="acctline">
        <div className="acct">
          <span className="dot" style={{ background: a.displayColor ?? 'var(--muted)' }} />
          <div>
            <div>
              {a.name}
              {a.mask && <span className="meta"> ••{a.mask}</span>}
            </div>
            <div className="meta">
              {a.isManual ? 'Manual' : a.role === 'ledger' ? 'Ledger' : 'Balance only'}
              {a.subtype ? ` · ${a.subtype}` : ''}
              {a.isManual && a.balanceAsOf ? ` · updated ${shortDate(a.balanceAsOf)}` : ''}
              {a.hidden ? ' · hidden' : ''}
            </div>
          </div>
        </div>
        <div className="acct-right">
          <span className={`num ${a.currentBalance !== null && a.currentBalance < 0 ? 'neg' : ''}`}>
            {a.currentBalance === null ? '–' : fmt(a.currentBalance)}
          </span>
          <span className="acct-actions">
            {a.isManual && (
              <button type="button" className="linkbtn" onClick={() => setMode(mode === 'balance' ? 'view' : 'balance')}>
                Update balance
              </button>
            )}
            <button type="button" className="linkbtn" aria-label={`Edit ${a.name}`} onClick={() => setMode(mode === 'edit' ? 'view' : 'edit')}>
              Edit
            </button>
          </span>
        </div>
      </div>
      {mode === 'edit' && <EditForm a={a} onCancel={() => setMode('view')} onSaved={onSaved} onError={onError} />}
      {mode === 'balance' && <BalanceForm a={a} onCancel={() => setMode('view')} onSaved={onSaved} onError={onError} />}
    </div>
  )
}

function EditForm({ a, onCancel, onSaved, onError }: { a: Account; onCancel: () => void; onSaved: (m: string) => void; onError: (m: string) => void }) {
  const [name, setName] = useState(a.name)
  const [color, setColor] = useState(a.displayColor ?? PALETTE[0])
  const [hidden, setHidden] = useState(a.hidden)
  const [saving, setSaving] = useState(false)
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return onError('Name can’t be empty.')
    setSaving(true)
    try {
      await patchAccount(a.id, { name: name.trim(), displayColor: color, hidden })
      onSaved(`Saved ${name.trim()}.`)
    } catch (err) {
      onError(`That change didn’t save: ${errMsg(err)}`)
      setSaving(false)
    }
  }
  return (
    <form className="inline-form" onSubmit={submit} aria-label={`Edit ${a.name}`}>
      <label>
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
      </label>
      <fieldset className="swatches">
        <legend>Color</legend>
        {PALETTE.map((c) => (
          <button key={c} type="button" className="swatch" style={{ background: c }} aria-label={`Color ${c}`} aria-pressed={color === c} onClick={() => setColor(c)} />
        ))}
      </fieldset>
      <label className="chk">
        <input type="checkbox" checked={hidden} onChange={(e) => setHidden(e.target.checked)} /> Hide (leaves the ledger, budgets and net worth)
      </label>
      <div className="form-actions">
        <button type="submit" className="btn small" disabled={saving}>
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button type="button" className="btn ghost small" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function BalanceForm({ a, onCancel, onSaved, onError }: { a: Account; onCancel: () => void; onSaved: (m: string) => void; onError: (m: string) => void }) {
  const owed = isOwedType(a.type)
  const [amount, setAmount] = useState(a.currentBalance === null ? '' : String(owed ? Math.abs(a.currentBalance) : a.currentBalance))
  const [asOf, setAsOf] = useState(todayIso())
  const [saving, setSaving] = useState(false)
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    const v = parseFloat(amount.replace(/[^0-9.-]/g, ''))
    if (isNaN(v)) return onError('Enter a balance.')
    const current = Math.round((owed ? -Math.abs(v) : v) * 100) / 100
    setSaving(true)
    try {
      await enterBalance(a.id, asOf, current)
      onSaved(`Updated ${a.name}: ${fmt(current)} as of ${shortDate(asOf)}.`)
    } catch (err) {
      onError(`That balance didn’t save: ${errMsg(err)}`)
      setSaving(false)
    }
  }
  return (
    <form className="inline-form" onSubmit={submit} aria-label={`Update balance for ${a.name}`}>
      <label>
        {owed ? 'Amount owed' : 'Balance'}
        <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0.00" />
      </label>
      <label>
        As of
        <input type="date" value={asOf} max={todayIso()} onChange={(e) => setAsOf(e.target.value)} />
      </label>
      <div className="form-actions">
        <button type="submit" className="btn small" disabled={saving}>
          {saving ? 'Saving…' : 'Save balance'}
        </button>
        <button type="button" className="btn ghost small" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

const TYPES: [ManualType, string][] = [
  ['investment', 'Retirement or investment'],
  ['depository', 'Checking or savings'],
  ['credit', 'Credit card'],
  ['loan', 'Loan'],
  ['other', 'Other (car, property…)'],
]

function AddManualForm({ onCancel, onDone, onError }: { onCancel: () => void; onDone: (name: string) => void; onError: (m: string) => void }) {
  const [name, setName] = useState('')
  const [type, setType] = useState<ManualType>('investment')
  const [amount, setAmount] = useState('')
  const [asOf, setAsOf] = useState(todayIso())
  const [saving, setSaving] = useState(false)
  const owed = isOwedType(type)
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return onError('Give the account a name.')
    setSaving(true)
    try {
      const acct = await createManualAccount(name.trim(), type)
      const v = parseFloat(amount.replace(/[^0-9.-]/g, ''))
      if (!isNaN(v)) await enterBalance(acct.id, asOf, Math.round((owed ? -Math.abs(v) : v) * 100) / 100)
      onDone(acct.name)
    } catch (err) {
      onError(`The account wasn’t added: ${errMsg(err)}`)
      setSaving(false)
    }
  }
  return (
    <form className="inline-form group-form" onSubmit={submit} aria-label="Add manual account">
      <label>
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} placeholder="Fidelity 401(k) · former employer" />
      </label>
      <label>
        Type
        <select value={type} onChange={(e) => setType(e.target.value as ManualType)}>
          {TYPES.map(([v, l]) => (
            <option key={v} value={v}>
              {l}
            </option>
          ))}
        </select>
      </label>
      <label>
        {owed ? 'Amount owed (optional)' : 'Balance (optional)'}
        <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0.00" />
      </label>
      <label>
        As of
        <input type="date" value={asOf} max={todayIso()} onChange={(e) => setAsOf(e.target.value)} />
      </label>
      <div className="form-actions">
        <button type="submit" className="btn small" disabled={saving}>
          {saving ? 'Adding…' : 'Add account'}
        </button>
        <button type="button" className="btn ghost small" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function syncedAgo(iso: string): string {
  const mins = Math.round((Date.now() - new Date(iso).getTime()) / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} min ago`
  return new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'America/New_York' })
}
