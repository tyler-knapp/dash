import {
  customOptions, mLabel, nextDisabled, selectMode, stepPeriod, type Mode, type Period, type Today,
} from '../period'

const MODES: [Mode, string][] = [
  ['month', 'Month'],
  ['quarter', 'Quarter'],
  ['ytd', 'YTD'],
  ['year', 'Year'],
  ['custom', 'Custom'],
]

/** Segmented control + arrows, or the custom from/to selects. Same markup and classes as the prototype. */
export function PeriodBar({ period, today, onChange }: { period: Period; today: Today; onChange: (p: Period) => void }) {
  const opts = customOptions(today)
  return (
    <div className="period">
      <div className="seg" role="group" aria-label="Period">
        {MODES.map(([mode, label]) => (
          <button key={mode} type="button" aria-pressed={period.mode === mode} onClick={() => onChange(selectMode(period, mode, today))}>
            {label}
          </button>
        ))}
      </div>
      {period.mode !== 'custom' ? (
        <div className="step">
          <button type="button" aria-label="Previous period" onClick={() => onChange(stepPeriod(period, -1, today))}>
            ‹
          </button>
          <button
            type="button"
            aria-label="Next period"
            disabled={nextDisabled(period, today)}
            onClick={() => onChange(stepPeriod(period, 1, today))}
          >
            ›
          </button>
        </div>
      ) : (
        <div className="custom">
          <select aria-label="From month" value={period.from} onChange={(e) => onChange({ ...period, from: e.target.value })}>
            {opts.map((m) => (
              <option key={m} value={m}>
                {mLabel(m, true)}
              </option>
            ))}
          </select>{' '}
          to{' '}
          <select aria-label="To month" value={period.to} onChange={(e) => onChange({ ...period, to: e.target.value })}>
            {opts.map((m) => (
              <option key={m} value={m}>
                {mLabel(m, true)}
              </option>
            ))}
          </select>
        </div>
      )}
    </div>
  )
}
