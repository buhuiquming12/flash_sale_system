import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/** 一条请求的观测记录。观测面板与秒杀链路视图都读这里 */
export interface TraceRecord {
  id: number
  ts: number
  method: string
  url: string
  /** HTTP 状态码。业务失败是 200，只有 401/403/429/503 才非 200 */
  httpStatus: number
  /** 业务码，见 docs/06-接口契约 错误码表 */
  code: number
  message: string
  traceId: string
  durationMs: number
  ok: boolean
  /** 链路阶段标签，如「领令牌」「提交」「轮询」，便于在时间线上分组 */
  tag?: string
  retryAfter?: string
}

const MAX_RECORDS = 300

export const useTraceStore = defineStore('trace', () => {
  const records = ref<TraceRecord[]>([])
  let seq = 0

  /** 当前正在跑的链路阶段。由 useSeckill 在每一步前后设置 */
  const currentTag = ref<string | undefined>(undefined)

  function push(rec: Omit<TraceRecord, 'id' | 'ts' | 'tag'>) {
    records.value.unshift({
      ...rec,
      id: ++seq,
      ts: Date.now(),
      tag: currentTag.value,
    })
    // 演示会连续点很多次，不设上限的话时间线会把内存和渲染都拖死
    if (records.value.length > MAX_RECORDS) {
      records.value.length = MAX_RECORDS
    }
  }

  function clear() {
    records.value = []
  }

  const total = computed(() => records.value.length)
  const failed = computed(() => records.value.filter((r) => !r.ok).length)
  const rateLimited = computed(() => records.value.filter((r) => r.code === 1004).length)
  const avgDuration = computed(() => {
    if (!records.value.length) return 0
    const sum = records.value.reduce((acc, r) => acc + r.durationMs, 0)
    return Math.round(sum / records.value.length)
  })
  const slowest = computed(() =>
    records.value.reduce((max, r) => (r.durationMs > max ? r.durationMs : max), 0),
  )

  return { records, currentTag, push, clear, total, failed, rateLimited, avgDuration, slowest }
})
