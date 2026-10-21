/**
 * Period logic ported from reference/prototype.html (ym, addM, rangeYm, daysIn, elapsed,
 * periodMonths, periodTitle, stepSize, renderHeader's span and next-arrow rules).
 * "Today" is taken in America/New_York (SPEC §6), not the browser's zone.
 */
export type Mode = 'month' | 'quarter' | 'ytd' | 'year' | 'custom'

export interface Period {
  mode: Mode
  anchor: string // YYYY-MM
  from: string // custom range
  to: string
}

export interface Today {
  ym: string
  day: number
}

export const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

export function ym(y: number, m: number): string {
  while (m < 1) {
    m += 12
    y--
  }
  while (m > 12) {
    m -= 12
    y++
  }
  return y + '-' + String(m).padStart(2, '0')
}

export function parseYm(s: string): [number, number] {
  const [a, b] = s.split('-')
  return [+a, +b]
}

export const addM = (s: string, n: number) => {
  const [y, m] = parseYm(s)
  return ym(y, m + n)
}

export function rangeYm(a: string, b: string): string[] {
  const out: string[] = []
  let c = a
  while (c <= b) {
    out.push(c)
    c = addM(c, 1)
  }
  return out
}

export function daysIn(s: string): number {
  const [y, m] = parseYm(s)
  return new Date(y, m, 0).getDate()
}

/** Share of a month that has passed: 1 for past months, day/daysIn for this month, 0 for future. */
export function elapsed(s: string, today: Today): number {
  if (s < today.ym) return 1
  if (s > today.ym) return 0
  return today.day / daysIn(s)
}

export function mLabel(s: string, short = false): string {
  const [y, m] = parseYm(s)
  return (short ? MONTHS[m - 1].slice(0, 3) : MONTHS[m - 1]) + ' ' + y
}

export function todayInNewYork(now: Date = new Date()): Today {
  // en-CA formats as YYYY-MM-DD.
  const s = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/New_York',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now)
  return { ym: s.slice(0, 7), day: +s.slice(8, 10) }
}

export function defaultPeriod(today: Today): Period {
  return { mode: 'month', anchor: today.ym, from: addM(today.ym, -5), to: today.ym }
}

export function periodMonths(p: Period, today: Today): string[] {
  const [y, m] = parseYm(p.anchor)
  switch (p.mode) {
    case 'month':
      return [p.anchor]
    case 'quarter': {
      const q = Math.floor((m - 1) / 3) * 3 + 1
      return rangeYm(ym(y, q), ym(y, q + 2))
    }
    case 'ytd': {
      const end = y === +today.ym.slice(0, 4) ? today.ym : ym(y, 12)
      return rangeYm(ym(y, 1), end)
    }
    case 'year':
      return rangeYm(ym(y, 1), ym(y, 12))
    case 'custom':
      return p.from <= p.to ? rangeYm(p.from, p.to) : rangeYm(p.to, p.from)
  }
}

export function periodTitle(p: Period, ms: string[]): string {
  const [y, m] = parseYm(p.anchor)
  switch (p.mode) {
    case 'month':
      return mLabel(p.anchor)
    case 'quarter':
      return 'Q' + (Math.floor((m - 1) / 3) + 1) + ' ' + y
    case 'ytd':
      return y + ' year to date'
    case 'year':
      return String(y)
    case 'custom':
      return ms.length === 1 ? mLabel(ms[0]) : mLabel(ms[0], true) + ' – ' + mLabel(ms[ms.length - 1], true)
  }
}

export function stepSize(mode: Mode): number {
  return { month: 1, quarter: 3, ytd: 12, year: 12, custom: 0 }[mode]
}

/** The next arrow can't move past the current period. */
export function nextDisabled(p: Period, today: Today): boolean {
  const [y, m] = parseYm(p.anchor)
  switch (p.mode) {
    case 'month':
      return p.anchor >= today.ym
    case 'ytd':
    case 'year':
      return y >= +today.ym.slice(0, 4)
    case 'quarter':
      return ym(y, Math.floor((m - 1) / 3) * 3 + 3) >= today.ym
    case 'custom':
      return true
  }
}

/** "Oct 1 – Oct 21 (today)" style span under the title. */
export function spanLabel(ms: string[], today: Today): string {
  const first = ms[0]
  const last = ms[ms.length - 1]
  if (first > today.ym) return 'Hasn’t started yet'
  const effEnd = last > today.ym ? today.ym : last
  const endDay = effEnd === today.ym ? today.day : daysIn(effEnd)
  const [, fm] = parseYm(first)
  const [, lm] = parseYm(effEnd)
  return (
    MONTHS[fm - 1].slice(0, 3) + ' 1 – ' + MONTHS[lm - 1].slice(0, 3) + ' ' + endDay + (effEnd === today.ym ? ' (today)' : '')
  )
}

export function selectMode(p: Period, mode: Mode, today: Today): Period {
  return { ...p, mode, anchor: mode !== 'custom' && p.anchor > today.ym ? today.ym : p.anchor }
}

export function stepPeriod(p: Period, dir: -1 | 1, today: Today): Period {
  let n = addM(p.anchor, dir * stepSize(p.mode))
  if (dir === 1 && n > today.ym) n = today.ym
  return { ...p, anchor: n }
}

/** Months offered in the custom range selects: two years back to now, newest first. */
export function customOptions(today: Today): string[] {
  return rangeYm(addM(today.ym, -23), today.ym).reverse()
}
