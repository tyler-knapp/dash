import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockApi } from './test/fetchMock'
import App from './App'

vi.mock('react-plaid-link', () => ({ usePlaidLink: () => ({ open: vi.fn(), ready: false }) }))

const healthy = { status: 'ok', db: 'ok', plaidEnv: 'sandbox', timeZone: 'America/New_York', config: {} }

function api(over: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  return mockApi({
    'GET /api/health': () => ({ body: healthy }),
    'GET /api/categories': () => ({ body: [] }),
    'GET /api/accounts': () => ({ body: [{ id: 'a', name: 'Amex', role: 'ledger', isSample: true }] }),
    'GET /api/transactions': () => ({ body: { transactions: [], total: 42, nextCursor: null } }),
    'GET /api/items': () => ({ body: [] }),
    ...over,
  })
}

describe('App shell', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-21T16:00:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows the period title, span with count, and the Sample badge', async () => {
    api()
    render(<App />)
    expect(screen.getByText('Personal Finance')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'October 2026' })).toBeInTheDocument()
    expect(await screen.findByText('Oct 1 – Oct 21 (today) · 42 transactions')).toBeInTheDocument()
    expect(await screen.findByText('Sample data')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('steps periods and remembers tab and period across reloads', async () => {
    api()
    const { unmount } = render(<App />)
    expect(screen.getByRole('button', { name: 'Next period' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Previous period' }))
    expect(screen.getByRole('heading', { name: 'September 2026' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Quarter' }))
    expect(screen.getByRole('heading', { name: 'Q3 2026' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: 'Accounts' }))
    unmount()

    render(<App />)
    expect(screen.getByRole('heading', { name: 'Q3 2026' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Accounts' })).toHaveAttribute('aria-selected', 'true')
    await screen.findByText(/No accounts linked yet/)
  })

  it('custom range shows from/to selects', () => {
    api()
    render(<App />)
    fireEvent.click(screen.getByRole('button', { name: 'Custom' }))
    expect(screen.getByLabelText('From month')).toHaveValue('2026-05')
    expect(screen.getByLabelText('To month')).toHaveValue('2026-10')
    expect(screen.getByRole('heading', { name: 'May 2026 – Oct 2026' })).toBeInTheDocument()
  })

  it('warns when the server is unreachable', async () => {
    api({ 'GET /api/health': () => ({ status: 503, body: { detail: 'db down' } }) })
    render(<App />)
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('db down'))
  })
})
