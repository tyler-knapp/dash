import { vi } from 'vitest'

export type Route = (body: unknown) => { status?: number; body: unknown }

/** Stub global fetch with handlers keyed by "METHOD /path". Records every call. */
export function mockApi(routes: Record<string, Route>) {
  const calls: { key: string; body: unknown }[] = []
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ key, body })
    const route = routes[key]
    const res = route ? route(body) : { status: 404, body: { detail: `no route for ${key}` } }
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
