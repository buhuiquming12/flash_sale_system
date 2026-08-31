import type { Decimal, DateTimeStr } from '@/api/types'

/** 金额展示。BigDecimal 可能是数字也可能是字符串，统一两位小数 */
export function formatMoney(v: Decimal | null | undefined): string {
  if (v === null || v === undefined || v === '') return '-'
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : String(v)
}

/**
 * 解析后端的 LocalDateTime。
 *
 * Jackson 默认给 ISO-8601（`2026-08-31T20:00:00`），`new Date()` 能直接吃，
 * 且不带时区后缀时按本地时区解释——后端时区是 Asia/Shanghai，本地演示时一致。
 * 兼容一下空格分隔的写法，免得后端哪天配了 `date-format` 就整页 NaN。
 */
export function parseTime(s: DateTimeStr | null | undefined): Date | null {
  if (!s) return null
  const d = new Date(s.includes('T') ? s : s.replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d
}

export function formatTime(s: DateTimeStr | null | undefined): string {
  const d = parseTime(s)
  if (!d) return '-'
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 只要时分秒，时间线上一屏能放更多条 */
export function formatClock(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.` +
    String(d.getMilliseconds()).padStart(3, '0')
}

/** 秒数转 `12:34` / `1:02:03`，用于订单剩余支付时间与开抢倒计时 */
export function formatCountdown(seconds: number): string {
  if (seconds <= 0) return '00:00'
  const s = Math.floor(seconds % 60)
  const m = Math.floor((seconds / 60) % 60)
  const h = Math.floor(seconds / 3600)
  const p = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`
}

/**
 * 转成后端 `LocalDateTime` 能反序列化的本地 ISO 串（无时区后缀）。
 *
 * 不能用 `toISOString()`：它转成 UTC 并带 `Z`，后端按 `LocalDateTime` 解析时
 * 会直接把 UTC 的墙上时间当本地时间用，活动开始时间凭空差 8 小时。
 */
export function toLocalIso(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}` +
    `T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
