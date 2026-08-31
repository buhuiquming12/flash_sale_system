import { ref } from 'vue'
import { seckillApi } from '@/api'
import { ApiError } from '@/api/http'
import { useTraceStore } from '@/stores/trace'
import { codeText, SECKILL_STATUS } from '@/utils/enums'

export type StepStatus = 'pending' | 'running' | 'done' | 'fail' | 'skip'

export interface FlowStep {
  key: string
  label: string
  /** 这一步在做什么，讲给看演示的人 */
  hint: string
  status: StepStatus
  detail: string
  durationMs?: number
}

export interface PollAttempt {
  n: number
  waitedMs: number
  status: number
  statusDesc: string
}

export interface SeckillOutcome {
  ok: boolean
  status: number
  orderNo?: string
  message: string
}

/** 退避序列：第一档由服务端 `pollAfterMs` 决定，之后固定 2000ms 兜底 */
const BACKOFF = [500, 800, 1300, 2000]
const POLL_BUDGET_MS = 20_000

function newSteps(): FlowStep[] {
  return [
    {
      key: 'token',
      label: '领秒杀令牌',
      hint: 'POST /api/seckill/token —— 令牌一次性、绑定 userId+活动+SKU，并决定提交路径',
      status: 'pending',
      detail: '',
    },
    {
      key: 'submit',
      label: '提交秒杀',
      hint: '往令牌下发的动态路径 POST —— 服务端只做 Lua 判扣 + 投一条消息就返回',
      status: 'pending',
      detail: '',
    },
    {
      key: 'poll',
      label: '轮询结果',
      hint: 'GET /api/seckill/result —— 订单由消费端异步创建，结论要轮询才拿到',
      status: 'pending',
      detail: '',
    },
    {
      key: 'done',
      label: '拿到结论',
      hint: '成功给订单号，失败给具体原因，不会是笼统的「系统繁忙」',
      status: 'pending',
      detail: '',
    },
  ]
}

/**
 * 把一次秒杀拆成可视化的四步。
 *
 * 这个 composable 的存在意义不只是复用逻辑，更是把「阶段三之后接口只返回
 * 排队中」这件事在界面上讲清楚：同步返回订单号的那条路已经没有了，
 * 用户看到的成功来自轮询，而轮询间隔是服务端下发的。
 */
