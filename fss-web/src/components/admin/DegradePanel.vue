<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'
import { ApiError } from '@/api/http'
import type { DegradeStatus } from '@/api/types'
import { codeText, DEGRADE_LEVELS } from '@/utils/enums'

const status = ref<DegradeStatus | null>(null)
const level = ref(0)
const reason = ref('')
const busy = ref(false)
/** 操作者动过滑块后就不再被自动刷新盖掉，否则选到一半会被拽回去 */
const touched = ref(false)

async function load() {
  try {
    status.value = await adminApi.getDegrade()
    if (!touched.value) level.value = status.value.level
  } catch {
    status.value = null
  }
}

async function apply() {
  if (!reason.value.trim()) {
    return ElMessage.warning('必须填原因——降级是会被复盘的操作，原因要进审计日志')
  }
  busy.value = true
  try {
    status.value = await adminApi.setDegrade(level.value, reason.value.trim())
    ElMessage.success(`已设置人工降级等级 L${level.value}`)
    reason.value = ''
    touched.value = false
  } catch (e) {
    const err = e instanceof ApiError ? e : null
    ElMessage.error(err ? codeText(err.code, err.message) : '设置失败')
  } finally {
    busy.value = false
  }
}

let timer = 0
onMounted(() => {
  void load()
  timer = window.setInterval(load, 5000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <el-card shadow="never">
    <p class="section-title">降级开关</p>

    <el-descriptions v-if="status" :column="3" border size="small">
      <el-descriptions-item label="生效等级">
        <el-tag :type="status.level === 0 ? 'success' : status.level >= 3 ? 'danger' : 'warning'" size="small">
          L{{ status.level }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="自动等级">L{{ status.autoLevel }}</el-descriptions-item>
      <el-descriptions-item label="下发轮询间隔">
        <span class="mono">{{ status.pollIntervalMs }}ms</span>
      </el-descriptions-item>
      <el-descriptions-item label="秒杀">{{ status.seckillEnabled ? '开' : '关' }}</el-descriptions-item>
      <el-descriptions-item label="精确库存">{{ status.showExactStock ? '下发' : '只给档位' }}</el-descriptions-item>
      <el-descriptions-item label="活动浏览">{{ status.browseEnabled ? '开' : '关' }}</el-descriptions-item>
    </el-descriptions>
    <el-skeleton v-else :rows="2" />

    <el-radio-group v-model="level" style="margin-top: 14px" @change="touched = true">
      <el-radio-button v-for="l in DEGRADE_LEVELS" :key="l.level" :value="l.level">
        {{ l.label }}
      </el-radio-button>
    </el-radio-group>

    <p class="hint" style="margin-top: 6px">
      {{ DEGRADE_LEVELS.find((l) => l.level === level)?.effect }}
    </p>

    <div class="row" style="margin-top: 10px">
      <el-input v-model="reason" placeholder="降级原因（必填，进审计日志）" style="width: 320px" />
      <el-button type="danger" :loading="busy" @click="apply">设置人工等级</el-button>
    </div>

    <el-alert type="info" :closable="false" style="margin-top: 12px">
      写的是人工那一半（<code class="mono">degrade:level</code>，无 TTL）。
      生效等级取人工与自动的最大值，所以自动监控恢复到 0 也盖不过这里的决定——
      解除必须显式再设一次 L0。自动那一半有 TTL，写入者崩溃时会自己过期。
    </el-alert>

    <p class="hint">
      演示建议：设到 L1 后回秒杀大厅看库存变成档位；设到 L2 看轮询间隔从 300ms 变 2000ms；
      设到 L3 再抢会被直接拒。看完记得设回 L0。
    </p>
  </el-card>
</template>
