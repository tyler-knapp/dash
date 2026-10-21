import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Account, Category, Txn } from '../api/ledger'
import { mockApi } from '../test/fetchMock'
import { LedgerView } from './LedgerView'

const categories: Category[] = [
  { name: 'Food', kind: 'spend', subcategories: ['Groceries', 'Restaurants', 'Coffee'] },
  { name: 'Side Hustles', kind: 'spend', subcategories: ['Reselling Inventory', 'YouTube Gear'] },
  { name: 'Income', kind: 'income', subcategories: ['Salary'] },
  { name: 'Transfer', kind: 'transfer', subcategories: ['Card Payment'] },
]
const accounts = [
  { id: 'acc-hv', name: 'Huntington Voice Rewards', role: 'ledger', hidden: false, displayColor: '#2f7d4f' },
  { id: 'acc-vg', name: 'Vanguard Roth IRA', role: 'balance_only', hidden: false, displayColor: null },
] as Account[]

const txn = (over: Partial<Txn>): Txn => ({
  id: 't1', date: '2026-10-02', merchant: 'Kroger #512 Polaris', rawName: null, amount: -118.42, accountId: 'acc-hv',
  accountName: 'Huntington Voice Rewards', accountColor: '#2f7d4f', category: 'Food', subcategory: 'Groceries',
  categorySource: 'ai', confidence: 0.95, reviewed: false, pending: false, needsReview: false, ...over,
})

const ROWS = [
  txn({ id: 't1' }),
  txn({ id: 't2', merchant: 'Amazon', amount: -64.2, confidence: 0.55, needsReview: true, category: 'Side Hustles', subcategory: 'Reselling Inventory' }),
  txn({ id: 't3', date: '2026-10-01', merchant: 'Payroll deposit', amount: 3412.55, category: 'Income', subcategory: 'Salary', reviewed: true, categorySource: 'user' }),
  txn({ id: 't4', date: '2026-10-01', merchant: 'New charge', amount: -9.99, category: null, subcategory: null, confidence: null, needsReview: true, pending: true }),
]

function renderLedger(extra: Partial<Parameters<typeof LedgerView>[0]> = {}) {
  const onToast = vi.fn()
  render(
    <LedgerView from="2026-10" to="2026-10" title="October 2026" periodCount={4} accounts={accounts} categories={categories} onToast={onToast} {...extra} />,
  )
  return { onToast }
}

