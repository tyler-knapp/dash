import type { AccountBalance } from '../api/networth'

export const shortDate = (iso: string, withYear = false) =>
  new Date(iso + 'T12:00').toLocaleDateString('en-US', withYear ? { month: 'short', day: 'numeric', year: 'numeric' } : { month: 'short', day: 'numeric' })

/** The prototype's meta line under each account: "Manual · updated Oct 1", "Retirement · linked", or the kind. */
export function metaFor(a: AccountBalance): string {
  if (a.isManual) return a.asOf ? 'Manual · updated ' + shortDate(a.asOf) : 'Manual'
  return a.kind + (a.kind === 'Retirement' ? ' · linked' : '')
}
