import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 需要登录 */
    auth?: boolean
    /** 需要 role=1 */
    admin?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/seckill' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/seckill',
    name: 'seckill',
    component: () => import('@/views/SeckillView.vue'),
    meta: { title: '秒杀大厅', auth: true },
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('@/views/OrderView.vue'),
    meta: { title: '我的订单', auth: true },
  },
  {
    path: '/observe',
    name: 'observe',
    component: () => import('@/views/ObserveView.vue'),
    meta: { title: '链路观测', auth: true },
  },
  {
    path: '/admin',
    name: 'admin',
    component: () => import('@/views/AdminView.vue'),
    meta: { title: '管理控制台', auth: true, admin: true },
  },
  // 兜底到秒杀大厅，避免手输错路径看到空白页
  { path: '/:pathMatch(.*)*', redirect: '/seckill' },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.auth && !auth.loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 管理端在前端也挡一道，纯粹是体验问题——真正的鉴权在 AdminAuthFilter，
  // 前端这层挡住的只是「点进去必然 403」的无效跳转
  if (to.meta.admin && !auth.isAdmin) {
    return { name: 'seckill' }
  }
  if (to.name === 'login' && auth.loggedIn) {
    return { name: 'seckill' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 秒杀系统演示` : '秒杀系统演示'
})

export default router
