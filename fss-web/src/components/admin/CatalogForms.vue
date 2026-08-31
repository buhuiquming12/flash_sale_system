<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'
import { ApiError } from '@/api/http'
import type { SeckillGoodsCmd, SkuVO } from '@/api/types'
import { codeText } from '@/utils/enums'
import { formatMoney, toLocalIso } from '@/utils/format'

/** el-date-picker 用 dayjs 的格式串，字面量 T 要用方括号转义 */
const DT_FORMAT = 'YYYY-MM-DD[T]HH:mm:ss'

const product = reactive({ title: '', subTitle: '', mainImage: '' })
const sku = reactive({ productId: 0, spec: '', price: 0, stock: 100 })
const skus = ref<SkuVO[]>([])
const activity = reactive({
  name: '',
  startTime: toLocalIso(new Date(Date.now() + 5 * 60_000)),
  endTime: toLocalIso(new Date(Date.now() + 2 * 3600_000)),
})
const goods = ref<SeckillGoodsCmd[]>([])
const busy = ref('')

async function guard(kind: string, fn: () => Promise<void>) {
  busy.value = kind
  try {
    await fn()
  } catch (e) {
    const err = e instanceof ApiError ? e : null
    ElMessage.error(err ? `${codeText(err.code, err.message)}（code=${err.code}）` : '操作失败')
  } finally {
    busy.value = ''
  }
}

const createProduct = () =>
  guard('product', async () => {
    const r = await adminApi.createProduct({ ...product, status: 1 })
    sku.productId = r.productId
    ElMessage.success(`productId=${r.productId}，已填入下一步`)
  })

const createSku = () =>
  guard('sku', async () => {
    const r = await adminApi.createSku({ ...sku, status: 1 })
    goods.value.push({ skuId: r.skuId, seckillPrice: sku.price, totalStock: 100, limitPerUser: 1 })
    ElMessage.success(`skuId=${r.skuId}，已加入活动商品列表`)
    await listSkus()
  })

const listSkus = () =>
  guard('list', async () => {
    skus.value = await adminApi.listSkus(sku.productId)
  })

const createActivity = () =>
  guard('activity', async () => {
    if (!goods.value.length) {
      ElMessage.warning('活动至少要有一个秒杀商品')
      return
    }
    const r = await adminApi.createActivity({ ...activity, goods: goods.value })
    ElMessage.success(`activityId=${r.activityId} 已创建（草稿），到「活动运维」发布并预热`)
  })
</script>

<template>
  <div class="stack">
    <el-card shadow="never">
      <p class="section-title">1 · 创建商品</p>
      <div class="row">
        <el-input v-model="product.title" placeholder="商品标题" style="width: 220px" />
        <el-input v-model="product.subTitle" placeholder="副标题（可选）" style="width: 200px" />
        <el-input v-model="product.mainImage" placeholder="主图 URL（可选）" style="width: 240px" />
        <el-button :loading="busy === 'product'" @click="createProduct">创建</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <p class="section-title">2 · 创建 SKU</p>
      <div class="row">
        <el-input-number v-model="sku.productId" :min="1" style="width: 130px" />
        <span class="hint">productId</span>
        <el-input v-model="sku.spec" placeholder="规格，如 黑色/256G" style="width: 180px" />
        <el-input-number v-model="sku.price" :min="0.01" :step="100" style="width: 140px" />
        <span class="hint">日常价</span>
        <el-input-number v-model="sku.stock" :min="0" :step="100" style="width: 130px" />
        <span class="hint">库存</span>
        <el-button :loading="busy === 'sku'" @click="createSku">创建</el-button>
        <el-button text :loading="busy === 'list'" @click="listSkus">查该商品的 SKU</el-button>
      </div>
      <el-table v-if="skus.length" :data="skus" size="small" border style="margin-top: 10px">
        <el-table-column prop="skuId" label="skuId" width="80" />
        <el-table-column prop="productTitle" label="商品" min-width="160" />
        <el-table-column prop="spec" label="规格" width="140" />
        <el-table-column label="日常价" width="100">
          <template #default="{ row }"><span class="mono">¥{{ formatMoney(row.price) }}</span></template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="90" />
      </el-table>
    </el-card>

    <el-card shadow="never">
      <p class="section-title">3 · 创建活动（草稿）</p>
      <div class="row">
        <el-input v-model="activity.name" placeholder="活动名称" style="width: 220px" />
        <el-date-picker v-model="activity.startTime" type="datetime" placeholder="开始时间"
          :value-format="DT_FORMAT" style="width: 200px" />
        <el-date-picker v-model="activity.endTime" type="datetime" placeholder="结束时间"
          :value-format="DT_FORMAT" style="width: 200px" />
        <el-button :loading="busy === 'activity'" @click="createActivity">创建活动</el-button>
      </div>

      <p class="section-title" style="margin-top: 14px">秒杀商品</p>
      <div v-for="(g, i) in goods" :key="i" class="row" style="margin-bottom: 6px">
        <el-input-number v-model="g.skuId" :min="1" style="width: 120px" />
        <span class="hint">skuId</span>
        <el-input-number v-model="g.seckillPrice" :min="0.01" :step="100" style="width: 140px" />
        <span class="hint">秒杀价</span>
        <el-input-number v-model="g.totalStock" :min="1" :step="10" style="width: 130px" />
        <span class="hint">秒杀库存</span>
        <el-tag size="small" type="info">限购 1（后端 @Max(1)）</el-tag>
        <el-button text type="danger" @click="goods.splice(i, 1)">移除</el-button>
      </div>
      <el-button size="small"
        @click="goods.push({ skuId: 0, seckillPrice: 1, totalStock: 100, limitPerUser: 1 })">
        添加一个秒杀商品
      </el-button>

      <p class="hint">
        创建出来是草稿（DRAFT），必须到「活动运维」发布才会进 READY，
        发布后还要预热才能真的抢——三步缺一步都表现成「活动未预热 2005」或活动不出现在列表里。
      </p>
    </el-card>
  </div>
</template>
