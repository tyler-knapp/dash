import { useEffect, useState } from 'react'
import { usePlaidLink } from 'react-plaid-link'
import { createLinkToken, type LinkKind } from '../api/items'

interface Props {
  label: string
  /** Set for update mode (Reconnect). */
  itemId?: string
  kind?: LinkKind
  className?: string
  /** New link: called with the public token. Update mode: called with null (nothing to exchange). */
  onSuccess: (publicToken: string | null) => void
  onError: (message: string) => void
}

/**
 * Fetches a link token on click, then opens Plaid Link. The Plaid secret and access tokens never
 * reach the browser; only the short-lived link token and the public token pass through here.
 */
export function PlaidLinkButton({ label, itemId, kind, className, onSuccess, onError }: Props) {
  const [token, setToken] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const { open, ready } = usePlaidLink({
    token,
    onSuccess: (publicToken) => {
      setToken(null)
      onSuccess(itemId ? null : publicToken)
    },
    onExit: (err) => {
      setToken(null)
      if (err) onError(err.display_message || err.error_message || 'Plaid Link closed with an error.')
    },
  })

  // Link can only open once the SDK has loaded the new token.
  useEffect(() => {
    if (token && ready) open()
  }, [token, ready, open])

  const start = async () => {
    setBusy(true)
    try {
      setToken(await createLinkToken({ itemId, kind }))
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <button type="button" className={className ?? 'btn'} onClick={start} disabled={busy || !!token}>
      {busy || token ? 'Opening…' : label}
    </button>
  )
}
