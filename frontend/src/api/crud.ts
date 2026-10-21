import http from './http'
import type { AxiosRequestConfig } from 'axios'
import { invalidateReferenceData } from './referenceData'

export interface PageResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

export function list<T = any>(endpoint: string) {
  return http.get<T[], T[]>(endpoint)
}

export function get<T = any>(endpoint: string, id: number | string) {
  return http.get<T, T>(`${endpoint}/${id}`)
}

function invalidateMutableReference(endpoint: string) {
  if (endpoint === '/master/customers' || endpoint === '/master/products') {
    invalidateReferenceData(endpoint)
  }
}

export async function create<T = any>(endpoint: string, data: any) {
  const result = await http.post<T, T>(endpoint, data)
  invalidateMutableReference(endpoint)
  return result
}

export async function update<T = any>(endpoint: string, id: number | string, data: any) {
  const result = await http.put<T, T>(`${endpoint}/${id}`, data)
  invalidateMutableReference(endpoint)
  return result
}

export async function remove(endpoint: string, id: number | string) {
  const result = await http.delete(`${endpoint}/${id}`)
  invalidateMutableReference(endpoint)
  return result
}

export function postAction<T = any>(url: string, data?: any) {
  return http.post<T, T>(url, data || {})
}

export function getAction<T = any>(url: string, config?: AxiosRequestConfig) {
  return http.get<T, T>(url, config)
}
