<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'
import { ApiError } from '@/api/http'
import { codeText } from '@/utils/enums'
import { toLocalIso } from '@/utils/format'

const emit = defineEmits<{ created: [activityId: number] }>()

const form = reactive({
  title: 'iPhone 16 Pro',
  spec: '黑色/256G',
  price: 7999,
  skuStock: 1000,
  seckillPrice: 4999,
  totalStock: 100,
  startAfterMinutes: 1,
  durationHours: 2,
})

const running = ref(false)
const logs = ref<{ text: string; ok: boolean }[]>([])

function say(text: string, ok = true) {
  logs.value.push({ text, ok })
}

/**
 * 造一个能立刻演示的活动：商品 → SKU → 活动 → 发布 → 预热。
 *
 * 五步必须按这个顺序，而且预热不能省：没预热时 Lua 读不到
 * `seckill:goods`，所有请求都会返回 2005「活动未预热」。
 * 定时任务只在开抢前 5 分钟自动预热，手动造的活动等不到那一刻。
 */
async function run() {
  running.value = true
  logs.value = []
  try {
    const { productId } = await adminApi.createProduct({
      title: form.title,
      subTitle: '秒杀专场特价',
      mainImage: `https://placehold.co/300x300?text=${encodeURIComponent(form.title)}`,
      status: 1,
    })
    say(`商品已创建 productId=${productId}`)

    const { skuId } = await adminApi.createSku({
      productId,
      spec: form.spec,
      price: form.price,
      stock: form.skuStock,
      status: 1,
    })
    say(`SKU 已创建 skuId=${skuId}（日常价 ${form.price}，可用库存 ${form.skuStock}）`)

    const start = new Date(Date.now() + form.startAfterMinutes * 60_000)
    const end = new Date(start.getTime() + form.durationHours * 3600_000)
    const { activityId } = await adminApi.createActivity({
      name: `${form.title} 秒杀专场`,
      startTime: toLocalIso(start),
      endTime: toLocalIso(end),
      goods: [
        { skuId, seckillPrice: form.seckillPrice, totalStock: form.totalStock, limitPerUser: 1 },
      ],
    })
    say(`活动已创建 activityId=${activityId}（${toLocalIso(start)} 开抢）`)

    await adminApi.publish(activityId)
    say('已发布：通过了时间、库存、限购、SKU 与时间段冲突的全部校验')

    await adminApi.warmup(activityId)
    say('已预热：Redis 里有 seckill:goods 与 seckill:stock，现在可以抢了')

    ElMessage.success(`activityId=${activityId} 就绪，${form.startAfterMinutes} 分钟后开抢`)
    emit('created', activityId)
  } catch (e) {
    const err = e instanceof ApiError ? e : null
    const msg = err ? `${codeText(err.code, err.message)}（code=${err.code}）` : '未知错误'
    say(`失败：${msg}`, false)
    ElMessage.error(msg)
  } finally {
    running.value = false
  }
}
</script>

<template>
  <el-card shadow="never">
    <p class="section-title">一键造活动</p>
    <p class="hint">
      按「商品 → SKU → 活动 → 发布 → 预热」五步走一遍。发布校验要求开始时间晚于当前时间，
      所以开抢延迟不能填 0。
    </p>

    <el-form :inline="true" label-position="top" style="margin-top: 8px">
      <el-form-item label="商品名">
        <el-input v-model="form.title" style="width: 170px" />
      </el-form-item>
      <el-form-item label="规格">
        <el-input v-model="form.spec" style="width: 130px" />
      </el-form-item>
      <el-form-item label="日常价">
        <el-input-number v-model="form.price" :min="0.01" :step="100" style="width: 130px" />
      </el-form-item>
      <el-form-item label="SKU 库存">
        <el-input-number v-model="form.skuStock" :min="1" :step="100" style="width: 120px" />
      </el-form-item>
      <el-form-item label="秒杀价">
        <el-input-number v-model="form.seckillPrice" :min="0.01" :step="100" style="width: 130px" />
      </el-form-item>
      <el-form-item label="秒杀库存">
        <el-input-number v-model="form.totalStock" :min="1" :step="10" style="width: 120px" />
      </el-form-item>
      <el-form-item label="几分钟后开抢">
        <el-input-number v-model="form.startAfterMinutes" :min="1" :max="120" style="width: 110px" />
      </el-form-item>
      <el-form-item label="持续几小时">
        <el-input-number v-model="form.durationHours" :min="1" :max="48" style="width: 110px" />
      </el-form-item>
      <el-form-item label=" ">
        <el-button type="danger" :loading="running" @click="run">开始创建</el-button>
      </el-form-item>
    </el-form>

    <div v-if="logs.length" class="log">
      <div v-for="(l, i) in logs" :key="i" class="mono" :class="{ bad: !l.ok }">
        {{ l.ok ? '✓' : '✗' }} {{ l.text }}
      </div>
    </div>

    <el-alert type="info" :closable="false" style="margin-top: 12px">
      秒杀价不能高于日常价、秒杀库存不能超过 SKU 可用库存、同一 SKU 不能在重叠时间段
      参加两个活动——这三条都是发布时才校验的，填错了会在「已发布」那一步失败。
    </el-alert>
  </el-card>
</template>

<style scoped>
.log {
  margin-top: 12px;
  background: #1f2328;
  color: #d5d8dd;
  border-radius: 6px;
  padding: 10px 12px;
  line-height: 1.8;
}

.log .bad {
  color: #ff8a8a;
}
</style>
