import { getJson } from './client'
import type { Account } from './ledger'

const send = <T>(method: string, path: string, body: unknown) =>
  getJson<T>(path, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })

export type ManualType = 'depository' | 'credit' | 'investment' | 'loan' | 'other'

export const createManualAccount = (name: string, type: ManualType) =>
  send<Account>('POST', '/api/accounts/manual', { name, type })

export const patchAccount = (id: string, patch: { name?: string; displayColor?: string; hidden?: boolean }) =>
  send<Account>('PATCH', `/api/accounts/${encodeURIComponent(id)}`, patch)

/** {@code current} is app sign: what you owe is negative. */
export const enterBalance = (id: string, asOf: string, current: number) =>
  send<Account>('POST', `/api/accounts/${encodeURIComponent(id)}/balance`, { asOf, current })

/** Owed-type accounts: the form asks for "amount owed" as a positive number. */
export const isOwedType = (type: string | null) => type === 'credit' || type === 'loan'
