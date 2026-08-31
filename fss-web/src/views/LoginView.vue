<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api/http'
import { codeText } from '@/utils/enums'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 与 DevDataInitializer 里的演示账号一致，密码统一 Passw0rd1 */
const DEMO_ACCOUNTS = [
  { username: 'admin', label: 'admin（管理员）' },
  { username: 'demo1', label: 'demo1' },
  { username: 'demo2', label: 'demo2' },
  { username: 'demo3', label: 'demo3' },
]

const form = reactive({ username: 'demo1', password: 'Passw0rd1', nickname: '' })
const loading = ref(false)
const mode = ref<'login' | 'register'>('login')

function fill(username: string) {
  form.username = username
  form.password = 'Passw0rd1'
}

async function submit() {
  loading.value = true
  try {
    if (mode.value === 'login') {
      await auth.login(form.username, form.password)
    } else {
      await auth.register(form.username, form.password, form.nickname || undefined)
    }
    ElMessage.success(`欢迎，${auth.username}`)
    const redirect = route.query.redirect
    void router.replace(typeof redirect === 'string' ? redirect : { name: 'seckill' })
  } catch (e) {
    const err = e instanceof ApiError ? e : null
    // 1001 时后端会把具体哪个字段不合法拼进 message，比我们的兜底文案有用
    ElMessage.error(err ? (err.code === 1001 ? err.message : codeText(err.code, err.message)) : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <el-card shadow="never">
      <h2 class="title">{{ mode === 'login' ? '登录演示环境' : '注册新账号' }}</h2>

      <el-form label-width="72px" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="3~32 位字母数字下划线" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="8~32 位，含大小写与数字"
            @keyup.enter="submit" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="昵称">
          <el-input v-model="form.nickname" placeholder="可选" />
        </el-form-item>
        <el-form-item>
          <el-button type="danger" :loading="loading" @click="submit">
            {{ mode === 'login' ? '登录' : '注册并登录' }}
          </el-button>
          <el-button text @click="mode = mode === 'login' ? 'register' : 'login'">
            {{ mode === 'login' ? '没有账号？注册' : '已有账号？登录' }}
          </el-button>
        </el-form-item>
      </el-form>

      <el-divider>演示账号（dev profile 自动创建）</el-divider>
      <div class="row">
        <el-button v-for="a in DEMO_ACCOUNTS" :key="a.username" size="small" @click="fill(a.username)">
          {{ a.label }}
        </el-button>
      </div>
      <p class="hint">
        密码统一 <code class="mono">Passw0rd1</code>。管理控制台需要 admin，
        「一人一单」的验证需要多个普通账号轮换登录。
      </p>
      <p class="hint">
        登录返回的 JWT 里只有 userId / role / exp / jti；jti 写在 Redis 白名单里，
        所以登出是服务端真的撤销会话，不是前端丢掉 token。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.wrap {
  max-width: 480px;
  margin: 48px auto 0;
}

.title {
  margin: 0 0 20px;
  font-size: 18px;
}
</style>
