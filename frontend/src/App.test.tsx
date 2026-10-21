import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'

function mockFetch(res: { ok: boolean; status: number; body: unknown }) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: res.ok,
      status: res.status,
      statusText: res.ok ? 'OK' : 'Service Unavailable',
      text: async () => JSON.stringify(res.body),
    })),
  )
}

describe('App shell', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows the eyebrow and server status when healthy', async () => {
    mockFetch({
      ok: true,
      status: 200,
      body: {
        status: 'ok',
        db: 'ok',
        plaidEnv: 'sandbox',
        timeZone: 'America/New_York',
        config: { plaid: false, tokenEncKey: true, anthropic: false, auth: false },
      },
    })
    render(<App />)
    expect(screen.getByText('Personal Finance')).toBeInTheDocument()
    expect(await screen.findByText('Server and database are up')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Not configured yet: Plaid keys, Anthropic API key.')
  })

  it('shows an error state when the server is down', async () => {
    mockFetch({ ok: false, status: 503, body: { detail: 'db down' } })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('db down')
  })
})
