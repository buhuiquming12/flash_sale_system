import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { userApi } from '@/api'
import { clearSession, getStoredUser, getToken, setSession } from '@/api/session'

export const useAuthStore = defineStore('auth', () => {
  const stored = getStoredUser()
  const token = ref<string | null>(getToken())
  const userId = ref<number | null>(stored?.userId ?? null)
  const username = ref<string>(stored?.username ?? '')
  const role = ref<number>(stored?.role ?? 0)

  const loggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => role.value === 1)

  async function login(name: string, password: string) {
    const vo = await userApi.login(name, password)
    token.value = vo.token
    userId.value = vo.userId
    username.value = vo.username
    role.value = vo.role
    setSession(vo.token, { userId: vo.userId, username: vo.username, role: vo.role })
    return vo
  }

  async function register(name: string, password: string, nickname?: string) {
    await userApi.register(name, password, nickname)
    return login(name, password)
  }

  async function logout() {
    try {
      await userApi.logout()
    } catch {
      // token 已过期时登出接口会失败，但结果一样：这个会话没了。
      // 不把失败抛给调用方，否则用户会卡在"登不出去"的状态里
    }
    reset()
  }

  /** 401 时也走这里：本地状态必须和服务端一致，不能留着一个已失效的 token */
  function reset() {
    token.value = null
    userId.value = null
    username.value = ''
    role.value = 0
    clearSession()
  }

  return { token, userId, username, role, loggedIn, isAdmin, login, register, logout, reset }
})
