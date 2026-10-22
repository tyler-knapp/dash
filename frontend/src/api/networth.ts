import { getJson } from './client'

export interface AccountBalance {
  id: string
  name: string
  institutionName: string | null
  kind: string
  mask: string | null
  displayColor: string | null
  isManual: boolean
  isSample: boolean
  linked: boolean
  current: number | null
  asOf: string | null
  source: 'plaid' | 'manual' | null
}

export interface NetWorth {
  total: number
  cashMinusCards: number
  asOf: string | null
  groups: { name: string; total: number; accounts: AccountBalance[] }[]
  withoutBalance: AccountBalance[]
}

export const fetchNetWorth = () => getJson<NetWorth>('/api/networth')
