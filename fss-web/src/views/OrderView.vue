<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { orderApi, payApi } from '@/api'
import { ApiError } from '@/api/http'
import type { OrderVO } from '@/api/types'
import { codeText, ORDER_STATUS } from '@/utils/enums'
import { formatCountdown, formatMoney, formatTime } from '@/utils/format'

const orders = ref<OrderVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 10
const statusFilter = ref<number | undefined>(undefined)
const loading = ref(false)
const paying = ref<string | null>(null)
/** 本地秒表，只用来让服务端下发的 remainSeconds 走字，不参与任何判断 */
const tick = ref(0)

async function load() {
  loading.value = true
  try {
    const p = await orderApi.list(page.value, size, statusFilter.value)
    orders.value = p.list
    total.value = p.total
    tick.value = 0
  } catch (e) {
    const err = e instanceof ApiError ? e : null
    ElMessage.error(err ? codeText(err.code, err.message) : '订单加载失败')
  } finally {
    loading.value = false
  }
}

function remain(o: OrderVO): number | null {
  if (o.remainSeconds === null) return null
  return Math.max(0, o.remainSeconds - tick.value)
}

function onFilterChange() {
  page.value = 1
  void load()
}

/**
 * 演示环境的支付：创建支付流水 → 用 dev-only 接口换一份合法签名 → 自己投回调。
 *
 * 真实渠道是渠道服务器回调，前端拿不到验签密钥。mock-sign 等价于把密钥交给客户端，
 * 所以后端用 `@Profile("dev")` 把它隔离在演示环境里。
 */
async function pay(o: OrderVO) {
  paying.value = o.orderNo
  try {
    const created = await payApi.create(o.orderNo)
    const signed = await payApi.mockSign(created.payNo, String(created.amount))
    await payApi.notify(signed)
    const st = await payApi.status(o.orderNo)
    ElNotification({
      title: '支付完成',
      message: `订单 ${st.orderNo} → ${st.orderStatusDesc}，支付流水 ${st.payStatusDesc}`,
      type: 'success',
    })
  } catch (e) {
    const err = e instanceof ApiError ? e : null
    ElMessage.error(err ? codeText(err.code, err.message) : '支付失败')
  } finally {
    paying.value = null
    await load()
  }
}

async function cancel(o: OrderVO) {
  try {
    await ElMessageBox.confirm(
      '取消后库存立刻回补给其他用户，并且本次活动你将无法再次参与（决策 1：取消不恢复资格）。确认取消？',
      '取消订单',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '不取消' },
    )
  } catch {
    return
  }
  try {
    const r = await orderApi.cancel(o.orderNo)
    ElNotification({ title: '已取消', message: r.notice, type: 'info', duration: 6000 })
  } catch (e) {
    const err = e instanceof ApiError ? e : null
    ElMessage.error(err ? codeText(err.code, err.message) : '取消失败')
  } finally {
    await load()
  }
}

let timer = 0
onMounted(() => {
  void load()
  timer = window.setInterval(() => (tick.value += 1), 1000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <el-card shadow="never">
    <div class="row toolbar">
      <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px"
        @change="onFilterChange">
        <el-option v-for="(v, k) in ORDER_STATUS" :key="k" :label="v.text" :value="Number(k)" />
      </el-select>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <span class="hint">
        共 {{ total }} 单。剩余支付时间由服务端算好下发（<code class="mono">remainSeconds</code>），
        前端只负责走字，不自己判断是否超时。
      </span>
    </div>

    <el-table :data="orders" v-loading="loading" border style="width: 100%">
      <el-table-column label="订单号" width="230">
        <template #default="{ row }">
          <div class="mono">{{ row.orderNo }}</div>
          <div class="hint">{{ formatTime(row.createTime) }}</div>
        </template>
      </el-table-column>

      <el-table-column label="商品" min-width="220">
        <template #default="{ row }">
          <div v-for="it in row.items" :key="it.skuId">
            {{ it.productTitle }}
            <el-tag size="small" type="info">{{ it.spec }}</el-tag>
            <span class="muted"> × {{ it.quantity }}</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="ORDER_STATUS[row.status]?.type ?? 'info'">
            {{ row.statusDesc || ORDER_STATUS[row.status]?.text }}
          </el-tag>
          <div v-if="row.cancelReason" class="hint">{{ row.cancelReason }}</div>
        </template>
      </el-table-column>

      <el-table-column label="应付" width="110">
        <template #default="{ row }">
          <span class="mono">¥{{ formatMoney(row.payAmount) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="剩余支付" width="120">
        <template #default="{ row }">
          <span v-if="remain(row) !== null" class="mono" :style="{ color: remain(row)! <= 60 ? 'var(--fss-red)' : '' }">
            {{ formatCountdown(remain(row)!) }}
          </span>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <template v-if="row.status === 0">
            <el-button size="small" type="danger" :loading="paying === row.orderNo" @click="pay(row)">
              模拟支付
            </el-button>
            <el-button size="small" @click="cancel(row)">取消</el-button>
          </template>
          <span v-else class="muted">
            {{ row.payTime ? '支付于 ' + formatTime(row.payTime) : '-' }}
          </span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="total > size" v-model:current-page="page" :page-size="size" :total="total"
      layout="prev, pager, next" style="margin-top: 12px" @current-change="load" />
  </el-card>
</template>

<style scoped>
.toolbar {
  margin-bottom: 12px;
}
</style>
