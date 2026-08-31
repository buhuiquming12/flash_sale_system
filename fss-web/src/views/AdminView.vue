<script setup lang="ts">
import { ref } from 'vue'
import ActivityOps from '@/components/admin/ActivityOps.vue'
import CatalogForms from '@/components/admin/CatalogForms.vue'
import DegradePanel from '@/components/admin/DegradePanel.vue'
import QuickActivity from '@/components/admin/QuickActivity.vue'

const tab = ref('quick')
const ops = ref<InstanceType<typeof ActivityOps> | null>(null)

/** 一键造完直接跳到运维页，并刷新那边的列表，省一次手动刷新 */
function onCreated() {
  tab.value = 'ops'
  void ops.value?.loadList()
}
</script>

<template>
  <el-card shadow="never" body-style="padding-top: 8px">
    <el-tabs v-model="tab">
      <el-tab-pane label="一键造活动" name="quick">
        <QuickActivity @created="onCreated" />
      </el-tab-pane>

      <el-tab-pane label="活动运维" name="ops">
        <ActivityOps ref="ops" />
      </el-tab-pane>

      <el-tab-pane label="分步创建" name="catalog">
        <CatalogForms />
      </el-tab-pane>

      <el-tab-pane label="降级开关" name="degrade">
        <DegradePanel />
      </el-tab-pane>
    </el-tabs>

    <p class="hint">
      管理接口的鉴权在后端 <code class="mono">AdminAuthFilter</code> 上按 <code
        class="mono">/api/admin/</code>
      前缀统一处理，前端这层只是不让你点进必然 403 的页面。所有管理操作都会写
      <code class="mono">t_admin_log</code>。
    </p>
  </el-card>
</template>
