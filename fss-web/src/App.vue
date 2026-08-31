<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useTraceStore } from '@/stores/trace'

const auth = useAuthStore()
const trace = useTraceStore()
const route = useRoute()
const router = useRouter()

const active = computed(() => route.name as string)

async function doLogout() {
  await auth.logout()
  ElMessage.success('已登出，服务端已撤销该会话')
  void router.replace({ name: 'login' })
}
</script>

<template>
  <el-config-provider>
    <div class="shell">
      <header class="topbar">
        <div class="brand">秒杀系统<span>演示控制台</span></div>

        <el-menu v-if="auth.loggedIn" :default-active="active" mode="horizontal" router :ellipsis="false"
          class="nav">
          <el-menu-item index="seckill" :route="{ name: 'seckill' }">秒杀大厅</el-menu-item>
          <el-menu-item index="orders" :route="{ name: 'orders' }">我的订单</el-menu-item>
          <el-menu-item index="observe" :route="{ name: 'observe' }">链路观测</el-menu-item>
          <el-menu-item v-if="auth.isAdmin" index="admin" :route="{ name: 'admin' }">
            管理控制台
          </el-menu-item>
        </el-menu>

        <div class="spacer" />

        <template v-if="auth.loggedIn">
          <el-tag size="small" type="info" class="mono">请求 {{ trace.total }} · 失败 {{ trace.failed }}</el-tag>
          <el-tag size="small" :type="auth.isAdmin ? 'danger' : 'primary'">
            {{ auth.username }}（userId={{ auth.userId }}{{ auth.isAdmin ? '，管理员' : '' }}）
          </el-tag>
          <el-button size="small" text @click="doLogout">登出</el-button>
        </template>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>
  </el-config-provider>
</template>

<style scoped>
.shell {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  height: 56px;
  background: #fff;
  border-bottom: 1px solid var(--fss-line);
  position: sticky;
  top: 0;
  z-index: 10;
}

.brand {
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
}

.brand span {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 400;
  color: var(--fss-muted);
}

.nav {
  border-bottom: none;
}

.spacer {
  flex: 1;
}

.content {
  flex: 1;
  max-width: 1240px;
  width: 100%;
  margin: 0 auto;
  padding: 18px 16px 40px;
  box-sizing: border-box;
}
</style>
