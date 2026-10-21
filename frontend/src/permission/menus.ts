export interface MenuEntry {
  code: string
  group: string
  label: string
  path: string
  business: boolean
  parentCode?: string | null
}
export interface UserProfile {
  id: number
  username: string
  realName: string
  enabled: boolean
  owner: boolean
  orderPricesVisible: boolean
  permissionMode: 'CUSTOM' | 'ALL_BUSINESS'
  menuCodes: string[]
  effectiveMenuCodes: string[]
}
export function canAccessPath(path: string, codes: string[], catalog: MenuEntry[]) {
  const clean = path.split('?')[0]
  if (clean === '/no-access') return true
  if (clean.startsWith('/print/')) {
    const type = clean.split('/')[2]
    const requirements: Record<string, string[]> = { quotation: ['quotations'], delivery: ['deliveries'], statement: ['statements', 'invoices'] }
    return (requirements[type] || []).some(code => codes.includes(code))
  }
  const entry = catalog.find(item => !item.parentCode && item.path === clean)
  return !!entry && codes.includes(entry.code)
}
export function firstAccessiblePath(codes: string[], catalog: MenuEntry[]) {
  return catalog.find(entry => !entry.parentCode && codes.includes(entry.code))?.path || '/no-access'
}
