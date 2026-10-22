import { getJson } from './client'

export interface Txn {
  id: string
  date: string
  merchant: string
  rawName: string | null
  amount: number
  accountId: string
  accountName: string
  accountColor: string | null
  category: string | null
  subcategory: string | null
  categorySource: 'rule' | 'plaid' | 'ai' | 'user' | null
  confidence: number | null
  reviewed: boolean
  pending: boolean
  needsReview: boolean
}

export interface TxnPage {
  transactions: Txn[]
  nextCursor: string | null
  total: number
}

export interface TxnQuery {
  from: string
  to: string
  account?: string
  category?: string
  q?: string
  needsReview?: boolean
  includeTransfers?: boolean
  cursor?: string | null
  limit?: number
}

export interface Category {
  name: string
  kind: 'spend' | 'income' | 'transfer'
  subcategories: string[]
}

export interface Account {
  id: string
  itemId: string | null
  name: string
  institutionName: string | null
  mask: string | null
  type: string | null
  subtype: string | null
  role: 'ledger' | 'balance_only'
  displayColor: string | null
  hidden: boolean
  isManual: boolean
  isSample: boolean
  currentBalance: number | null
  balanceAsOf: string | null
}

export function fetchTransactions(q: TxnQuery): Promise<TxnPage> {
  const p = new URLSearchParams({ from: q.from, to: q.to })
  if (q.account) p.set('account', q.account)
  if (q.category) p.set('category', q.category)
  if (q.q) p.set('q', q.q)
  if (q.needsReview) p.set('needsReview', 'true')
  if (q.includeTransfers) p.set('includeTransfers', 'true')
  if (q.cursor) p.set('cursor', q.cursor)
  if (q.limit) p.set('limit', String(q.limit))
  return getJson<TxnPage>('/api/transactions?' + p.toString())
}

export function recategorize(id: string, category: string, subcategory: string): Promise<Txn> {
  return getJson<Txn>('/api/transactions/' + encodeURIComponent(id), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ category, subcategory }),
  })
}

export const fetchCategories = () => getJson<Category[]>('/api/categories')
export const fetchAccounts = () => getJson<Account[]>('/api/accounts')
