import { defineConfig } from '@playwright/test'

/**
 * E2E（playwright/test）。
 *
 * <h3>运行前提</h3>
 * 前端 dev server（5173）与后端（8080）已在运行 —— 这里刻意不配 webServer
 * 自动拉起：E2E 依赖真实后端（登录/租户/菜单），起一整套后端远超单测的
 * 代价，且与本地联调流程冲突。跑法：
 *
 *   pnpm --filter @admin/admin dev   # 终端 1
 *   （后端照常启动）                  # 终端 2
 *   pnpm test:e2e                    # 终端 3
 *
 * <h3>认证</h3>
 * 登录页有图片验证码，无法脚本内全自动登录。认证态通过环境变量注入：
 *   E2E_TOKEN=<accessToken> pnpm test:e2e
 * 未提供时用例跳过（而不是失败）—— 保证"没配 token"不会污染门禁。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: 'http://localhost:5173',
    viewport: { width: 1440, height: 900 }
  },
  // 单 worker：页签持久化的用例共享浏览器 localStorage，并行会互相污染
  workers: 1,
  reporter: [['list']]
})
