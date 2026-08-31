<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { activityApi, adminApi } from '@/api'
import { ApiError } from '@/api/http'
import type { ActivityListItemVO } from '@/api/types'
import { ACTIVITY_STATUS, codeText } from '@/utils/enums'
import { formatTime } from '@/utils/format'

const activities = ref<ActivityListItemVO[]>([])
const selected = ref<number | undefined>(undefined)
const busy = ref('')

const stock = reactive({ goodsId: 1, delta: 100, reason: '追加库存' })

async function loadList() {
  try {
    const page = await activityApi.list(1, 50)
    activities.value = page.list
    if (selected.value === undefined) selected.value = page.list[0]?.activityId
  } catch {
    ElMessage.error('活动列表加载失败')
  }
}

async function act(kind: 'publish' | 'close' | 'warmup') {
  if (!selected.value) return ElMessage.warning('先选一个活动')
  if (kind === 'close') {
    try {
      await ElMessageBox.confirm(
        '关闭活动会把状态改成 CLOSED 并同步 Redis，进行中的活动会立刻停止售卖。确认？',
        '关闭活动',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  busy.value = kind
  try {
    await adminApi[kind](selected.value)
    ElMessage.success(
      { publish: '已发布', close: '已关闭', warmup: '已预热（幂等，不会重置已扣减的库存）' }[kind],
    )
    await loadList()
  } catch (e) {
    const err = e instanceof ApiError ? e : null
    ElMessage.error(err ? `${codeText(err.code, err.message)}（code=${err.code}）` : '操作失败')
  } finally {
    busy.value = ''
  }
}

async function adjust() {
  if (!stock.reason.trim()) return ElMessage.warning('必须填调整原因，它会进审计日志')
  busy.value = 'stock'
  try {
    await adminApi.adjustStock(stock.goodsId, stock.delta, stock.reason)
    ElMessage.success(`goodsId=${stock.goodsId} 库存调整 ${stock.delta > 0 ? '+' : ''}${stock.delta}`)
  } catch (e) {
    const err = e instanceof ApiError ? e : null
    ElMessage.error(err ? `${codeText(err.code, err.message)}（code=${err.code}）` : '调整失败')
  } finally {
    busy.value = ''
  }
}

onMounted(loadList)
defineExpose({ loadList })
</script>

<template>
  <div class="stack">
    <el-card shadow="never">
      <p class="section-title">活动运维</p>
      <div class="row">
        <el-select v-model="selected" placeholder="选择活动" style="width: 380px" filterable>
          <el-option v-for="a in activities" :key="a.activityId" :value="a.activityId"
            :label="`#${a.activityId} ${a.name}（${a.statusDesc}）`" />
        </el-select>
        <el-button :loading="busy === 'publish'" @click="act('publish')">发布</el-button>
        <el-button :loading="busy === 'warmup'" @click="act('warmup')">手动预热</el-button>
        <el-button type="danger" plain :loading="busy === 'close'" @click="act('close')">关闭</el-button>
        <el-button text @click="loadList">刷新</el-button>
      </div>

      <el-table :data="activities" size="small" border style="margin-top: 12px" max-height="260">
        <el-table-column label="ID" width="60" prop="activityId" />
        <el-table-column label="名称" min-width="180" prop="name" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="ACTIVITY_STATUS[row.status]?.type ?? 'info'">
              {{ row.statusDesc || ACTIVITY_STATUS[row.status]?.text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始" width="160">
          <template #default="{ row }"><span class="mono">{{ formatTime(row.startTime) }}</span></template>
        </el-table-column>
        <el-table-column label="结束" width="160">
          <template #default="{ row }"><span class="mono">{{ formatTime(row.endTime) }}</span></template>
        </el-table-column>
        <el-table-column label="商品数" width="80" prop="goodsCount" />
      </el-table>

      <p class="hint">
        手动预热用于两种情况：自动预热失败后人工重试，以及 Redis 数据意外丢失后补数据
        （故障用例 F2）。可以放心重复点。
      </p>
    </el-card>

    <el-card shadow="never">
      <p class="section-title">库存调整（活动进行中改库存的唯一入口）</p>
      <div class="row">
        <el-input-number v-model="stock.goodsId" :min="1" style="width: 130px" />
        <span class="hint">秒杀商品 ID</span>
        <el-input-number v-model="stock.delta" :step="10" style="width: 140px" />
        <span class="hint">增量，可为负</span>
        <el-input v-model="stock.reason" placeholder="调整原因（必填）" style="width: 220px" />
        <el-button type="primary" :loading="busy === 'stock'" @click="adjust">提交调整</el-button>
      </div>
      <el-alert type="warning" :closable="false" style="margin-top: 12px">
        这里的 ID 是 <code class="mono">t_seckill_goods.id</code>，不是 skuId。
        活动详情接口没有下发它，所以只能手填——演示数据里第一个秒杀商品通常是 1。
        调整会在同一事务里改 <code class="mono">total_stock</code> 与
        <code class="mono">available_stock</code> 并写库存流水，只改一个会让对账立刻报差异。
      </el-alert>
    </el-card>
  </div>
</template>
