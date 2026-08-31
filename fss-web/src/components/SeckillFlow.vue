<script setup lang="ts">
import type { FlowStep, PollAttempt, StepStatus } from '@/composables/useSeckill'
import { SECKILL_STATUS } from '@/utils/enums'

const props = defineProps<{
  steps: FlowStep[]
  attempts: PollAttempt[]
  requestNo: string
}>()

const ICON: Record<StepStatus, string> = {
  pending: '○',
  running: '◌',
  done: '✓',
  fail: '✗',
  skip: '—',
}

function cls(s: StepStatus) {
  return `step step--${s}`
}
</script>

<template>
  <div>
    <p class="section-title">抢购链路</p>
    <ol class="flow">
      <li v-for="s in props.steps" :key="s.key" :class="cls(s.status)">
        <span class="icon">{{ ICON[s.status] }}</span>
        <div class="body">
          <div class="head">
            <strong>{{ s.label }}</strong>
            <span v-if="s.durationMs !== undefined" class="mono muted">{{ s.durationMs }}ms</span>
          </div>
          <div class="hint">{{ s.hint }}</div>
          <div v-if="s.detail" class="mono detail">{{ s.detail }}</div>
        </div>
      </li>
    </ol>

    <template v-if="props.attempts.length">
      <p class="section-title" style="margin-top: 14px">
        轮询明细
        <span class="muted" style="font-weight: 400">
          requestNo = <code class="mono">{{ props.requestNo }}</code>
        </span>
      </p>
      <el-table :data="props.attempts" size="small" border>
        <el-table-column prop="n" label="#" width="48" />
        <el-table-column label="本次等待" width="100">
          <template #default="{ row }">
            <span class="mono">{{ row.waitedMs }}ms</span>
          </template>
        </el-table-column>
        <el-table-column label="结论">
          <template #default="{ row }">
            <el-tag v-if="row.status >= 0" size="small" :type="SECKILL_STATUS[row.status]?.type ?? 'info'">
              {{ SECKILL_STATUS[row.status]?.text ?? row.statusDesc }}
            </el-tag>
            <el-tag v-else size="small" type="warning">{{ row.statusDesc }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">
        间隔是退避的：第一档用服务端下发的 <code class="mono">pollAfterMs</code>，
        之后 500 / 800 / 1300 / 2000ms。降级到 L2 时服务端会把第一档从 300 拉到 2000，
        前端不用发版就跟着变。
      </p>
    </template>
  </div>
</template>

<style scoped>
.flow {
  list-style: none;
  margin: 0;
  padding: 0;
}

.step {
  display: flex;
  gap: 10px;
  padding: 8px 0;
  border-left: 2px solid var(--fss-line);
  padding-left: 12px;
  margin-left: 6px;
}

.step .icon {
  width: 18px;
  text-align: center;
  font-weight: 700;
  color: var(--fss-muted);
}

.step--running {
  border-left-color: #e6a23c;
}

.step--running .icon {
  color: #e6a23c;
}

.step--done {
  border-left-color: #67c23a;
}

.step--done .icon {
  color: #67c23a;
}

.step--fail {
  border-left-color: var(--fss-red);
}

.step--fail .icon {
  color: var(--fss-red);
}

.body {
  flex: 1;
  min-width: 0;
}

.head {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.detail {
  margin-top: 4px;
  background: #f7f8fa;
  border-radius: 4px;
  padding: 4px 8px;
  word-break: break-all;
}
</style>
