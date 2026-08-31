/**
 * 后端 VO / Cmd 的 TypeScript 镜像。
 *
 * 字段名与 `fss-biz` 里的 `*VO` / `*Cmd` 逐一对应，改后端记得改这里——
 * 类型不是文档的副本，它是唯一会在 `npm run build` 时报错的那份契约。
 *
 * 时间统一是后端 `LocalDateTime` 的 Jackson 默认形态（ISO-8601，如
 * `2026-08-31T20:00:00`），不是时间戳；金额是 `BigDecimal`，Jackson
 * 按数字序列化，但少数接口（mock-sign 返回 `Map<String,String>`）给的是字符串，
 * 所以统一按 `Decimal` 处理，展示前一律过 `formatMoney`。
 */

export type Decimal = number | string
export type DateTimeStr = string

/** 统一响应体 `com.fss.common.result.R` */
export interface R<T> {
  code: number
  message: string
  data: T
  traceId?: string
}

/** 分页结果体 `com.fss.common.result.PageR` */
export interface PageR<T> {
  total: number
  page: number
  size: number
  list: T[]
}

// ---------------------------------------------------------------- 用户

export interface LoginVO {
  token: string
  expiresIn: number
  userId: number
  username: string
  role: number
}

export interface UserVO {
  userId: number
  username: string
  nickname: string
  role: number
  status: number
}

// ---------------------------------------------------------------- 活动

export interface ActivityListItemVO {
  activityId: number
  name: string
  startTime: DateTimeStr
  endTime: DateTimeStr
  status: number
  statusDesc: string
  goodsCount: number
}

/** 库存档位。Level 1 降级后后端只给档位不给精确值 */
export type StockLevel = 'AVAILABLE' | 'LOW' | 'SOLD_OUT'

export interface GoodsItemVO {
  skuId: number
  productTitle: string
  spec: string
  originPrice: Decimal
  seckillPrice: Decimal
  totalStock: number | null
  remainStock: number | null
  stockLevel: StockLevel | null
  limitPerUser: number
  soldOut: boolean
  image: string | null
}

export interface ActivityDetailVO {
  activityId: number
  name: string
  startTime: DateTimeStr
  endTime: DateTimeStr
  status: number
  statusDesc: string
  /** 客户端倒计时必须用它校准本地时钟，否则本地快 30s 就会在未开始时疯狂提交 */
  serverTime: DateTimeStr
  goodsList: GoodsItemVO[]
}

// ---------------------------------------------------------------- 秒杀

export interface SeckillTokenVO {
  token: string
  /** 服务端给出的动态提交路径，客户端不要自己拼 */
  path: string
}

export interface SeckillSubmitVO {
  requestNo: string
  status: number
  statusDesc: string
  orderNo: string | null
  remainStock: number | null
  expireTime: DateTimeStr | null
  /** 轮询间隔由服务端下发，降级时它会被拉长，前端不要硬编码 */
  pollAfterMs: number | null
}

export interface SeckillResultVO {
  requestNo: string
  status: number
  statusDesc: string
  orderNo: string | null
  failReason: string | null
  expireTime: DateTimeStr | null
  pollAfterMs: number | null
}

// ---------------------------------------------------------------- 订单与支付

export interface OrderItemVO {
  skuId: number
  productTitle: string
  spec: string
  unitPrice: Decimal
  quantity: number
  image: string | null
}

export interface OrderVO {
  orderNo: string
  status: number
  statusDesc: string
  totalAmount: Decimal
  payAmount: Decimal
  quantity: number
  expireTime: DateTimeStr | null
  payTime: DateTimeStr | null
  createTime: DateTimeStr
  cancelReason: string | null
  /** 服务端算好的剩余支付秒数，前端不要自己减，避免时钟问题 */
  remainSeconds: number | null
  items: OrderItemVO[]
}

export interface PayCreateVO {
  payNo: string
  amount: Decimal
  payUrl: string
}

export interface PayStatusVO {
  orderNo: string
  orderStatus: number
  orderStatusDesc: string
  payStatus: number
  payStatusDesc: string
  payTime: DateTimeStr | null
}

/** `/api/pay/mock-sign` 返回的是 `Map<String,String>`，全字段都是字符串 */
export interface MockSignVO {
  payNo: string
  outTradeNo: string
  amount: string
  status: string
  timestamp: string
  sign: string
}

// ---------------------------------------------------------------- 管理端

export interface SkuVO {
  skuId: number
  productId: number
  productTitle: string
  spec: string
  price: Decimal
  stock: number
  status: number
  image: string | null
}

export interface ProductCreateCmd {
  title: string
  subTitle?: string
  detail?: string
  mainImage?: string
  status?: number
}

export interface SkuCreateCmd {
  productId: number
  spec: string
  price: Decimal
  stock: number
  status?: number
}

export interface SeckillGoodsCmd {
  skuId: number
  seckillPrice: Decimal
  totalStock: number
  /** 后端 `@Max(1)`：当前版本限购必须为 1 */
  limitPerUser: 1
}

export interface ActivityCreateCmd {
  name: string
  startTime: DateTimeStr
  endTime: DateTimeStr
  goods: SeckillGoodsCmd[]
}

/** `/api/admin/degrade` 的响应，AdminController#status() 拼的 Map */
export interface DegradeStatus {
  level: number
  autoLevel: number
  seckillEnabled: boolean
  showExactStock: boolean
  browseEnabled: boolean
  pollIntervalMs: number
}
