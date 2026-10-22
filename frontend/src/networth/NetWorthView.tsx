import type { NetWorth } from '../api/networth'
import { fmt } from '../format'
import { metaFor, shortDate } from './meta'

type Load = { kind: 'loading' } | { kind: 'ok'; nw: NetWorth } | { kind: 'error' }


/** Net worth tab, ported from the prototype's renderNetWorth(). Always current; ignores the period picker. */
export function NetWorthView({ load, onRetry }: { load: Load; onRetry: () => void }) {
  if (load.kind === 'loading') return <section className="nw"><div className="banner">Loading balances…</div></section>
  if (load.kind === 'error')
    return (
      <section className="nw">
        <div className="banner" role="alert">
          Balances couldn’t load. Check your connection and try again.
          <button type="button" className="btn ghost small" onClick={onRetry}>
            Retry
          </button>
        </div>
      </section>
    )
  const { nw } = load
  if (!nw.groups.length && !nw.withoutBalance.length)
    return <section className="nw"><div className="banner">No balances yet. They appear once your accounts are linked.</div></section>

  return (
    <section className="nw">
      <div className="summary" style={{ gridTemplateColumns: 'repeat(2,minmax(0,1fr))' }}>
        <div>
          <span className="lbl">Net worth</span>
          <span className="big num">{fmt(nw.total, false)}</span>
        </div>
        <div>
          <span className="lbl">Cash minus card balances</span>
          <span className="big num">{fmt(nw.cashMinusCards, false)}</span>
        </div>
      </div>
      {nw.groups.map((g) => (
        <div className="group" key={g.name}>
          <h2>
            <span>{g.name}</span>
            <span className="num">{fmt(g.total)}</span>
          </h2>
          {g.accounts.map((a) => (
            <div className="arow" key={a.id}>
              <div>
                <div>{a.name}</div>
                <div className="meta">{metaFor(a)}</div>
              </div>
              <span className={'num' + ((a.current ?? 0) < 0 ? ' neg' : '')}>{fmt(a.current ?? 0)}</span>
            </div>
          ))}
        </div>
      ))}
      {nw.withoutBalance.length > 0 && (
        <div className="group">
          <h2>
            <span>No balance yet</span>
          </h2>
          {nw.withoutBalance.map((a) => (
            <div className="arow" key={a.id}>
              <div>
                <div>{a.name}</div>
                <div className="meta">{a.isManual ? 'Manual · add a balance on the Accounts tab' : 'Waiting for the first sync'}</div>
              </div>
              <span className="num">–</span>
            </div>
          ))}
        </div>
      )}
      <p className="note">
        {nw.asOf ? `Balances as of ${shortDate(nw.asOf, true)}. ` : ''}Net worth is always current and doesn't follow the period picker.
        Retirement accounts show balances only; their trades stay out of the ledger.
      </p>
    </section>
  )
}
