import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ItemView } from '../api/items'
import type { Account } from '../api/ledger'
import { mockApi } from '../test/fetchMock'
import { AccountsView } from './AccountsView'

// Plaid Link is a third-party iframe; stand in for it and capture the callbacks.
const plaid = vi.hoisted(() => ({
  open: vi.fn(),
  onSuccess: undefined as undefined | ((t: string, m: unknown) => void),
}))
vi.mock('react-plaid-link', () => ({
  usePlaidLink: (cfg: { token: string | null; onSuccess: (t: string, m: unknown) => void }) => {
    if (cfg.token) plaid.onSuccess = cfg.onSuccess
    return { open: plaid.open, ready: !!cfg.token }
  },
}))

const acct = (over: Partial<Account>): Account => ({
  id: 'a1', itemId: 'item-1', name: 'Plaid Checking', institutionName: 'First Platypus Bank', mask: '0000', type: 'depository',
  subtype: 'checking', role: 'ledger', displayColor: '#1f6b5c', hidden: false, isManual: false, isSample: false,
  currentBalance: 4218.4, balanceAsOf: '2026-10-21', ...over,
})

const item = (over: Partial<ItemView> = {}): ItemView => ({
  id: 'item-1', institutionName: 'First Platypus Bank', status: 'ok', lastSyncedAt: new Date().toISOString(),
  products: ['transactions'], transactionsUpdateStatus: 'HISTORICAL_UPDATE_COMPLETE', lastErrorCode: null, accounts: [], ...over,
})

const LINKED = [acct({}), acct({ id: 'a2', name: 'Plaid Credit Card', mask: '3333', type: 'credit', subtype: 'credit card', currentBalance: -412.66, displayColor: '#2b6cb0' })]

