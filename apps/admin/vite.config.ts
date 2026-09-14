import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 8 配置（Rolldown 统一引擎）。
 *
 * <h3>为什么显式用 alias 指向 workspace 包的源码</h3>
 * pnpm 通过符号链接把 workspace 包挂到 node_modules 下，
 * 直接引用会让部分插件把包内文件当作「外部依赖」跳过转换（尤其是 .vue 文件）。
 * 别名指到源码可以保证 SFC 编译与 HMR 都正常工作，也让改动即时生效、无需先构建包。
 *
 * <h3>分包策略（设计文档 §12.5）</h3>
 * 大依赖各自独立 chunk，避免单个 vendor 文件过大导致首屏阻塞。
 */
export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@admin/ui': fileURLToPath(new URL('../../packages/ui/src/index.ts', import.meta.url)),
      '@admin/api': fileURLToPath(new URL('../../packages/api/src/index.ts', import.meta.url)),
      '@admin/theme': fileURLToPath(new URL('../../packages/theme/src/index.ts', import.meta.url))
    }
  },

  server: {
    port: 5173,
    // 开发期通过代理访问后端，避免 CORS 配置与 Cookie 跨站问题
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },

  build: {
    target: 'es2022',
    sourcemap: false,
    // 单个 chunk 超过该体积告警（设计文档 §12.1 的预算门禁基线）
    chunkSizeWarningLimit: 1500
  }
})
