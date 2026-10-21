import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ItemView } from '../api/items'
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

const item = (over: Partial<ItemView> = {}): ItemView => ({
  id: 'item-1',
  institutionName: 'First Platypus Bank',
  status: 'ok',
  lastSyncedAt: new Date().toISOString(),
  products: ['transactions'],
  transactionsUpdateStatus: 'HISTORICAL_UPDATE_COMPLETE',
  lastErrorCode: null,
  accounts: [
    {
      id: 'a1', name: 'Plaid Checking', officialName: null, mask: '0000', type: 'depository', subtype: 'checking',
      role: 'ledger', displayColor: '#1f6b5c', hidden: false, isManual: false, currentBalance: 4218.4, balanceAsOf: '2026-10-21',
    },
    {
      id: 'a2', name: 'Plaid Credit Card', officialName: null, mask: '3333', type: 'credit', subtype: 'credit card',
      role: 'ledger', displayColor: '#2b6cb0', hidden: false, isManual: false, currentBalance: -412.66, balanceAsOf: '2026-10-21',
    },
  ],
  ...over,
})

describe('AccountsView', () => {
  beforeEach(() => {
    plaid.open.mockReset()
    plaid.onSuccess = undefined
  })
  afterEach(() => vi.unstubAllGlobals())

  it('shows the empty state when nothing is linked', async () => {
    mockApi({ 'GET /api/items': () => ({ body: [] }) })
    render(<AccountsView />)
    expect(await screen.findByText(/No accounts linked yet/)).toBeInTheDocument()
  })

  it('lists institutions with status, accounts and signed balances', async () => {
    mockApi({ 'GET /api/items': () => ({ body: [item()] }) })
    render(<AccountsView />)
    expect(await screen.findByText('First Platypus Bank')).toBeInTheDocument()
    expect(screen.getByText('Connected')).toBeInTheDocument()
    expect(screen.getByText('$4,218.40')).toBeInTheDocument()
    expect(screen.getByText('−$412.66')).toBeInTheDocument()
    expect(screen.getByText(/Last synced just now/)).toBeInTheDocument()
  })

  it('links a bank: link token, open Link, exchange the public token, refresh', async () => {
    let items: ItemView[] = []
    const calls = mockApi({
      'GET /api/items': () => ({ body: items }),
      'POST /api/plaid/link-token': () => ({ body: { linkToken: 'link-sandbox-1' } }),
      'POST /api/plaid/exchange': () => {
        items = [item()]
        return { body: { item: items[0], firstSync: { added: 12 } } }
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

  it('offers Reconnect for a broken login and finishes with a sync (update mode)', async () => {
    let status: ItemView['status'] = 'login_required'
    const calls = mockApi({
      'GET /api/items': () => ({ body: [item({ status, lastErrorCode: status === 'ok' ? null : 'ITEM_LOGIN_REQUIRED' })] }),
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
    expect(calls.some((c) => c.key === 'POST /api/plaid/exchange')).toBe(false) // update mode never exchanges
    expect(await screen.findByText('Connected')).toBeInTheDocument()
  })

  it('Sync now reports institutions that failed', async () => {
    mockApi({
      'GET /api/items': () => ({ body: [item()] }),
      'POST /api/sync': () => ({ body: { results: [{ outcome: 'error', added: 0 }, { outcome: 'ok', added: 3 }] } }),
    })
    render(<AccountsView />)
    await screen.findByText('First Platypus Bank')
    fireEvent.click(screen.getByRole('button', { name: 'Sync now' }))
    expect(await screen.findByText('1 institution couldn’t sync. See below.')).toBeInTheDocument()
  })

  it('shows the server’s message when Plaid isn’t configured', async () => {
    mockApi({
      'GET /api/items': () => ({ body: [] }),
      'POST /api/plaid/link-token': () => ({ status: 503, body: { detail: 'Plaid keys are not set (PLAID_CLIENT_ID, PLAID_SECRET)' } }),
    })
    render(<AccountsView />)
    await screen.findByText(/No accounts linked yet/)
    fireEvent.click(screen.getByRole('button', { name: 'Link bank or card' }))
    expect(await screen.findByText('Plaid keys are not set (PLAID_CLIENT_ID, PLAID_SECRET)')).toBeInTheDocument()
    expect(plaid.open).not.toHaveBeenCalled()
  })
})
