export const ROLE_ADMIN = 'ADMIN'
export const ROLE_SALES = 'SALES'
export const ROLE_HR = 'HR'
export const ROLE_CLERK = 'CLERK'
export const ROLE_PRODUCTION = 'PRODUCTION'
export const ROLE_LOGISTICS = 'LOGISTICS'
export const ROLE_WAREHOUSE = 'WAREHOUSE'

export const FULL_BUSINESS_ROLES = [ROLE_ADMIN, ROLE_HR, ROLE_CLERK, ROLE_LOGISTICS]
export const SALES_ROLES = [...FULL_BUSINESS_ROLES, ROLE_SALES]
export const INVENTORY_ROLES = [...FULL_BUSINESS_ROLES, ROLE_WAREHOUSE]
export const PRODUCTION_ROLES = [...FULL_BUSINESS_ROLES, ROLE_PRODUCTION]
export const DELIVERY_ROLES = [ROLE_ADMIN, ROLE_HR, ROLE_CLERK, ROLE_LOGISTICS, ROLE_SALES]

export const routeRoles: Record<string, string[]> = {
  '/dashboard': FULL_BUSINESS_ROLES,
  '/customers': FULL_BUSINESS_ROLES,
  '/products': FULL_BUSINESS_ROLES,
  '/materials': FULL_BUSINESS_ROLES,
  '/suppliers': FULL_BUSINESS_ROLES,
  '/quotations': SALES_ROLES,
  '/orders': SALES_ROLES,
  '/deliveries': DELIVERY_ROLES,
  '/product-stocks': INVENTORY_ROLES,
  '/product-movements': INVENTORY_ROLES,
  '/inventory-transactions': INVENTORY_ROLES,
  '/semi-finished-stocks': PRODUCTION_ROLES,
  '/production-plans': PRODUCTION_ROLES,
  '/production-records': PRODUCTION_ROLES,
  '/statements': FULL_BUSINESS_ROLES,
  '/invoices': FULL_BUSINESS_ROLES,
  '/receipts': FULL_BUSINESS_ROLES,
  '/quality-reports': FULL_BUSINESS_ROLES,
  '/notifications': FULL_BUSINESS_ROLES,
  '/users': [ROLE_ADMIN],
  '/roles': [ROLE_ADMIN],
  '/field-permissions': [ROLE_ADMIN],
  '/audit-logs': [ROLE_ADMIN]
}

const fallbackPaths = [
  '/dashboard',
  '/quotations',
  '/semi-finished-stocks',
  '/product-stocks'
]

export function normalizeRoles(roleCodes?: string | string[]) {
  if (Array.isArray(roleCodes)) return roleCodes.filter(Boolean)
  return String(roleCodes || '')
    .split(',')
    .map(role => role.trim())
    .filter(Boolean)
}

export function hasRoleAccess(userRoles: string[], allowedRoles?: string[]) {
  if (!allowedRoles || allowedRoles.length === 0) return true
  return allowedRoles.some(role => userRoles.includes(role))
}

export function canAccessPath(path: string, userRoles: string[]) {
  const cleanPath = path.split('?')[0]
  const allowedRoles = routeRoles[cleanPath]
  return hasRoleAccess(userRoles, allowedRoles)
}

export function firstAccessiblePath(userRoles: string[]) {
  return fallbackPaths.find(path => canAccessPath(path, userRoles)) || '/login'
}
