/// <reference types="vitest" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 单测配置。
 *
 * <h3>为什么不用 vitest/config 的 defineConfig</h3>
 * vitest 3.2.7 内嵌 vite@7 的类型，而本项目是 vite@8（rolldown）——
 * 两种 Plugin 类型互不兼容（hotUpdate hook 签名不同），混用会让
 * vue() 插件在 typecheck 阶段报 TS2769。这里用 vite 自己的
 * defineConfig + `/// <reference types="vitest" />` 声明 test 字段，
 * 运行时 vitest 正常读取。
 *
 * <h3>alias 为什么重复定义而不共享</h3>
 * 同样的类型鸿沟：共享模块若用 vite8 类型导出，会被 vitest(vite7)
 * 的 resolve 消费 —— 运行时无碍但类型层报错。alias 的顺序语义
 * （'@admin/ui/core' 必须在 '@admin/ui' 之前）见 config/alias.ts 的
 * 注释，两边改动时务必同步。
 */
export default defineConfig({
  plugins: [
    // 测试链会触达 .vue 文件（store → @admin/ui barrel → AdminConfigProvider.vue），
    // 缺了它 collect 阶段直接解析失败
    vue()
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@admin/ui/core': fileURLToPath(
        new URL('../../packages/ui/src/core.ts', import.meta.url)
      ),
      '@admin/ui': fileURLToPath(
        new URL('../../packages/ui/src/index.ts', import.meta.url)
      ),
      '@admin/api': fileURLToPath(
        new URL('../../packages/api/src/index.ts', import.meta.url)
      ),
      '@admin/theme': fileURLToPath(
        new URL('../../packages/theme/src/index.ts', import.meta.url)
      )
    }
  },
  test: {
    // jsdom：store 链上有浏览器顶层副作用（@/router 的 createWebHistory、
    // app/tabs store 的 localStorage 读写），node 环境跑不起来
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    setupFiles: ['src/test/setup.ts']
  }
})
