import axios, { type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import type { R } from './types'
import { getToken, clearSession } from './session'
import { useTraceStore } from '@/stores/trace'

declare module 'axios' {
  export interface InternalAxiosRequestConfig {
    /** 发起时刻，用于算 RT。写在 config 上而不是闭包里，重试时也能重置 */
    __start?: number
  }
}

/** 业务异常。`code` 是后端错误码表里的码，不是 HTTP 状态码 */
export class ApiError extends Error {
  constructor(
    readonly code: number,
    message: string,
    readonly traceId = '',
    readonly httpStatus = 0,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 401 的处理交给外部注入，避免 http 层反向依赖 router */
let unauthorizedHandler: (() => void) | null = null
export function onUnauthorized(fn: () => void): void {
  unauthorizedHandler = fn
}

const http = axios.create({
  // 走 Vite proxy，前端不关心后端端口；生产同源部署也是这个形态
  baseURL: '',
  timeout: 15_000,
  headers: { 'Content-Type': 'application/json' },
  // 业务失败是 200 + 非 0 code，但 401/403/429/503 是真的非 200；
  // 全部放行到响应拦截器里统一按响应体判断，才能拿到 traceId 和错误码
  validateStatus: () => true,
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  config.__start = performance.now()
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    const cfg = resp.config as InternalAxiosRequestConfig
    const durationMs = Math.round(performance.now() - (cfg.__start ?? performance.now()))
    const body = (resp.data ?? {}) as Partial<R<unknown>>
    // 非 JSON 响应（例如代理没起来时返回的 HTML）没有 code，按系统错误记
    const code = typeof body.code === 'number' ? body.code : resp.status === 200 ? 0 : -1
    const message = body.message ?? `HTTP ${resp.status}`
    const traceId = body.traceId ?? ''

    useTraceStore().push({
      method: (cfg.method ?? 'get').toUpperCase(),
      url: cfg.url ?? '',
      httpStatus: resp.status,
      code,
      message,
      traceId,
      durationMs,
      ok: code === 0,
      retryAfter: resp.headers['retry-after'] as string | undefined,
    })

    if (code === 0) return resp
    if (resp.status === 401) {
      clearSession()
      unauthorizedHandler?.()
    }
    return Promise.reject(new ApiError(code, message, traceId, resp.status))
  },
  (err) => {
    // 走到这里只剩网络错误与超时——validateStatus 放行了所有状态码
    const cfg = (err.config ?? {}) as InternalAxiosRequestConfig
    const durationMs = Math.round(performance.now() - (cfg.__start ?? performance.now()))
    const message = err.code === 'ECONNABORTED' ? '请求超时' : '无法连接后端服务'
    useTraceStore().push({
      method: (cfg.method ?? 'get').toUpperCase(),
      url: cfg.url ?? '',
      httpStatus: 0,
      code: -1,
      message,
      traceId: '',
      durationMs,
      ok: false,
    })
    return Promise.reject(new ApiError(-1, message))
  },
)

/** 拆掉 `R<T>` 外壳，调用方只拿 data。失败一律走异常，不用每处判 code */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const resp = await http.request<R<T>>(config)
  return resp.data.data
}

export const get = <T>(url: string, params?: Record<string, unknown>) =>
  request<T>({ method: 'get', url, params })

export const post = <T>(url: string, data?: unknown, params?: Record<string, unknown>) =>
  request<T>({ method: 'post', url, data, params })

export default http
