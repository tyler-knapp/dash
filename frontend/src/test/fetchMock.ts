import { vi } from 'vitest'

export type Route = (body: unknown, url: URL) => { status?: number; body: unknown } | Promise<{ status?: number; body: unknown }>

/** Stub global fetch with handlers keyed by "METHOD /path" (query string ignored for matching). Records every call. */
export function mockApi(routes: Record<string, Route>) {
  const calls: { key: string; body: unknown; url: URL }[] = []
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    const url = new URL(path, 'http://localhost')
    const key = `${init?.method ?? 'GET'} ${url.pathname}`
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ key, body, url })
    const route = routes[key] ?? Object.entries(routes).find(([k]) => k.endsWith('*') && key.startsWith(k.slice(0, -1)))?.[1]
    const res = route ? await route(body, url) : { status: 404, body: { detail: `no route for ${key}` } }
    const status = res.status ?? 200
    return {
      ok: status < 400,
      status,
      statusText: String(status),
      text: async () => JSON.stringify(res.body),
    }
  })
  vi.stubGlobal('fetch', fetchMock)
  return calls
}
