import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Summary } from '../api/budget'
import { SummaryStrip } from '../components/SummaryStrip'
import { BudgetView } from './BudgetView'

// October 2026 on the sample data, as the backend returns it (figures match the prototype).
const OCT: Summary = {
  from: '2026-10', to: '2026-10', months: ['2026-10'], periodState: 'current', today: '2026-10-21',
  transactionCount: 48, uncategorizedCount: 0, income: 7165.13, spent: 3365.02, budgeted: 4105, net: 3800.11,
  categories: [
    { name: 'Housing', spent: 1806.31, budget: 1900, expectedToDate: 1287.0968, status: 'warn', statusLabel: 'Ahead of pace',
      subs: [{ name: 'Rent', spent: 1640 }, { name: 'Electric', spent: 96.31 }, { name: 'Internet', spent: 70 }, { name: 'Renters Insurance', spent: 0 }] },
    { name: 'Food', spent: 461.06, budget: 750, expectedToDate: 508.0645, status: 'ok', statusLabel: 'On track',
      subs: [{ name: 'Groceries', spent: 372.61 }, { name: 'Restaurants', spent: 74.35 }, { name: 'Coffee', spent: 14.1 }] },
    { name: 'Health & Fitness', spent: 391.39, budget: 275, expectedToDate: 186.2903, status: 'over', statusLabel: 'Over by $116', subs: [] },
  ],
}

const card = (name: string) => screen.getByRole('button', { name: new RegExp(name) }).closest('.cat') as HTMLElement

describe('BudgetView', () => {
  it('shows status pills, spent of budget, bars and the pace marker like the prototype', () => {
    render(<BudgetView load={{ kind: 'ok', summary: OCT }} editable onSetBudget={vi.fn()} onRetry={vi.fn()} />)
    const housing = card('Housing')
    expect(within(housing).getByText('Ahead of pace')).toHaveClass('state', 'warn')
    expect(within(housing).getByText('$1,806.31')).toBeInTheDocument()
    expect(within(housing).getByLabelText('Housing budget for October 2026')).toHaveValue('1900')
    const bar = housing.querySelector('.bar i') as HTMLElement
    expect(bar).toHaveClass('warn')
    expect(parseFloat(bar.style.width)).toBeCloseTo(95.0689, 3) // same as the prototype
    expect((housing.querySelector('.bar b') as HTMLElement).style.left).toMatch(/^calc\(67\.74\d*% - 1px\)$/)
    expect(card('Health').querySelector('.bar i')).toHaveClass('over')
    expect(card('Food').querySelector('.bar i')?.className).toBe('')
    expect(screen.getByText(/Budget changes apply from October 2026 onward/)).toBeInTheDocument()
  })

  it('expands a category to its subcategories', () => {
    render(<BudgetView load={{ kind: 'ok', summary: OCT }} editable onSetBudget={vi.fn()} onRetry={vi.fn()} />)
    const btn = screen.getByRole('button', { name: /Housing/ })
    expect(btn).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(btn)
    expect(btn).toHaveAttribute('aria-expanded', 'true')
    expect(within(card('Housing')).getByText('Renters Insurance')).toBeInTheDocument()
    expect(within(card('Housing')).getByText('$1,640.00')).toBeInTheDocument()
  })

  it('editing a budget commits on blur or Enter, for the period’s month', () => {
    const onSet = vi.fn()
    render(<BudgetView load={{ kind: 'ok', summary: OCT }} editable onSetBudget={onSet} onRetry={vi.fn()} />)
    const input = screen.getByLabelText('Food budget for October 2026')
    fireEvent.change(input, { target: { value: '$825.50' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    fireEvent.blur(input)
    expect(onSet).toHaveBeenCalledTimes(1)
    expect(onSet).toHaveBeenCalledWith('2026-10', 'Food', 825.5)
    // Unchanged value: no request.
    fireEvent.blur(screen.getByLabelText('Housing budget for October 2026'))
    expect(onSet).toHaveBeenCalledTimes(1)
  })

  it('outside Month view, budgets are read-only sums with the prototype note and no pace marker when finished', () => {
    const q3: Summary = { ...OCT, months: ['2026-07', '2026-08', '2026-09'], periodState: 'past',
      categories: [{ ...OCT.categories[0], budget: 5700, spent: 5660.51, status: 'ok', statusLabel: 'Under by $39' }] }
    render(<BudgetView load={{ kind: 'ok', summary: q3 }} editable={false} onSetBudget={vi.fn()} onRetry={vi.fn()} />)
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByText('$5,700')).toBeInTheDocument()
    expect(document.querySelector('.bar b')).toBeNull()
    expect(screen.getByText('Budgets here are the monthly budgets added up across 3 months, using whatever amount was in effect each month. Switch to Month to edit them.')).toBeInTheDocument()
  })

  it('loading and error states', () => {
    const onRetry = vi.fn()
    const { rerender } = render(<BudgetView load={{ kind: 'loading' }} editable onSetBudget={vi.fn()} onRetry={onRetry} />)
    expect(screen.getByText('Loading your budgets…')).toBeInTheDocument()
    rerender(<BudgetView load={{ kind: 'error' }} editable onSetBudget={vi.fn()} onRetry={onRetry} />)
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onRetry).toHaveBeenCalled()
  })
})

describe('SummaryStrip', () => {
  it('formats like the prototype, net colored by sign', () => {
    const { rerender } = render(<SummaryStrip summary={OCT} />)
    expect(screen.getByText('$7,165.13')).toBeInTheDocument()
    expect(screen.getByText('$3,365.02')).toBeInTheDocument()
    expect(screen.getByText('$4,105')).toBeInTheDocument()
    expect(screen.getByText('+$3,800.11')).toHaveStyle({ color: 'var(--good)' })
    rerender(<SummaryStrip summary={{ ...OCT, net: -12.5 }} />)
    expect(screen.getByText('−$12.50')).toHaveStyle({ color: 'var(--bad)' })
    rerender(<SummaryStrip summary={null} />)
    expect(screen.getAllByText('–')).toHaveLength(4)
  })
})
