<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import { activityApi } from '@/api'
import { ApiError } from '@/api/http'
import type { ActivityDetailVO } from '@/api/types'
import ActivityCard from '@/components/ActivityCard.vue'
import SeckillFlow from '@/components/SeckillFlow.vue'
import { useSeckill } from '@/composables/useSeckill'
import { codeText } from '@/utils/enums'
import { parseTime } from '@/utils/format'

const { steps, attempts, running, outcome, requestNo, run, reset } = useSeckill()

const activities = ref<ActivityDetailVO[]>([])
const loading = ref(false)
const busySkuId = ref<number | null>(null)
const skewMs = ref(0)
const autoRefresh = ref(true)
const lastLoadedAt = ref(0)

/** 只有进行中和待开始的活动值得盯着刷库存 */
const liveCount = computed(() => activities.value.filter((a) => a.status === 1 || a.status === 2).length)

async function load() {
  loading.value = true
  try {
    const page = await activityApi.list(1, 20)
    const details = await Promise.all(page.list.map((a) => activityApi.detail(a.activityId)))
    activities.value = details
    const server = parseTime(details[0]?.serverTime)
    if (server) skewMs.value = server.getTime() - Date.now()
    lastLoadedAt.value = Date.now()
  } catch (e) {
    const err = e instanceof ApiError ? e : null
    ElMessage.error(err ? codeText(err.code, err.message) : '活动加载失败')
  } finally {
    loading.value = false
  }
}

async function buy(activityId: number, skuId: number, useToken: boolean) {
  busySkuId.value = skuId
  try {
    const r = await run(activityId, skuId, useToken)
    ElNotification({
      title: r.ok ? '秒杀成功' : '没抢到',
      message: r.message,
      type: r.ok ? 'success' : 'warning',
      duration: r.ok ? 6000 : 4000,
    })
  } finally {
    busySkuId.value = null
    await load()
  }
}

// 抢购过程中不刷新：轮询已经在打结果接口，再叠一份详情请求容易自己把自己限流
let timer = 0
onMounted(() => {
  void load()
  timer = window.setInterval(() => {
    if (autoRefresh.value && !running.value && liveCount.value > 0) void load()
  }, 3000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div class="grid">
    <div>
      <div class="row toolbar">
        <el-button :loading="loading" @click="load">刷新活动</el-button>
        <el-switch v-model="autoRefresh" active-text="3s 自动刷新库存" />
        <span class="hint">
          共 {{ activities.length }} 个活动，其中 {{ liveCount }} 个待开始/进行中
        </span>
      </div>

      <el-empty v-if="!loading && !activities.length" description="还没有活动。到管理控制台用「一键造活动」建一个" />

      <ActivityCard v-for="a in activities" :key="a.activityId" :activity="a" :skew-ms="skewMs"
        :busy-sku-id="busySkuId" @buy="(skuId) => buy(a.activityId, skuId, true)"
        @direct="(skuId) => buy(a.activityId, skuId, false)" />
    </div>

    <div class="side">
      <el-card shadow="never">
        <SeckillFlow :steps="steps" :attempts="attempts" :request-no="requestNo" />

        <el-alert v-if="outcome" :type="outcome.ok ? 'success' : 'warning'" :closable="false" show-icon
          style="margin-top: 12px">
          <template #title>{{ outcome.ok ? '秒杀成功' : '本次没抢到' }}</template>
          {{ outcome.message }}
        </el-alert>

        <div class="row" style="margin-top: 12px">
          <el-button size="small" text :disabled="running" @click="reset">清空链路</el-button>
        </div>

        <el-divider />
        <p class="section-title">这个面板在讲什么</p>
        <ul class="hint bullets">
          <li>秒杀接口只做 Lua 判扣 + 投一条消息就返回「排队中」，订单是消费端异步建的。</li>
          <li>所以成功与失败都来自轮询，而轮询间隔由服务端下发，降级时会被拉长。</li>
          <li>令牌是一次性的，只抬高脚本成本；真正的防线是限流与一人一单。</li>
          <li>「不用令牌直接提交」走后端保留的压测入口，生产应在网关屏蔽。</li>
        </ul>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 420px;
  gap: 16px;
  align-items: start;
}

@media (max-width: 1080px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.toolbar {
  margin-bottom: 12px;
}

.side {
  position: sticky;
  top: 74px;
}

.bullets {
  margin: 0;
  padding-left: 18px;
}

.bullets li {
  margin-bottom: 4px;
}
</style>
