import axios from 'axios'
import { ElMessage } from 'element-plus'

declare module 'axios' {
  interface AxiosRequestConfig {
    skipErrorToast?: boolean
  }
}

export interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 20000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('OMS_TOKEN')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<any>
    if (body && body.success === false) {
      if (!response.config.skipErrorToast) ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    if (!['get', 'head', 'options'].includes((response.config.method || 'get').toLowerCase())) {
      window.dispatchEvent(new Event('oms:data-changed'))
    }
    return body?.data ?? response.data
  },
  (error) => {
    if (axios.isCancel(error)) return Promise.reject(error)
    const status = error?.response?.status
    const requestToken = String(error?.config?.headers?.Authorization || '').replace(/^Bearer /, '')
    if (status === 401 && requestToken === localStorage.getItem('OMS_TOKEN')) window.dispatchEvent(new Event('oms:auth-expired'))
    if (status === 403) window.dispatchEvent(new Event('oms:permissions-refresh'))
    if (!error?.config?.skipErrorToast) {
      ElMessage.error(error?.response?.data?.message || error.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

export default http
