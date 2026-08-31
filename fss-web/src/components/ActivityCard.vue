<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import type { ActivityDetailVO, GoodsItemVO } from '@/api/types'
import { ACTIVITY_STATUS } from '@/utils/enums'
import { formatCountdown, formatMoney, formatTime, parseTime } from '@/utils/format'

const props = defineProps<{
  activity: ActivityDetailVO
  /** 服务端时间 - 本地时间，由调用方用 serverTime 算好 */
  skewMs: number
  /** 正在抢购的 skuId，用于禁用按钮 */
  busySkuId: number | null
}>()

const emit = defineEmits<{ buy: [skuId: number]; direct: [skuId: number] }>()

// Date.now() 不是响应式的，倒计时必须有一个每秒变化的 ref 驱动重算
const nowTick = ref(Date.now())
const timer = window.setInterval(() => (nowTick.value = Date.now()), 1000)
onUnmounted(() => window.clearInterval(timer))

/** 用服务端校准后的时间算倒计时。本地钟快 30s 就会在未开始时疯狂提交、全被拒 */
const correctedNow = computed(() => nowTick.value + props.skewMs)

const countdown = computed(() => {
  const start = parseTime(props.activity.startTime)
  const end = parseTime(props.activity.endTime)
  if (!start || !end) return null
  const now = correctedNow.value
  if (now < start.getTime()) {
    return { label: '距开抢', seconds: Math.ceil((start.getTime() - now) / 1000) }
  }
  if (now < end.getTime()) {
    return { label: '距结束', seconds: Math.ceil((end.getTime() - now) / 1000) }
  }
  return null
})

const meta = computed(() => ACTIVITY_STATUS[props.activity.status] ?? { text: '未知', type: 'info' as const })

function stockPercent(g: GoodsItemVO): number {
  if (!g.totalStock || g.remainStock === null) return 0
  return Math.max(0, Math.min(100, Math.round((g.remainStock / g.totalStock) * 100)))
}

function buyable(g: GoodsItemVO): boolean {
  return props.activity.status === 2 && !g.soldOut
}

function buttonText(g: GoodsItemVO): string {
  if (g.soldOut) return '已售罄'
  if (props.activity.status === 1) return '未开始'
  if (props.activity.status === 2) return '立即秒杀'
  return '不可购买'
}
</script>

<template>
  <el-card shadow="never" class="card">
    <template #header>
      <div class="row" style="justify-content: space-between">
        <div class="row">
          <strong>{{ activity.name }}</strong>
          <el-tag size="small" :type="meta.type">{{ activity.statusDesc || meta.text }}</el-tag>
          <span class="mono muted">activityId={{ activity.activityId }}</span>
        </div>
        <div v-if="countdown" class="row">
          <span class="muted">{{ countdown.label }}</span>
          <span class="mono countdown">{{ formatCountdown(countdown.seconds) }}</span>
        </div>
      </div>
    </template>

    <p class="hint">
      {{ formatTime(activity.startTime) }} ~ {{ formatTime(activity.endTime) }}
      ｜服务端时间 {{ formatTime(activity.serverTime) }}
      <template v-if="Math.abs(skewMs) > 3000">
        ｜<span style="color: var(--fss-red)">本地时钟偏差 {{ Math.round(skewMs / 1000) }}s，倒计时已按服务端校准</span>
      </template>
    </p>

    <div v-for="g in activity.goodsList" :key="g.skuId" class="goods">
      <div class="goods-main">
        <div class="row">
          <strong>{{ g.productTitle }}</strong>
          <el-tag size="small" type="info">{{ g.spec }}</el-tag>
          <span class="mono muted">skuId={{ g.skuId }}</span>
        </div>
        <div>
          <span class="price">¥{{ formatMoney(g.seckillPrice) }}</span>
          <span class="price-origin">¥{{ formatMoney(g.originPrice) }}</span>
          <span class="muted" style="margin-left: 10px">每人限 {{ g.limitPerUser }} 件</span>
        </div>

        <!-- 库存有两种形态：精确值，或降级到 L1 后只有档位。界面必须都能显示 -->
        <div v-if="g.remainStock !== null" class="stock">
          <el-progress :percentage="stockPercent(g)" :stroke-width="10"
            :color="stockPercent(g) <= 20 ? '#d9363e' : '#67c23a'" :show-text="false" />
          <span class="mono muted">剩余 {{ g.remainStock }} / {{ g.totalStock }}（Redis 近似值）</span>
        </div>
        <div v-else class="stock">
          <el-tag size="small" :type="g.stockLevel === 'SOLD_OUT' ? 'info' : g.stockLevel === 'LOW' ? 'danger' : 'success'">
            {{ g.stockLevel === 'SOLD_OUT' ? '已售罄' : g.stockLevel === 'LOW' ? '库存紧张' : '库存充足' }}
          </el-tag>
          <span class="hint">降级 L1：服务端只下发档位，不下发精确库存</span>
        </div>
      </div>

      <div class="goods-actions">
        <el-button type="danger" :disabled="!buyable(g) || busySkuId !== null" :loading="busySkuId === g.skuId"
          @click="emit('buy', g.skuId)">
          {{ buttonText(g) }}
        </el-button>
        <el-button size="small" text :disabled="!buyable(g) || busySkuId !== null" @click="emit('direct', g.skuId)">
          不用令牌直接提交
        </el-button>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.card {
  margin-bottom: 14px;
}

.countdown {
  font-size: 18px;
  font-weight: 700;
  color: var(--fss-red);
}

.goods {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-top: 1px dashed var(--fss-line);
}

.goods:first-of-type {
  border-top: none;
}

.goods-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.stock {
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 380px;
}

.stock :deep(.el-progress) {
  width: 160px;
}

.goods-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
  white-space: nowrap;
}
</style>
