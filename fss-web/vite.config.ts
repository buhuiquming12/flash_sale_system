import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发期用 Vite 代理把 /api 转到后端，而不是给后端开 CORS。
 *
 * 理由：CORS 一开就得决定 allow-origin、要不要 allowCredentials，
 * 而这套配置在生产里是同源部署、根本不需要——为了本地联调往生产配置里
 * 塞一段只在开发期成立的跨域策略，是后面最容易被误当成"线上也这样"的地方。
 * 代理把这件事完全留在前端工程里，后端零改动。
 *
 * 后端端口用 FSS_API 覆盖（README 里 job/consumer profile 会改端口）。
 */
const target = process.env.FSS_API ?? 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      // /actuator 也代理：观测面板要读健康状态与指标，它们不在 /api 下
      '/api': { target, changeOrigin: true },
      '/actuator': { target, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    // 演示环境要能对着 sourcemap 讲"这一步前端做了什么"
    sourcemap: true,
    // Element Plus 全量引入约 900KB，是已知且刻意接受的——演示环境同机访问，
    // 换成按需引入要多一个插件和一份组件清单，对这个项目不划算。
    // 阈值抬到 1000 是为了让「业务代码真的变大了」这件事仍然能触发警告
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        // Element Plus 一整个进 index chunk 会是 1MB+。拆出来不是为了首屏快
        // （演示环境无所谓），而是让业务代码的体积变化在每次构建里看得见
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          'element-plus': ['element-plus', '@element-plus/icons-vue'],
        },
      },
    },
  },
})