export function useSeckill() {
  const steps = ref<FlowStep[]>(newSteps())
  const attempts = ref<PollAttempt[]>([])
  const running = ref(false)
  const outcome = ref<SeckillOutcome | null>(null)
  const requestNo = ref('')

  const trace = useTraceStore()

  function step(key: string): FlowStep {
    return steps.value.find((s) => s.key === key)!
  }

  function reset() {
    steps.value = newSteps()
    attempts.value = []
    outcome.value = null
    requestNo.value = ''
  }

  async function run(activityId: number, skuId: number, useToken = true): Promise<SeckillOutcome> {
    reset()
    running.value = true
    try {
      const path = await doToken(activityId, skuId, useToken)
      const submit = await doSubmit(path, activityId, skuId)
      if (submit.status !== 0) {
        // 阶段三之后正常不会走到这里；保留是因为同步成功的分支后端仍然可能返回
        step('poll').status = 'skip'
        step('poll').detail = '服务端直接给了结论，无需轮询'
        return finish(submit.status, submit.orderNo ?? undefined)
      }
      requestNo.value = submit.requestNo
      return await doPoll(submit, activityId, skuId)
    } catch {
      // timed() 已经把失败写进对应步骤和 outcome，这里只负责别把异常抛给视图——
      // 抢购失败是预期路径（库存不足、已参与过），不该表现成一个未处理错误
      return outcome.value ?? { ok: false, status: -1, message: '秒杀失败' }
    } finally {
      running.value = false
      trace.currentTag = undefined
    }
  }

  async function doToken(activityId: number, skuId: number, useToken: boolean): Promise<string> {
    if (!useToken) {
      const s = step('token')
      s.status = 'skip'
      s.detail = '跳过令牌，直接打固定路径 /api/seckill/do（压测入口，生产应在网关屏蔽）'
      return ''
    }
    return await timed('token', '领令牌', async () => {
      const t = await seckillApi.token(activityId, skuId)
      step('token').detail = `令牌 ${t.token.slice(0, 8)}… → 提交路径 ${t.path}`
      return t.path
    })
  }

  async function doSubmit(path: string, activityId: number, skuId: number) {
    return await timed('submit', '提交', async () => {
      const vo = path
        ? await seckillApi.submit(path, activityId, skuId)
        : await seckillApi.submitDirect(activityId, skuId)
      const stock = vo.remainStock === null ? '未下发（降级中）' : vo.remainStock
      step('submit').detail =
        `${SECKILL_STATUS[vo.status]?.text ?? vo.statusDesc}` +
        `｜requestNo=${vo.requestNo}｜Redis 剩余库存 ${stock}` +
        `｜服务端要求 ${vo.pollAfterMs ?? 300}ms 后再查`
      return vo
    })
  }

  async function doPoll(
    submit: { requestNo: string; pollAfterMs: number | null },
    activityId: number,
    skuId: number,
  ): Promise<SeckillOutcome> {
    const s = step('poll')
    s.status = 'running'
    trace.currentTag = '轮询'
    const started = performance.now()
    let n = 0
    let elapsed = 0

    while (elapsed < POLL_BUDGET_MS) {
      // 第一档用服务端下发的间隔，之后退避——绝大多数请求第一次就有结论了，
      // 固定 300ms 轮询会让真的要等几秒的请求白打十几次
      const wait = n === 0 ? (submit.pollAfterMs ?? 300) : (BACKOFF[n - 1] ?? 2000)
      await sleep(wait)
      elapsed += wait
      n += 1

      try {
        const r = await seckillApi.result(submit.requestNo, activityId, skuId)
        attempts.value.push({ n, waitedMs: wait, status: r.status, statusDesc: r.statusDesc })
        s.detail = `第 ${n} 次查询，累计等待 ${elapsed}ms，当前「${r.statusDesc}」`
        if (r.status === 0) continue
        s.status = 'done'
        s.durationMs = Math.round(performance.now() - started)
        return finish(r.status, r.orderNo ?? undefined, r.failReason ?? undefined)
      } catch (e) {
        // 轮询本身被限流（result-qps 默认 5）时不该放弃，退避后继续——
        // 放弃会让用户以为没抢到，而资格其实已经拿到了
        const err = asApiError(e)
        attempts.value.push({ n, waitedMs: wait, status: -1, statusDesc: codeText(err.code, err.message) })
        if (err.code !== 1004 && err.code !== 1005) {
          s.status = 'fail'
          s.detail = codeText(err.code, err.message)
          return failOutcome(err)
        }
      }
    }

    s.status = 'fail'
    s.detail = `${Math.round(POLL_BUDGET_MS / 1000)}s 内没拿到结论`
    step('done').status = 'fail'
    step('done').detail = '结果查询超时，去订单列表确认'
    outcome.value = { ok: false, status: 0, message: '结果查询超时，请到订单列表确认' }
    return outcome.value
  }

  function finish(status: number, orderNo?: string, failReason?: string): SeckillOutcome {
    const d = step('done')
    const meta = SECKILL_STATUS[status]
    const ok = status === 1
    d.status = ok ? 'done' : 'fail'
    d.detail = ok
      ? `订单 ${orderNo}，15 分钟内完成支付`
      : `${meta?.text ?? status}${failReason ? `：${failReason}` : ''}`
    outcome.value = { ok, status, orderNo, message: d.detail }
    return outcome.value
  }

  function failOutcome(err: ApiError): SeckillOutcome {
    const d = step('done')
    d.status = 'fail'
    d.detail = codeText(err.code, err.message)
    outcome.value = { ok: false, status: -1, message: d.detail }
    return outcome.value
  }

  /** 包一层计时与状态流转，让每一步在界面上都有 RT 可看 */
  async function timed<T>(key: string, tag: string, fn: () => Promise<T>): Promise<T> {
    const s = step(key)
    s.status = 'running'
    trace.currentTag = tag
    const t0 = performance.now()
    try {
      const out = await fn()
      s.status = 'done'
      s.durationMs = Math.round(performance.now() - t0)
      return out
    } catch (e) {
      const err = asApiError(e)
      s.status = 'fail'
      s.durationMs = Math.round(performance.now() - t0)
      s.detail = codeText(err.code, err.message)
      steps.value.filter((x) => x.status === 'pending').forEach((x) => (x.status = 'skip'))
      failOutcome(err)
      throw e
    }
  }

  return { steps, attempts, running, outcome, requestNo, run, reset }
}

function sleep(ms: number) {
  return new Promise<void>((r) => setTimeout(r, ms))
}

function asApiError(e: unknown): ApiError {
  return e instanceof ApiError ? e : new ApiError(9000, e instanceof Error ? e.message : '未知错误')
}
