import { defaultPeriod, type Mode, type Period, type Today } from './period'

export type Tab = 'budget' | 'ledger' | 'networth' | 'accounts'
export const TABS: Tab[] = ['budget', 'ledger', 'networth', 'accounts']

export interface ViewState {
  period: Period
  tab: Tab
}

const KEY = 'pf-view' // same key the prototype used
const MODES: Mode[] = ['month', 'quarter', 'ytd', 'year', 'custom']
const isYm = (s: unknown): s is string => typeof s === 'string' && /^\d{4}-(0[1-9]|1[0-2])$/.test(s)

/** Last tab and period from localStorage (SPEC §9). Anything missing or malformed falls back to defaults. */
export function loadViewState(today: Today): ViewState {
  const fallback: ViewState = { period: defaultPeriod(today), tab: 'budget' } // the prototype opens on Budget
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) ?? 'null')
    if (!raw || typeof raw !== 'object') return fallback
    const p = raw.period ?? {}
    const period: Period = {
      mode: MODES.includes(p.mode) ? p.mode : fallback.period.mode,
      anchor: isYm(p.anchor) && p.anchor <= today.ym ? p.anchor : fallback.period.anchor,
      from: isYm(p.from) ? p.from : fallback.period.from,
      to: isYm(p.to) ? p.to : fallback.period.to,
    }
    return { period, tab: TABS.includes(raw.tab) ? raw.tab : fallback.tab }
  } catch {
    return fallback
  }
}

export function saveViewState(v: ViewState): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(v))
  } catch {
    // Private mode or blocked storage: the app works without it.
  }
}
