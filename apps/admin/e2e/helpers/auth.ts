import { test as base, expect, type Page } from '@playwright/test'

/**
 * 认证态注入。
 *
 * 登录页有图片验证码，脚本内无法全自动完成登录 —— 与其做一个
 * 脆弱的 OCR，不如复用"从后端/运维处拿一个 accessToken"的现实：
 * 环境变量 E2E_TOKEN 存在则注入 sessionStorage，不存在则跳过用例。
 */
const TOKEN = process.env.E2E_TOKEN ?? ''

export const test = base.extend({
  page: async ({ page }, use) => {
    test.skip(!TOKEN, '需要 E2E_TOKEN 环境变量（accessToken）')
    await page.goto('/login')
    await page.evaluate((token) => {
      sessionStorage.setItem('accessToken', token)
      sessionStorage.setItem('tenantId', '1')
    }, TOKEN)
    await use(page)
  }
})

export { expect }

/** 布局偏好：写入 localStorage 的 admin.layout（与 store 的持久化 key 一致）。 */
export async function setLayoutPrefs(
  page: Page,
  patch: Record<string, unknown>
): Promise<void> {
  await page.evaluate((p) => {
    const raw = JSON.parse(localStorage.getItem('admin.layout') ?? '{}')
    Object.assign(raw, p)
    localStorage.setItem('admin.layout', JSON.stringify(raw))
  }, patch)
}