describe('LedgerView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('groups rows by day with prototype tags, signed amounts and account dots', async () => {
    mockApi({ 'GET /api/transactions': () => ({ body: { transactions: ROWS, total: 4, nextCursor: null } }) })
    renderLedger()
    expect(await screen.findByText('Kroger #512 Polaris')).toBeInTheDocument()
    expect(screen.getByText('Fri, Oct 2')).toBeInTheDocument()
    expect(screen.getByText('Thu, Oct 1')).toBeInTheDocument()
    expect(screen.getByText('−$118.42')).toBeInTheDocument()
    expect(screen.getByText('+$3,412.55')).toHaveClass('amt', 'in')
    expect(screen.getByText('Auto')).toBeInTheDocument()
    expect(screen.getByText('Review · auto 55%')).toBeInTheDocument()
    expect(screen.getByText('Reviewed')).toBeInTheDocument()
    expect(screen.getByText('Review · uncategorized')).toBeInTheDocument()
    expect(screen.getByText('Pending')).toBeInTheDocument()
    expect(screen.getByLabelText('Category for Kroger #512 Polaris')).toHaveValue('Food|Groceries')
    // Only ledger accounts in the account filter.
    expect(within(screen.getByLabelText('Filter by account')).queryByText('Vanguard Roth IRA')).toBeNull()
  })

  it('sends filters to the API and starts over from page one', async () => {
    const calls = mockApi({ 'GET /api/transactions': () => ({ body: { transactions: ROWS.slice(0, 1), total: 1, nextCursor: null } }) })
    renderLedger()
    await screen.findByText('Kroger #512 Polaris')

    fireEvent.change(screen.getByLabelText('Filter by account'), { target: { value: 'acc-hv' } })
    fireEvent.change(screen.getByLabelText('Filter by category'), { target: { value: 'Food' } })
    fireEvent.click(screen.getByLabelText('Show transfers'))
    fireEvent.click(screen.getByLabelText('Needs review only'))
    fireEvent.change(screen.getByLabelText('Search merchants'), { target: { value: 'krog' } })

    await waitFor(() => {
      const u = calls[calls.length - 1].url
      expect(u.searchParams.get('q')).toBe('krog')
    })
    const u = calls[calls.length - 1].url
    expect(Object.fromEntries(u.searchParams)).toEqual({
      from: '2026-10', to: '2026-10', account: 'acc-hv', category: 'Food', q: 'krog', needsReview: 'true', includeTransfers: 'true', limit: '150',
    })
  })

  it('recategorizes optimistically and keeps the saved result', async () => {
    const calls = mockApi({
      'GET /api/transactions': () => ({ body: { transactions: ROWS, total: 4, nextCursor: null } }),
      'PATCH /api/transactions/t2': (b) => ({
        body: { ...ROWS[1], ...(b as object), categorySource: 'user', reviewed: true, needsReview: false },
      }),
    })
    const onChanged = vi.fn()
    renderLedger({ onChanged })
    const select = await screen.findByLabelText('Category for Amazon')

    fireEvent.change(select, { target: { value: 'Food|Groceries' } })

    expect(select).toHaveValue('Food|Groceries')
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    expect(calls.find((c) => c.key === 'PATCH /api/transactions/t2')?.body).toEqual({ category: 'Food', subcategory: 'Groceries' })
    expect(screen.queryByText('Review · auto 55%')).toBeNull()
    expect(screen.getAllByText('Reviewed')).toHaveLength(2)
  })

  it('in "Needs review only", a fixed row drops out of the list', async () => {
    mockApi({
      'GET /api/transactions': () => ({ body: { transactions: [ROWS[1]], total: 1, nextCursor: null } }),
      'PATCH /api/transactions/t2': (b) => ({ body: { ...ROWS[1], ...(b as object), reviewed: true, needsReview: false } }),
    })
    renderLedger()
    await screen.findByText('Amazon')
    fireEvent.click(screen.getByLabelText('Needs review only'))
    const select = await screen.findByLabelText('Category for Amazon')
    fireEvent.change(select, { target: { value: 'Food|Groceries' } })
    expect(await screen.findByText('No transactions match these filters.')).toBeInTheDocument()
  })

  it('reverts and shows the prototype message when a change fails to save', async () => {
    mockApi({
      'GET /api/transactions': () => ({ body: { transactions: ROWS, total: 4, nextCursor: null } }),
      'PATCH /api/transactions/t1': () => ({ status: 500, body: {} }),
    })
    const { onToast } = renderLedger()
    const select = await screen.findByLabelText('Category for Kroger #512 Polaris')
    fireEvent.change(select, { target: { value: 'Food|Coffee' } })
    await waitFor(() => expect(onToast).toHaveBeenCalledWith('That change didn’t save. Check your connection and try again.'))
    expect(screen.getByLabelText('Category for Kroger #512 Polaris')).toHaveValue('Food|Groceries')
  })

  it('"Show more" fetches the next page with the cursor', async () => {
    const page1 = Array.from({ length: 150 }, (_, i) => txn({ id: 'a' + i, merchant: 'Shop ' + i }))
    const page2 = [txn({ id: 'b1', merchant: 'Last shop', date: '2026-09-30' })]
    const calls = mockApi({
      'GET /api/transactions': (_b, url) =>
        url.searchParams.get('cursor') === 'CUR1'
          ? { body: { transactions: page2, total: 151, nextCursor: null } }
          : { body: { transactions: page1, total: 151, nextCursor: 'CUR1' } },
    })
    renderLedger({ periodCount: 151 })
    fireEvent.click(await screen.findByRole('button', { name: 'Show 1 more of 1 remaining' }))
    expect(await screen.findByText('Last shop')).toBeInTheDocument()
    expect(calls[calls.length - 1].url.searchParams.get('cursor')).toBe('CUR1')
    expect(screen.queryByRole('button', { name: /Show .* more/ })).toBeNull()
  })

  it('empty and error states use the prototype wording', async () => {
    mockApi({ 'GET /api/transactions': () => ({ body: { transactions: [], total: 0, nextCursor: null } }) })
    renderLedger({ periodCount: 0 })
    expect(await screen.findByText('No transactions in October 2026 yet. New ones appear here as your accounts sync.')).toBeInTheDocument()
    vi.unstubAllGlobals()

    mockApi({ 'GET /api/transactions': () => ({ status: 500, body: {} }) })
    await act(async () => {
      fireEvent.click(screen.getByLabelText('Show transfers'))
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('Your transactions couldn’t load')
  })
})
