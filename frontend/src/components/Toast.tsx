import { useEffect } from 'react'

/** Bottom toast, styled like the prototype's showError(). */
export function Toast({ message, onDone }: { message: string | null; onDone: () => void }) {
  useEffect(() => {
    if (!message) return
    const h = setTimeout(onDone, 4000)
    return () => clearTimeout(h)
  }, [message, onDone])
  if (!message) return null
  return (
    <div className="toast" role="status">
      {message}
    </div>
  )
}
