import { useState } from 'react'
import type { CategorySummary, Summary } from '../api/budget'
import { fmt } from '../format'
import { mLabel } from '../period'

type Load = { kind: 'loading' } | { kind: 'ok'; summary: Summary } | { kind: 'error' }

interface Props {
  load: Load
  /** Budgets are editable only in Month view (SPEC §6). */
  editable: boolean
  onSetBudget: (month: string, category: string, amount: number) => void
  onRetry: () => void
}

/** Budget tab, ported from the prototype's renderBudget(). Numbers come from /api/summary. */
export function BudgetView({ load, editable, onSetBudget, onRetry }: Props) {
  const [open, setOpen] = useState<Set<string>>(new Set())

  if (load.kind === 'loading')
    return (
      <div className="cats">
        <div className="banner">Loading your budgets…</div>
      </div>
    )
  if (load.kind === 'error')
    return (
      <div className="cats">
        <div className="banner" role="alert">
          Your budgets couldn’t load. Check your connection and try again.
          <button type="button" className="btn ghost small" onClick={onRetry}>
            Retry
          </button>
        </div>
      </div>
    )

  const { summary } = load
  const month = summary.months[0]
  const toggle = (cat: string) =>
    setOpen((s) => {
      const n = new Set(s)
      if (n.has(cat)) n.delete(cat)
      else n.add(cat)
      return n
    })

  return (
    <section style={{ display: 'grid', gap: 10 }}>
      <div className="cats">
        {/* Keyed on the saved budget too, so the input resets to the server's value after a save or a failed one. */}
        {summary.categories.map((c) => (
          <CategoryCard
            key={c.name + month + editable + c.budget}
            c={c}
            open={open.has(c.name)}
            onToggle={() => toggle(c.name)}
            editable={editable}
            month={month}
            showPace={summary.periodState === 'current'}
            onSetBudget={(amount) => onSetBudget(month, c.name, amount)}
          />
        ))}
      </div>
      <p className="note">
        {editable
          ? `Budget changes apply from ${mLabel(month)} onward. Earlier months keep the budgets they had. The thin marker on each bar shows where spending would be at an even pace.`
          : `Budgets here are the monthly budgets added up across ${summary.months.length} months, using whatever amount was in effect each month. Switch to Month to edit them.`}
      </p>
    </section>
  )
}

function CategoryCard({
  c, open, onToggle, editable, month, showPace, onSetBudget,
}: {
  c: CategorySummary
  open: boolean
  onToggle: () => void
  editable: boolean
  month: string
  showPace: boolean
  onSetBudget: (amount: number) => void
}) {
  const [draft, setDraft] = useState(String(c.budget))
  const pct = c.budget ? Math.min(c.spent / c.budget, 1) * 100 : 0
  const pacePct = c.budget && showPace ? Math.min(c.expectedToDate / c.budget, 1) * 100 : null
  const id = 'b-' + c.name.replace(/\W/g, '')

  const commit = () => {
    const v = parseFloat(draft.replace(/[^0-9.]/g, ''))
    const amount = isNaN(v) ? 0 : Math.round(v * 100) / 100
    setDraft(String(amount))
    if (amount !== c.budget) onSetBudget(amount)
  }

  return (
    <div className={'cat' + (open ? ' open' : '')}>
      <div className="cat-top">
        <button type="button" className="cat-name" aria-expanded={open} onClick={onToggle}>
          <span className="chev">▶</span>
          {c.name}
        </button>
        <span className={'state ' + c.status}>{c.statusLabel}</span>
        <span className="figs">
          <span className="spent num">{fmt(c.spent)}</span> of{' '}
          {editable ? (
            <>
              <label htmlFor={id} className="sr">
                {c.name} budget for {mLabel(month)}
              </label>
              <input
                className="budget-in"
                id={id}
                inputMode="decimal"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onBlur={commit}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') e.currentTarget.blur()
                }}
              />
            </>
          ) : (
            <span className="num">{fmt(c.budget, false)}</span>
          )}
        </span>
      </div>
      <div className="bar" aria-hidden="true">
        <i className={c.status === 'ok' ? '' : c.status} style={{ width: pct + '%' }} />
        {pacePct !== null && <b style={{ left: `calc(${pacePct}% - 1px)` }} title="Where spending would be at an even pace" />}
      </div>
      {open && (
        <div className="subs">
          {c.subs.map((s) => (
            <div className="subrow" key={s.name}>
              <span>{s.name}</span>
              <span className="num">{fmt(s.spent)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
