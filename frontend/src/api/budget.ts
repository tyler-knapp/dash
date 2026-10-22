import { getJson } from './client'

export interface SubSummary {
  name: string
  spent: number
}

export interface CategorySummary {
  name: string
  spent: number
  budget: number
  expectedToDate: number
  status: 'ok' | 'warn' | 'over'
  statusLabel: string
  subs: SubSummary[]
}

export interface Summary {
  from: string
  to: string
  months: string[]
  periodState: 'past' | 'current' | 'future'
  today: string
  transactionCount: number
  uncategorizedCount: number
  income: number
  spent: number
  budgeted: number
  net: number
  categories: CategorySummary[]
}

export const fetchSummary = (from: string, to: string) =>
  getJson<Summary>(`/api/summary?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`)

export const setBudget = (month: string, category: string, amount: number) =>
  getJson<{ effectiveFrom: string; amounts: Record<string, number> }>(
    `/api/budgets/${encodeURIComponent(month)}/${encodeURIComponent(category)}`,
    { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ amount }) },
  )
