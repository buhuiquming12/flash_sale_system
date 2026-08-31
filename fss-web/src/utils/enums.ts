/**
 * 错误码 → 面向用户的文案与「该怎么办」。
 *
 * 与 `docs/06-接口契约` 的错误码表对齐。后端 message 已经是中文，
 * 这里覆盖的是那些「技术上准确但对用户没用」的说法，并给出下一步动作——
 * 秒杀场景里用户最需要知道的是「还能不能再试」，而不是失败原因的措辞。
 */
interface CodeHint {
  text: string
  /** true 表示重试有意义（限流、系统繁忙）；false 表示这条路走到头了 */
  retryable: boolean
}

const HINTS: Record<number, CodeHint> = {
  1001: { text: '参数不合法', retryable: false },
  1002: { text: '请先登录', retryable: false },
  1003: { text: '没有权限，这个操作需要管理员账号', retryable: false },
  1004: { text: '手速太快被限流了，等一秒再试', retryable: true },
  1005: { text: '系统繁忙，结果未知——去订单列表确认是否已下单', retryable: true },
  1006: { text: '服务降级中，稍后再来', retryable: true },
  2001: { text: '活动不存在', retryable: false },
  2002: { text: '活动还没开始', retryable: true },
  2003: { text: '活动已结束', retryable: false },
  2004: { text: '商品已停售', retryable: false },
  2005: { text: '活动还没预热完，稍等一下再试（管理端可手动预热）', retryable: true },
  2006: { text: '活动配置不合法', retryable: false },
  2007: { text: '秒杀商品不存在', retryable: false },
  2008: { text: 'SKU 不存在或已下架', retryable: false },
  2010: { text: '活动当前状态不允许这个操作', retryable: false },
  3001: { text: '库存不足，被抢完了', retryable: false },
  3002: { text: '你已经参与过本次秒杀了，去订单列表看', retryable: false },
  3003: { text: '秒杀令牌无效或已被用掉，重新领一个', retryable: true },
  3004: { text: '请求号重复，去查原来那次的结果', retryable: false },
  4001: { text: '订单不存在', retryable: false },
  4002: { text: '订单当前状态不允许这个操作，刷新一下', retryable: false },
  4003: { text: '订单已超时关闭', retryable: false },
  5001: { text: '支付金额与订单不符', retryable: false },
  5002: { text: '订单已经支付过了', retryable: false },
  5003: { text: '回调签名校验失败', retryable: false },
  9000: { text: '服务端内部错误，请报障并带上 traceId', retryable: true },
  [-1]: { text: '连不上后端服务，确认 8080 是否在跑', retryable: true },
}

export function codeText(code: number, fallback?: string): string {
  return HINTS[code]?.text ?? fallback ?? `请求失败（code=${code}）`
}

export function isRetryable(code: number): boolean {
  return HINTS[code]?.retryable ?? false
}

// ------------------------------------------------------------------ 状态枚举

/** `com.fss.common.enums.ActivityStatus` */
export const ACTIVITY_STATUS: Record<number, { text: string; type: TagType }> = {
  0: { text: '待发布', type: 'info' },
  1: { text: '待开始', type: 'warning' },
  2: { text: '进行中', type: 'danger' },
  3: { text: '已结束', type: 'info' },
  4: { text: '已关闭', type: 'info' },
}

/** `com.fss.common.enums.OrderStatus` */
export const ORDER_STATUS: Record<number, { text: string; type: TagType }> = {
  0: { text: '待支付', type: 'warning' },
  1: { text: '已支付', type: 'success' },
  2: { text: '已取消', type: 'info' },
  3: { text: '已完成', type: 'success' },
  4: { text: '退款中', type: 'warning' },
  5: { text: '已退款', type: 'info' },
}

/** `com.fss.common.enums.SeckillRequestStatus` */
export const SECKILL_STATUS: Record<number, { text: string; type: TagType }> = {
  0: { text: '排队中', type: 'warning' },
  1: { text: '秒杀成功', type: 'success' },
  2: { text: '库存不足', type: 'info' },
  3: { text: '已参与过', type: 'info' },
  4: { text: '创建订单失败', type: 'danger' },
  5: { text: '系统繁忙已退回', type: 'danger' },
}

export type TagType = 'success' | 'warning' | 'info' | 'danger' | 'primary'

/** 降级等级的含义，来自 `DegradeSwitch`：等级只会收紧，不会放松 */
export const DEGRADE_LEVELS: { level: number; label: string; effect: string }[] = [
  { level: 0, label: 'L0 正常', effect: '全部功能开启' },
  { level: 1, label: 'L1 隐藏精确库存', effect: '只下发库存档位（充足 / 紧张 / 售罄）' },
  { level: 2, label: 'L2 拉长轮询', effect: '结果轮询间隔从 300ms 拉到 2000ms' },
  { level: 3, label: 'L3 停止秒杀', effect: '不再分配新资格，已排队的继续处理' },
  { level: 4, label: 'L4 停止浏览', effect: '活动详情也关闭，只保留订单与支付' },
]
