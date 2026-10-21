import { useCallback, useEffect, useState } from 'react'
import { exchangePublicToken, fetchItems, syncNow, type ItemView } from '../api/items'
import { fmt } from '../format'
import { PlaidLinkButton } from './PlaidLinkButton'

type Load = { kind: 'loading' } | { kind: 'ok'; items: ItemView[] } | { kind: 'error'; message: string }

const STATUS: Record<ItemView['status'], { cls: string; label: string }> = {
  ok: { cls: 'ok', label: 'Connected' },
  login_required: { cls: 'warn', label: 'Needs reconnect' },
  error: { cls: 'over', label: 'Sync error' },
}

/** Linked institutions: status, last sync, Sync now, Link new account, Reconnect (update mode). */
export function AccountsView({ onChanged }: { onChanged?: () => void } = {}) {
  const [load, setLoad] = useState<Load>({ kind: 'loading' })
  const [busy, setBusy] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const refresh = useCallback(() => {
    onChanged?.()
    return fetchItems().then(
      (items) => setLoad({ kind: 'ok', items }),
      (e: unknown) => setLoad({ kind: 'error', message: e instanceof Error ? e.message : String(e) }),
    )
  }, [onChanged])

  useEffect(() => {
    let live = true
    fetchItems().then(
      (items) => live && setLoad({ kind: 'ok', items }),
      (e: unknown) => live && setLoad({ kind: 'error', message: e instanceof Error ? e.message : String(e) }),
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
      setNotice(`Sync failed: ${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setBusy(null)
      await refresh()
    }
  }

  const onLinked = async (publicToken: string | null) => {
    if (publicToken === null) {
      // Update mode finished: the Item is re-authenticated; sync to clear its status.
      await runSync('Reconnected. ')
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
      setNotice(`Linking failed: ${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setBusy(null)
      await refresh()
    }
  }

  const onError = (message: string) => setNotice(message)

  return (
    <section className="accounts" aria-label="Linked accounts">
      <div className="toolbar">
        <PlaidLinkButton label="Link bank or card" kind="bank" onSuccess={onLinked} onError={onError} />
        <PlaidLinkButton label="Link retirement account" kind="retirement" className="btn ghost" onSuccess={onLinked} onError={onError} />
        <button type="button" className="btn ghost" onClick={() => runSync()} disabled={!!busy}>
          {busy ?? 'Sync now'}
        </button>
      </div>
      {notice && (
        <p className="note" role="status">
          {notice}
        </p>
      )}
      <Items load={load} onLinked={onLinked} onError={onError} />
    </section>
  )
}

function Items({ load, onLinked, onError }: { load: Load; onLinked: (t: string | null) => void; onError: (m: string) => void }) {
  if (load.kind === 'loading') return <div className="banner">Loading your accounts…</div>
  if (load.kind === 'error')
    return (
      <div className="banner" role="alert">
        Your accounts couldn’t load ({load.message}).
      </div>
    )
  if (!load.items.length)
    return <div className="banner">No accounts linked yet. Link a bank to start pulling transactions and balances.</div>

  return (
    <>
      {load.items.map((item) => {
        const st = STATUS[item.status]
        const pulling =
          item.products.includes('transactions') && item.transactionsUpdateStatus !== 'HISTORICAL_UPDATE_COMPLETE'
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
            {item.accounts.map((a) => (
              <div className="arow" key={a.id}>
                <div className="acct">
                  <span className="dot" style={{ background: a.displayColor ?? 'var(--muted)' }} />
                  <div>
                    <div>
                      {a.name}
                      {a.mask && <span className="meta"> ••{a.mask}</span>}
                    </div>
                    <div className="meta">
                      {a.role === 'ledger' ? 'Ledger' : 'Balance only'}
                      {a.subtype ? ` · ${a.subtype}` : ''}
                    </div>
                  </div>
                </div>
                <span className={`num ${a.currentBalance !== null && a.currentBalance < 0 ? 'neg' : ''}`}>
                  {a.currentBalance === null ? '–' : fmt(a.currentBalance)}
                </span>
              </div>
            ))}
          </div>
        )
      })}
    </>
  )
}

function syncedAgo(iso: string): string {
  const mins = Math.round((Date.now() - new Date(iso).getTime()) / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} min ago`
  const d = new Date(iso)
  return d.toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'America/New_York' })
}
