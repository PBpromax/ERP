import { useUserStore } from '@/stores/user'

const moneyFields = new Set([
  'unitPrice',
  'totalAmount',
  'amount',
  'costPrice',
  'stockAmount',
  'taxAmount',
  'receivableAmount',
  'invoicedAmount',
  'receivedAmount',
  'arrearsAmount',
  'lastPurchasePrice',
  'paidAmount',
  'defaultPrice',
  'creditLimit'
])

export function canReadField(field: string) {
  const user = useUserStore()
  if (moneyFields.has(field)) {
    return user.canReadMoney
  }
  return true
}

export function displayValue(row: any, field: string) {
  if (!canReadField(field)) return '***'
  const value = row?.[field]
  return value ?? ''
}
