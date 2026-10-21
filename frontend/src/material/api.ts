import http from '@/api/http'
import type { PageResponse } from '@/api/crud'

export interface DailyRecord {
  id: number; businessDate: string; purchaseQty: number; productionQty: number; lossQty: number
  remark?: string; versionNo: number; updatedBy?: string; updatedAt?: string
}
export interface MaterialRow {
  id: number; name: string; category: string; unit?: string
  safetyStock?: number; remark?: string; enabled: boolean; versionNo: number
  currentQty?: number; stockVersion?: number; ready: boolean; unitLocked: boolean
  today?: DailyRecord; warning: boolean
}
export interface Movement {
  id: number; businessDate?: string; createdAt: string; materialMovementType?: string
  qty: number; beforeQty: number; afterQty: number; sourceType?: string; txnType: string
  operator?: string; operatorUsername?: string; operatorUserId?: number; createdBy?: string; remark?: string
}
export interface TrendPoint { date: string; productionQty: number; lossQty: number; totalQty: number }
export const materialCategories = ['扁绳', '丝线', '圆绳', '颗粒']
export const materialUnits = ['公斤', '米', '卷', '个', '袋', '吨']
export function shanghaiToday() {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
}
export function quantity(value?: number | null) {
  return value == null ? '待完善' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 3 })
}
export const materialApi = {
  scanCode: (id: number) => http.post<{ token: string }, { token: string }>(`/master/materials/${id}/scan-code`),
  list: () => http.get<MaterialRow[], MaterialRow[]>('/master/materials'),
  get: (id: number) => http.get<MaterialRow, MaterialRow>(`/master/materials/${id}`),
  create: (data: unknown) => http.post<MaterialRow, MaterialRow>('/master/materials', data),
  update: (id: number, data: unknown) => http.put<MaterialRow, MaterialRow>(`/master/materials/${id}`, data),
  remove: (id: number) => http.delete(`/master/materials/${id}`),
  record: (id: number, date: string) => http.get<DailyRecord | null, DailyRecord | null>(`/master/materials/${id}/daily-records/${date}`),
  saveDaily: (id: number, date: string, data: unknown) => http.put<MaterialRow, MaterialRow>(`/master/materials/${id}/daily-records/${date}`, data),
  adjust: (id: number, data: unknown) => http.post<MaterialRow, MaterialRow>(`/master/materials/${id}/adjustments`, data),
  records: (id: number, params: unknown) => http.get<PageResponse<DailyRecord>, PageResponse<DailyRecord>>(`/master/materials/${id}/daily-records`, { params }),
  ledger: (id: number, params: unknown) => http.get<PageResponse<Movement>, PageResponse<Movement>>(`/master/materials/${id}/ledger`, { params }),
  trend: (id: number) => http.get<TrendPoint[], TrendPoint[]>(`/master/materials/${id}/trend`),
  warnings: () => http.get<MaterialRow[], MaterialRow[]>('/reports/material-warnings', { skipErrorToast: true })
}