describe('AccountsView', () => {
  beforeEach(() => {
    plaid.open.mockReset()
    plaid.onSuccess = undefined
  })
  afterEach(() => vi.unstubAllGlobals())

  it('shows the empty state when nothing exists', async () => {
    mockApi({ 'GET /api/items': () => ({ body: [] }), 'GET /api/accounts': () => ({ body: [] }) })
    render(<AccountsView />)
    expect(await screen.findByText(/No accounts linked yet/)).toBeInTheDocument()
  })

  it('lists institutions with status, their accounts and signed balances', async () => {
    mockApi({ 'GET /api/items': () => ({ body: [item()] }), 'GET /api/accounts': () => ({ body: LINKED }) })
    render(<AccountsView />)
    const group = (await screen.findByText('First Platypus Bank')).closest('.group') as HTMLElement
    expect(within(group).getByText('Connected')).toBeInTheDocument()
    expect(within(group).getByText('$4,218.40')).toBeInTheDocument()
    expect(within(group).getByText('−$412.66')).toHaveClass('neg')
    expect(within(group).getByText(/Last synced just now/)).toBeInTheDocument()
    expect(within(group).queryByRole('button', { name: 'Update balance' })).toBeNull() // linked: balances come from the bank
  })

  it('links a bank: link token, open Link, exchange the public token, refresh', async () => {
    let items: ItemView[] = []
    let accounts: Account[] = []
    const calls = mockApi({
      'GET /api/items': () => ({ body: items }),
      'GET /api/accounts': () => ({ body: accounts }),
      'POST /api/plaid/link-token': () => ({ body: { linkToken: 'link-sandbox-1' } }),
      'POST /api/plaid/exchange': () => {
        items = [item()]
        accounts = LINKED
        return { body: { item: { ...items[0], accounts: LINKED }, firstSync: { added: 12 } } }
      },
    })
    render(<AccountsView />)
    await screen.findByText(/No accounts linked yet/)
    fireEvent.click(screen.getByRole('button', { name: 'Link bank or card' }))
    await waitFor(() => expect(plaid.open).toHaveBeenCalled())
    expect(calls.find((c) => c.key === 'POST /api/plaid/link-token')?.body).toEqual({ kind: 'bank' })
    plaid.onSuccess!('public-sandbox-abc', {})
    expect(await screen.findByText(/Linked First Platypus Bank: 2 accounts, 12 transactions so far/)).toBeInTheDocument()
    expect(calls.find((c) => c.key === 'POST /api/plaid/exchange')?.body).toEqual({ publicToken: 'public-sandbox-abc' })
    expect(await screen.findByText('Plaid Checking')).toBeInTheDocument()
  })

  it('offers Reconnect for a broken login and finishes with a sync (update mode, no exchange)', async () => {
    let status: ItemView['status'] = 'login_required'
    const calls = mockApi({
      'GET /api/items': () => ({ body: [item({ status })] }),
      'GET /api/accounts': () => ({ body: LINKED }),
      'POST /api/plaid/link-token': () => ({ body: { linkToken: 'link-sandbox-update' } }),
      'POST /api/sync': () => {
        status = 'ok'
        return { body: { results: [{ outcome: 'ok', added: 0 }] } }
      },
    })
    render(<AccountsView />)
    expect(await screen.findByText('Needs reconnect')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reconnect' }))
    await waitFor(() => expect(plaid.open).toHaveBeenCalled())
    expect(calls.find((c) => c.key === 'POST /api/plaid/link-token')?.body).toEqual({ itemId: 'item-1' })
    plaid.onSuccess!('public-ignored', {})
    expect(await screen.findByText('Reconnected. Synced. 0 new transactions.')).toBeInTheDocument()
    expect(calls.some((c) => c.key === 'POST /api/plaid/exchange')).toBe(false)
    expect(await screen.findByText('Connected')).toBeInTheDocument()
  })

  it('adds a manual retirement account with a starting balance', async () => {
    let accounts: Account[] = []
    const calls = mockApi({
      'GET /api/items': () => ({ body: [] }),
      'GET /api/accounts': () => ({ body: accounts }),
      'POST /api/accounts/manual': (b) => {
        const body = b as { name: string; type: string }
        accounts = [acct({ id: 'm1', itemId: null, name: body.name, type: body.type, subtype: null, role: 'balance_only', isManual: true, currentBalance: null, balanceAsOf: null })]
        return { status: 201, body: accounts[0] }
      },
      'POST /api/accounts/m1/balance': (b) => {
        const body = b as { asOf: string; current: number }
        accounts = [{ ...accounts[0], currentBalance: body.current, balanceAsOf: body.asOf }]
        return { body: accounts[0] }
      },
    })
    const onChanged = vi.fn()
    render(<AccountsView onChanged={onChanged} />)
    await screen.findByText(/No accounts linked yet/)
    fireEvent.click(screen.getByRole('button', { name: 'Add manual account' }))
    const form = screen.getByRole('form', { name: 'Add manual account' })
    fireEvent.change(within(form).getByLabelText('Name'), { target: { value: 'Fidelity 401(k) · former employer' } })
    fireEvent.change(within(form).getByLabelText('Balance (optional)'), { target: { value: '22,940' } })
    fireEvent.change(within(form).getByLabelText('As of'), { target: { value: '2026-10-01' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Add account' }))

    expect(await screen.findByText('Added Fidelity 401(k) · former employer.')).toBeInTheDocument()
    expect(calls.find((c) => c.key === 'POST /api/accounts/manual')?.body).toEqual({ name: 'Fidelity 401(k) · former employer', type: 'investment' })
    expect(calls.find((c) => c.key === 'POST /api/accounts/m1/balance')?.body).toEqual({ asOf: '2026-10-01', current: 22940 })
    const group = (await screen.findByText('Manual accounts')).closest('.group') as HTMLElement
    expect(await within(group).findByText(/Manual · updated Oct 1/)).toBeInTheDocument()
    expect(within(group).getByText('$22,940.00')).toBeInTheDocument()
    expect(onChanged).toHaveBeenCalled()
  })

  it('a manual loan takes "amount owed" and stores it as negative', async () => {
    const loan = acct({ id: 'L1', itemId: null, name: 'Car loan', type: 'loan', subtype: null, role: 'balance_only', isManual: true, currentBalance: -9800, balanceAsOf: '2026-09-15' })
    const calls = mockApi({
      'GET /api/items': () => ({ body: [] }),
      'GET /api/accounts': () => ({ body: [loan] }),
      'POST /api/accounts/L1/balance': (b) => ({ body: { ...loan, ...(b as object) } }),
    })
    render(<AccountsView />)
    fireEvent.click(await screen.findByRole('button', { name: 'Update balance' }))
    const form = screen.getByRole('form', { name: 'Update balance for Car loan' })
    expect(within(form).getByLabelText('Amount owed')).toHaveValue('9800') // shown positive
    fireEvent.change(within(form).getByLabelText('Amount owed'), { target: { value: '9500' } })
    fireEvent.change(within(form).getByLabelText('As of'), { target: { value: '2026-10-20' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Save balance' }))
    await waitFor(() => expect(calls.find((c) => c.key === 'POST /api/accounts/L1/balance')?.body).toEqual({ asOf: '2026-10-20', current: -9500 }))
  })

  it('renames, recolors and hides an account', async () => {
    const calls = mockApi({
      'GET /api/items': () => ({ body: [item()] }),
      'GET /api/accounts': () => ({ body: LINKED }),
      'PATCH /api/accounts/a2': (b) => ({ body: { ...LINKED[1], ...(b as object) } }),
    })
    render(<AccountsView />)
    fireEvent.click(await screen.findByRole('button', { name: 'Edit Plaid Credit Card' }))
    const form = screen.getByRole('form', { name: 'Edit Plaid Credit Card' })
    fireEvent.change(within(form).getByLabelText('Name'), { target: { value: 'Voice card' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Color #b4413a' }))
    fireEvent.click(within(form).getByLabelText(/Hide/))
    fireEvent.click(within(form).getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Saved Voice card.')).toBeInTheDocument()
    expect(calls.find((c) => c.key === 'PATCH /api/accounts/a2')?.body).toEqual({ name: 'Voice card', displayColor: '#b4413a', hidden: true })
  })

  it('shows sample accounts in their own group with the removal note', async () => {
    mockApi({
      'GET /api/items': () => ({ body: [] }),
      'GET /api/accounts': () => ({ body: [acct({ id: 's1', itemId: null, name: 'American Express', isSample: true })] }),
    })
    render(<AccountsView />)
    const group = (await screen.findByText('Sample data')).closest('.group') as HTMLElement
    expect(within(group).getByText('American Express')).toBeInTheDocument()
    expect(within(group).getByText(/removed automatically when you link your first real account/)).toBeInTheDocument()
  })

  it('Sync now reports institutions that failed; Link shows a not-configured message', async () => {
    mockApi({
      'GET /api/items': () => ({ body: [item()] }),
      'GET /api/accounts': () => ({ body: LINKED }),
      'POST /api/sync': () => ({ body: { results: [{ outcome: 'error', added: 0 }, { outcome: 'ok', added: 3 }] } }),
      'POST /api/plaid/link-token': () => ({ status: 503, body: { detail: 'Plaid keys are not set (PLAID_CLIENT_ID, PLAID_SECRET)' } }),
    })
    render(<AccountsView />)
    await screen.findByText('First Platypus Bank')
    fireEvent.click(screen.getByRole('button', { name: 'Sync now' }))
    expect(await screen.findByText('1 institution couldn’t sync. See below.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Link bank or card' }))
    expect(await screen.findByText('Plaid keys are not set (PLAID_CLIENT_ID, PLAID_SECRET)')).toBeInTheDocument()
    expect(plaid.open).not.toHaveBeenCalled()
  })
})
