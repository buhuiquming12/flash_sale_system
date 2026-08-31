const TOKEN_KEY = 'fss.token'
const USER_KEY = 'fss.user'

export interface StoredUser {
  userId: number
  username: string
  role: number
}

/**
 * 会话的持久化载体。
 *
 * 刻意不放在 Pinia 里：请求拦截器需要在任何组件之外读 token，
 * 而 Pinia store 只有在 `app.use(pinia)` 之后才能取——把 token 的来源
 * 收在这个纯模块里，拦截器与 store 都依赖它，不会产生循环引用。
 *
 * 用 localStorage 是演示取向的决定：刷新页面不用重登。生产里 JWT 存
 * localStorage 对 XSS 没有防护，应当换成 HttpOnly Cookie + CSRF token。
 */
export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setSession(token: string, user: StoredUser): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function getStoredUser(): StoredUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as StoredUser
  } catch {
    // 存的内容坏了就当没登录，不要让一段脏 JSON 把整个应用卡在白屏
    return null
  }
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
