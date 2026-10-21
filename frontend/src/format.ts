/** Money formatting, same as the prototype's fmt(): real minus sign, $, thousands separators. */
export function fmt(n: number, cents = true): string {
  return (
    (n < 0 ? '−' : '') +
    '$' +
    Math.abs(n).toLocaleString('en-US', {
      minimumFractionDigits: cents ? 2 : 0,
      maximumFractionDigits: cents ? 2 : 0,
    })
  )
}
