<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'
import type { DegradeStatus } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useTraceStore } from '@/stores/trace'
import { DEGRADE_LEVELS } from '@/utils/enums'
import { formatClock } from '@/utils/format'

const trace = useTraceStore()
const auth = useAuthStore()

const degrade = ref<DegradeStatus | null>(null)
const degradeError = ref('')

/** 后端把这些外部看板都跑在固定端口上，见根 README 的快速开始 */
const LINKS = [
  { label: 'Swagger UI', href: 'http://localhost:8080/swagger-ui.html', desc: '全部接口与请求体' },
  { label: 'Grafana', href: 'http://localhost:3000', desc: '秒杀系统总览看板（匿名可看）' },
  { label: 'Prometheus', href: 'http://localhost:9090/alerts', desc: '16 条告警规则的实时状态' },
  { label: 'actuator/prometheus', href: 'http://localhost:8080/actuator/prometheus', desc: '原始指标文本' },
  { label: 'actuator/health', href: 'http://localhost:8080/actuator/health', desc: '依赖健康状态' },
]

async function loadDegrade() {
  if (!auth.isAdmin) return
  try {
    degrade.value = await adminApi.getDegrade()
    degradeError.value = ''
  } catch {
    degradeError.value = '读不到降级状态（需要 admin，且 job profile 才有自动降级）'
  }
}

function copyTrace(traceId: string) {
  if (!traceId) return
  void navigator.clipboard?.writeText(traceId).then(
    () => ElMessage.success(`已复制 traceId ${traceId}`),
    () => ElMessage.warning('浏览器拒绝了剪贴板访问'),
  )
}

function levelText(level: number) {
  return DEGRADE_LEVELS.find((l) => l.level === level)?.label ?? `L${level}`
}

let timer = 0
onMounted(() => {
  void loadDegrade()
  timer = window.setInterval(loadDegrade, 5000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div class="stack">
    <el-card shadow="never">
      <p class="section-title">本次会话的请求统计</p>
      <div class="stats">
        <el-statistic title="请求数" :value="trace.total" />
        <el-statistic title="失败数" :value="trace.failed" />
        <el-statistic title="被限流(1004)" :value="trace.rateLimited" />
        <el-statistic title="平均 RT" :value="trace.avgDuration" suffix="ms" />
        <el-statistic title="最慢 RT" :value="trace.slowest" suffix="ms" />
      </div>
      <p class="hint">
        这里统计的是浏览器侧的耗时，含 Vite 代理与网络往返，比服务端 RT 略大。
        服务端真实分位数看 Grafana。
      </p>
    </el-card>

    <el-card v-if="auth.isAdmin" shadow="never">
      <p class="section-title">降级状态（5s 自动刷新）</p>
      <el-alert v-if="degradeError" type="warning" :closable="false" :title="degradeError" />
      <template v-else-if="degrade">
        <div class="row">
          <el-tag :type="degrade.level === 0 ? 'success' : degrade.level >= 3 ? 'danger' : 'warning'" size="large">
            生效等级 {{ levelText(degrade.level) }}
          </el-tag>
          <span class="hint">自动监控算出的等级 {{ levelText(degrade.autoLevel) }}</span>
        </div>
        <el-descriptions :column="4" border size="small" style="margin-top: 12px">
          <el-descriptions-item label="秒杀开关">
            {{ degrade.seckillEnabled ? '开' : '关' }}
          </el-descriptions-item>
          <el-descriptions-item label="精确库存">
            {{ degrade.showExactStock ? '下发' : '只给档位' }}
          </el-descriptions-item>
          <el-descriptions-item label="活动浏览">
            {{ degrade.browseEnabled ? '开' : '关' }}
          </el-descriptions-item>
          <el-descriptions-item label="轮询间隔">
            <span class="mono">{{ degrade.pollIntervalMs }}ms</span>
          </el-descriptions-item>
        </el-descriptions>
        <p class="hint">
          生效等级 = max(人工, 自动)。自动监控恢复到 0 也盖不过人工的决定，
          解除必须到管理控制台显式设回 0。
        </p>
      </template>
      <el-skeleton v-else :rows="2" />
    </el-card>

    <el-card shadow="never">
      <div class="row" style="justify-content: space-between">
        <p class="section-title" style="margin: 0">请求时间线（最近 {{ trace.records.length }} 条）</p>
        <el-button size="small" text :disabled="!trace.records.length" @click="trace.clear()">清空</el-button>
      </div>
      <el-table :data="trace.records" size="small" border max-height="460" style="margin-top: 10px">
        <el-table-column label="时刻" width="110">
          <template #default="{ row }"><span class="mono">{{ formatClock(row.ts) }}</span></template>
        </el-table-column>
        <el-table-column label="阶段" width="76">
          <template #default="{ row }">
            <el-tag v-if="row.tag" size="small" type="info">{{ row.tag }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="请求" min-width="280">
          <template #default="{ row }">
            <span class="mono">{{ row.method }} {{ row.url }}</span>
          </template>
        </el-table-column>
        <el-table-column label="HTTP" width="70">
          <template #default="{ row }">
            <span class="mono" :class="{ bad: row.httpStatus !== 200 }">{{ row.httpStatus || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="code" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.ok ? 'success' : 'danger'">{{ row.code }}</el-tag>
            <span v-if="!row.ok" class="hint"> {{ row.message }}</span>
          </template>
        </el-table-column>
        <el-table-column label="RT" width="80">
          <template #default="{ row }"><span class="mono">{{ row.durationMs }}ms</span></template>
        </el-table-column>
        <el-table-column label="traceId" width="140">
          <template #default="{ row }">
            <el-link v-if="row.traceId" type="primary" class="mono" @click="copyTrace(row.traceId)">
              {{ row.traceId }}
            </el-link>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">
        traceId 每个响应都带，点一下复制——拿它去日志里 grep 就能捞出这一次请求在
        web / consumer / job 三个角色里的全部日志行。
      </p>
    </el-card>

    <el-card shadow="never">
      <p class="section-title">外部看板</p>
      <div class="row">
        <el-link v-for="l in LINKS" :key="l.href" :href="l.href" target="_blank" type="primary">
          {{ l.label }}
        </el-link>
      </div>
      <ul class="hint bullets">
        <li v-for="l in LINKS" :key="l.href">{{ l.label }} —— {{ l.desc }}</li>
      </ul>
    </el-card>
  </div>
</template>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}

.bad {
  color: var(--fss-red);
  font-weight: 700;
}

.bullets {
  margin: 8px 0 0;
  padding-left: 18px;
}
</style>
