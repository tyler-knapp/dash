import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { AccountBalance, NetWorth } from '../api/networth'
import { NetWorthView } from './NetWorthView'

const a = (over: Partial<AccountBalance>): AccountBalance => ({
  id: over.name ?? 'x', name: 'x', institutionName: null, kind: 'Checking', mask: null, displayColor: null,
  isManual: false, isSample: true, linked: true, current: 0, asOf: '2026-10-20', source: 'plaid', ...over,
})

// The sample data as the backend returns it (figures match the prototype's Net worth tab).
const NW: NetWorth = {
  total: 176961.01, cashMinusCards: 44434.01, asOf: '2026-10-20',
  groups: [
    { name: 'Cash & savings', total: 45612.13, accounts: [a({ name: 'Huntington Checking', current: 4218.4 }), a({ name: 'Vanguard Money Market', kind: 'Money market', current: 11204.31 })] },
    { name: 'Credit cards', total: -1178.12, accounts: [a({ name: 'American Express', kind: 'Credit card', current: -187.43 })] },
    { name: 'Retirement', total: 132527, accounts: [
      a({ name: 'Vanguard Roth IRA', kind: 'Retirement', current: 48210 }),
      a({ name: 'Fidelity 401(k) · former employer', kind: 'Retirement', current: 22940, isManual: true, linked: false, asOf: '2026-10-01', source: 'manual' }),
    ] },
  ],
  withoutBalance: [],
}

describe('NetWorthView', () => {
  it('renders the prototype’s totals, groups and meta lines', () => {
    render(<NetWorthView load={{ kind: 'ok', nw: NW }} onRetry={vi.fn()} />)
    expect(screen.getByText('$176,961')).toBeInTheDocument()
    expect(screen.getByText('$44,434')).toBeInTheDocument()
    const cards = screen.getByText('Credit cards').closest('.group') as HTMLElement
    expect(within(cards).getByText('−$1,178.12')).toBeInTheDocument()
    expect(within(cards).getByText('−$187.43')).toHaveClass('neg')
    expect(screen.getByText('Money market')).toBeInTheDocument()
    expect(screen.getByText('Retirement · linked')).toBeInTheDocument()
    expect(screen.getByText('Manual · updated Oct 1')).toBeInTheDocument()
    expect(screen.getByText(/^Balances as of Oct 20, 2026\. Net worth is always current and doesn't follow the period picker\./)).toBeInTheDocument()
  })

  it('lists accounts with no balance yet, outside the totals', () => {
    render(<NetWorthView load={{ kind: 'ok', nw: { ...NW, withoutBalance: [a({ name: 'HSA', isManual: true, current: null, asOf: null })] } }} onRetry={vi.fn()} />)
    const g = screen.getByText('No balance yet').closest('.group') as HTMLElement
    expect(within(g).getByText('HSA')).toBeInTheDocument()
    expect(within(g).getByText('Manual · add a balance on the Accounts tab')).toBeInTheDocument()
  })

  it('loading, empty and error states', () => {
    const onRetry = vi.fn()
    const { rerender } = render(<NetWorthView load={{ kind: 'loading' }} onRetry={onRetry} />)
    expect(screen.getByText('Loading balances…')).toBeInTheDocument()
    rerender(<NetWorthView load={{ kind: 'ok', nw: { ...NW, groups: [], withoutBalance: [] } }} onRetry={onRetry} />)
    expect(screen.getByText('No balances yet. They appear once your accounts are linked.')).toBeInTheDocument()
    rerender(<NetWorthView load={{ kind: 'error' }} onRetry={onRetry} />)
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onRetry).toHaveBeenCalled()
  })
})
