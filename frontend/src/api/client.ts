/** Thin fetch wrapper for the backend REST API (SPEC §8). Same-origin: /api is proxied in dev and by nginx in docker. */
export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export async function getJson<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { Accept: 'application/json', ...init?.headers },
    credentials: 'same-origin',
  })
  const text = await res.text()
  const body = text ? safeParse(text) : null
  if (!res.ok) {
    const detail = body && typeof body === 'object' && 'detail' in body ? String(body.detail) : res.statusText
    throw new ApiError(res.status, detail || `Request failed (${res.status})`)
  }
  return body as T
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}
