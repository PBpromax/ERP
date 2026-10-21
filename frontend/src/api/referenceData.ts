import http from './http'

type CacheEntry<T> = {
  value?: T
  expiresAt: number
  pending?: Promise<T>
}

const TTL_MS = 5 * 60_000
const cache = new Map<string, CacheEntry<any[]>>()
let generation = 0

async function loadReference<T>(endpoint: string): Promise<T[]> {
  const requestGeneration = generation
  const now = Date.now()
  const entry = cache.get(endpoint) as CacheEntry<T[]> | undefined
  if (entry?.value && entry.expiresAt > now) return entry.value
  if (entry?.pending) return entry.pending

  const pending = http.get<T[], T[]>(endpoint)
    .then(value => {
      if (requestGeneration === generation) cache.set(endpoint, { value, expiresAt: Date.now() + TTL_MS })
      return value
    })
    .catch(error => {
      if (requestGeneration === generation) cache.delete(endpoint)
      throw error
    })
  cache.set(endpoint, { value: entry?.value, expiresAt: entry?.expiresAt || 0, pending })
  return pending
}

export function loadCustomers<T = any>() {
  return loadReference<T>('/references/customers')
}

export function loadProducts<T = any>() {
  return loadReference<T>('/references/products')
}

export function invalidateReferenceData(endpoint?: string) {
  generation++
  if (endpoint) cache.delete(endpoint.replace('/master/', '/references/'))
  else cache.clear()
}

export async function warmReferenceData() {
  await Promise.allSettled([loadCustomers(), loadProducts()])
}
