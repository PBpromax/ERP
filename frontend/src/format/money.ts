interface Decimal { coefficient: bigint; scale: number }

function decimal(value: unknown): Decimal | null {
  if (value == null || String(value).trim() === '') return null
  const text = String(value).trim().replace(/,/g, '')
  const match = /^([+-]?)(\d*)(?:\.(\d*))?(?:e([+-]?\d+))?$/i.exec(text)
  if (!match || (!match[2] && !match[3])) return null
  const exponent = Number(match[4] || 0)
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 10000) return null
  let coefficient = BigInt((match[2] || '0') + (match[3] || '')) * (match[1] === '-' ? -1n : 1n)
  let scale = (match[3] || '').length - exponent
  if (scale < 0) { coefficient *= 10n ** BigInt(-scale); scale = 0 }
  return { coefficient, scale }
}

function plain({ coefficient, scale }: Decimal): string {
  if (coefficient === 0n) return '0'
  const sign = coefficient < 0n ? '-' : ''
  let digits = (coefficient < 0n ? -coefficient : coefficient).toString().padStart(scale + 1, '0')
  if (scale) digits = digits.slice(0, -scale) + '.' + digits.slice(-scale).replace(/0+$/, '')
  return sign + digits.replace(/\.$/, '')
}

/** Add grouping separators and remove only insignificant trailing zeros. Never round a value. */
export function formatMoney(value: unknown, empty = '0') {
  const parts = decimal(value)
  if (!parts) return value == null || String(value).trim() === '' ? empty : String(value)
  const [integer, fraction] = plain(parts).split('.')
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (fraction ? '.' + fraction : '')
}

export function formatPrice(value: unknown, empty = '—') { return formatMoney(value, empty) }

export function decimalMultiply(a: unknown, b: unknown): string {
  const left = decimal(a), right = decimal(b)
  if (!left || !right) return '0'
  return plain({ coefficient: left.coefficient * right.coefficient, scale: left.scale + right.scale })
}

export function decimalSum(values: Iterable<unknown>): string {
  let total: Decimal = { coefficient: 0n, scale: 0 }
  for (const value of values) {
    const next = decimal(value)
    if (!next) continue
    const scale = Math.max(total.scale, next.scale)
    total = { coefficient: total.coefficient * 10n ** BigInt(scale - total.scale)
      + next.coefficient * 10n ** BigInt(scale - next.scale), scale }
  }
  return plain(total)
}

export function decimalSubtract(a: unknown, b: unknown): string { return decimalSum([a, decimalMultiply(b, -1)]) }
export function moneyColumn(_row: unknown, _column: unknown, value: unknown) { return formatMoney(value) }
export function priceColumn(_row: unknown, _column: unknown, value: unknown) { return formatPrice(value) }
