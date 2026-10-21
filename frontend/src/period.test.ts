import { describe, expect, it } from 'vitest'
import {
  customOptions, defaultPeriod, elapsed, nextDisabled, periodMonths, periodTitle, selectMode, spanLabel, stepPeriod,
  todayInNewYork, type Period, type Today,
} from './period'

const today: Today = { ym: '2026-10', day: 21 }
const p = (over: Partial<Period>): Period => ({ ...defaultPeriod(today), ...over })

describe('period logic (ported from the prototype)', () => {
  it('month, quarter, year', () => {
    expect(periodMonths(p({ mode: 'month', anchor: '2026-07' }), today)).toEqual(['2026-07'])
    expect(periodMonths(p({ mode: 'quarter', anchor: '2026-08' }), today)).toEqual(['2026-07', '2026-08', '2026-09'])
    expect(periodMonths(p({ mode: 'year', anchor: '2025-03' }), today)).toHaveLength(12)
  })

  it('YTD runs Jan to the current month this year, the full year for past years', () => {
    expect(periodMonths(p({ mode: 'ytd', anchor: '2026-04' }), today)).toEqual(
      ['2026-01', '2026-02', '2026-03', '2026-04', '2026-05', '2026-06', '2026-07', '2026-08', '2026-09', '2026-10'])
    expect(periodMonths(p({ mode: 'ytd', anchor: '2025-04' }), today)).toHaveLength(12)
  })

  it('custom accepts either order', () => {
    expect(periodMonths(p({ mode: 'custom', from: '2026-09', to: '2026-07' }), today)).toEqual(['2026-07', '2026-08', '2026-09'])
  })

  it('titles', () => {
    expect(periodTitle(p({ mode: 'month', anchor: '2026-10' }), ['2026-10'])).toBe('October 2026')
    expect(periodTitle(p({ mode: 'quarter', anchor: '2026-11' }), [])).toBe('Q4 2026')
    expect(periodTitle(p({ mode: 'ytd', anchor: '2026-10' }), [])).toBe('2026 year to date')
    expect(periodTitle(p({ mode: 'year', anchor: '2025-10' }), [])).toBe('2025')
    expect(periodTitle(p({ mode: 'custom' }), ['2026-05', '2026-06', '2026-07'])).toBe('May 2026 – Jul 2026')
    expect(periodTitle(p({ mode: 'custom' }), ['2026-05'])).toBe('May 2026')
  })

  it('span under the title', () => {
    expect(spanLabel(['2026-10'], today)).toBe('Oct 1 – Oct 21 (today)')
    expect(spanLabel(['2026-07', '2026-08', '2026-09'], today)).toBe('Jul 1 – Sep 30')
    expect(spanLabel(['2026-10', '2026-11', '2026-12'], today)).toBe('Oct 1 – Oct 21 (today)')
    expect(spanLabel(['2026-11'], today)).toBe('Hasn’t started yet')
  })

  it('arrows step by 1, 3 or 12 months and never pass the current period', () => {
    expect(stepPeriod(p({ mode: 'month', anchor: '2026-10' }), -1, today).anchor).toBe('2026-09')
    expect(stepPeriod(p({ mode: 'quarter', anchor: '2026-10' }), -1, today).anchor).toBe('2026-07')
    expect(stepPeriod(p({ mode: 'year', anchor: '2026-10' }), -1, today).anchor).toBe('2025-10')
    expect(stepPeriod(p({ mode: 'month', anchor: '2026-09' }), 1, today).anchor).toBe('2026-10')
    expect(stepPeriod(p({ mode: 'year', anchor: '2025-12' }), 1, today).anchor).toBe('2026-10')
    expect(nextDisabled(p({ mode: 'month', anchor: '2026-10' }), today)).toBe(true)
    expect(nextDisabled(p({ mode: 'month', anchor: '2026-09' }), today)).toBe(false)
    expect(nextDisabled(p({ mode: 'quarter', anchor: '2026-10' }), today)).toBe(true) // Q4 ends in Dec
    expect(nextDisabled(p({ mode: 'quarter', anchor: '2026-08' }), today)).toBe(false)
    expect(nextDisabled(p({ mode: 'ytd', anchor: '2026-01' }), today)).toBe(true)
    expect(nextDisabled(p({ mode: 'year', anchor: '2025-06' }), today)).toBe(false)
  })

  it('switching mode pulls a future anchor back to today', () => {
    expect(selectMode(p({ mode: 'month', anchor: '2026-12' }), 'quarter', today).anchor).toBe('2026-10')
    expect(selectMode(p({ mode: 'month', anchor: '2026-12' }), 'custom', today).anchor).toBe('2026-12')
  })

  it('pace: past months count fully, the current month by day, future months not at all', () => {
    expect(elapsed('2026-09', today)).toBe(1)
    expect(elapsed('2026-10', today)).toBeCloseTo(21 / 31)
    expect(elapsed('2026-11', today)).toBe(0)
  })

  it('today is taken in New York, not UTC', () => {
    // 2026-11-01 02:30 UTC is still Oct 31 in New York.
    expect(todayInNewYork(new Date('2026-11-01T02:30:00Z'))).toEqual({ ym: '2026-10', day: 31 })
  })

  it('custom options cover two years, newest first', () => {
    const opts = customOptions(today)
    expect(opts[0]).toBe('2026-10')
    expect(opts).toHaveLength(24)
  })
})
