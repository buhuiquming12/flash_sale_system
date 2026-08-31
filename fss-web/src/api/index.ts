import { get, post } from './http'
import type {
  ActivityCreateCmd,
  ActivityDetailVO,
  ActivityListItemVO,
  DegradeStatus,
  LoginVO,
  MockSignVO,
  OrderVO,
  PageR,
  PayCreateVO,
  PayStatusVO,
  ProductCreateCmd,
  SeckillResultVO,
  SeckillSubmitVO,
  SeckillTokenVO,
  SkuCreateCmd,
  SkuVO,
  UserVO,
} from './types'

export const userApi = {
  register: (username: string, password: string, nickname?: string) =>
    post<{ userId: number }>('/api/user/register', { username, password, nickname }),
  login: (username: string, password: string) =>
    post<LoginVO>('/api/user/login', { username, password }),
  me: () => get<UserVO>('/api/user/me'),
  /** 服务端会把 jti 从 Redis 白名单里删掉，是真的撤销会话，不只是前端丢 token */
  logout: () => post<void>('/api/user/logout'),
}

export const activityApi = {
  list: (page = 1, size = 10, status?: number) =>
    get<PageR<ActivityListItemVO>>('/api/activity/list', { page, size, status }),
  detail: (activityId: number) => get<ActivityDetailVO>(`/api/activity/${activityId}`),
}

export const seckillApi = {
  /** 令牌接口用 query param 而非 body（后端是 @RequestParam） */
  token: (activityId: number, skuId: number) =>
    post<SeckillTokenVO>('/api/seckill/token', undefined, { activityId, skuId }),

  /** 往令牌决定的动态路径提交。path 由服务端下发，不要在前端拼 */
  submit: (path: string, activityId: number, skuId: number, quantity = 1) =>
    post<SeckillSubmitVO>(path, { activityId, skuId, quantity }),

  /**
   * 固定路径提交，不校验令牌。后端保留它是为了压测联调，
   * 这里也留一个入口，方便在演示时对比「有令牌」和「没令牌」两条路。
   * 生产应当在网关层只放开动态路径。
   */
  submitDirect: (activityId: number, skuId: number, quantity = 1) =>
    post<SeckillSubmitVO>('/api/seckill/do', { activityId, skuId, quantity }),

  /**
   * 查结果。activityId / skuId 必须带：结果 key 含 `{activityId:skuId}` hash tag，
   * 不带就只能回查数据库，命不中 Redis。
   */
  result: (requestNo: string, activityId: number, skuId: number) =>
    get<SeckillResultVO>('/api/seckill/result', { requestNo, activityId, skuId }),
}

export const orderApi = {
  list: (page = 1, size = 10, status?: number) =>
    get<PageR<OrderVO>>('/api/order/list', { page, size, status }),
  detail: (orderNo: string) => get<OrderVO>(`/api/order/${orderNo}`),
  cancel: (orderNo: string) =>
    post<{ cancelled: boolean; notice: string }>(`/api/order/${orderNo}/cancel`),
}

export const payApi = {
  create: (orderNo: string) => post<PayCreateVO>('/api/pay/create', { orderNo }),
  /** dev profile 专属：把验签密钥的能力借给前端，生产环境这个接口不存在 */
  mockSign: (payNo: string, amount: string, status = 'SUCCESS') =>
    get<MockSignVO>('/api/pay/mock-sign', { payNo, amount, status }),
  notify: (signed: MockSignVO) =>
    post<void>('/api/pay/notify', {
      payNo: signed.payNo,
      outTradeNo: signed.outTradeNo,
      // amount 原样回传字符串，不要 Number() 一下。
      // 验签是对「渠道实际发来的字段」算的，服务端用 amount.toPlainString() 重建串：
      // mock-sign 签的是 "4999.00"，转成数字再发回去就变成 4999，
      // toPlainString() 得到 "4999"，两串不等 → PAY_SIGN_INVALID。
      // Jackson 能从 JSON 字符串反序列化 BigDecimal 并保留标度，原样传最稳。
      amount: signed.amount,
      status: signed.status,
      timestamp: Number(signed.timestamp),
      sign: signed.sign,
    }),
  status: (orderNo: string) => get<PayStatusVO>('/api/pay/status', { orderNo }),
}

export const adminApi = {
  createProduct: (cmd: ProductCreateCmd) =>
    post<{ productId: number }>('/api/admin/product', cmd),
  createSku: (cmd: SkuCreateCmd) => post<{ skuId: number }>('/api/admin/sku', cmd),
  listSkus: (productId: number) => get<SkuVO[]>('/api/admin/sku/list', { productId }),
  createActivity: (cmd: ActivityCreateCmd) =>
    post<{ activityId: number }>('/api/admin/activity', cmd),
  publish: (activityId: number) => post<void>(`/api/admin/activity/${activityId}/publish`),
  close: (activityId: number) => post<void>(`/api/admin/activity/${activityId}/close`),
  /** 幂等：库存用 setIfAbsent 初始化，重复预热不会把已扣减的库存重置 */
  warmup: (activityId: number) => post<void>(`/api/admin/activity/${activityId}/warmup`),
  /**
   * 活动进行中改库存的唯一入口。注意 id 是 `t_seckill_goods.id`（秒杀商品 ID），
   * 不是 skuId——活动详情接口没有下发这个 ID，所以管理端只能手填。
   */
  adjustStock: (goodsId: number, delta: number, reason: string) =>
    post<void>(`/api/admin/goods/${goodsId}/stock-adjust`, { delta, reason }),
  getDegrade: () => get<DegradeStatus>('/api/admin/degrade'),
  /** reason 是必填的：降级要被复盘，「谁因为什么降的级」得能从审计日志查到 */
  setDegrade: (level: number, reason: string) =>
    post<DegradeStatus>('/api/admin/degrade', { level, reason }),
}
