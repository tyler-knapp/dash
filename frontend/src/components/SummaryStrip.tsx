import type { Summary } from '../api/budget'
import { fmt } from '../format'

/** The prototype's 4-cell strip: Income, Spent, Budgeted, Net cash flow. */
export function SummaryStrip({ summary }: { summary: Summary | null }) {
  const net = summary?.net ?? 0
  return (
    <section className="summary" aria-label="Period summary">
      <div>
        <span className="lbl">Income</span>
        <span className="big num">{summary ? fmt(summary.income) : '–'}</span>
      </div>
      <div>
        <span className="lbl">Spent</span>
        <span className="big num">{summary ? fmt(summary.spent) : '–'}</span>
      </div>
      <div>
        <span className="lbl">Budgeted</span>
        <span className="big num">{summary ? fmt(summary.budgeted, false) : '–'}</span>
      </div>
      <div>
        <span className="lbl">Net cash flow</span>
        <span className="big num" style={summary ? { color: net >= 0 ? 'var(--good)' : 'var(--bad)' } : undefined}>
          {summary ? (net >= 0 ? '+' : '') + fmt(net) : '–'}
        </span>
      </div>
    </section>
  )
}
