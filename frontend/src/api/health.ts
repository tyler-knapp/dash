import { getJson } from './client'

export interface Health {
  status: 'ok' | 'degraded'
  db: 'ok' | 'down'
  plaidEnv: 'sandbox' | 'production'
  timeZone: string
  config: { plaid: boolean; tokenEncKey: boolean; anthropic: boolean; auth: boolean }
}

export const fetchHealth = () => getJson<Health>('/api/health')
