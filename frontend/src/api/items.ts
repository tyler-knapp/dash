import { getJson, postJson } from './client'

export type ItemStatus = 'ok' | 'login_required' | 'error'

export interface AccountView {
  id: string
  name: string
  officialName: string | null
  mask: string | null
  type: string | null
  subtype: string | null
  role: 'ledger' | 'balance_only'
  displayColor: string | null
  hidden: boolean
  isManual: boolean
  currentBalance: number | null
  balanceAsOf: string | null
}

export interface ItemView {
  id: string
  institutionName: string | null
  status: ItemStatus
  lastSyncedAt: string | null
  products: string[]
  transactionsUpdateStatus: string | null
  lastErrorCode: string | null
  accounts: AccountView[]
}

export interface SyncResult {
  itemId: string
  institutionName: string | null
  outcome: 'ok' | 'login_required' | 'error' | 'skipped'
  added: number
  modified: number
  removed: number
  errorCode: string | null
  message: string | null
}

export type LinkKind = 'bank' | 'retirement'

export const fetchItems = () => getJson<ItemView[]>('/api/items')

export const createLinkToken = (opts: { itemId?: string; kind?: LinkKind }) =>
  postJson<{ linkToken: string }>('/api/plaid/link-token', opts).then((r) => r.linkToken)

export const exchangePublicToken = (publicToken: string) =>
  postJson<{ item: ItemView; firstSync: SyncResult }>('/api/plaid/exchange', { publicToken })

export const syncNow = () => postJson<{ results: SyncResult[] }>('/api/sync').then((r) => r.results)
