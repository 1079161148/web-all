import { expect, setLayoutPrefs, test } from './helpers/auth'

/**
 * 页签持久化：把之前的一次性手工验证固化为可重复执行的回归。
 *
 * 覆盖三层（对应 store 的三个语义）：
 *   1. 恢复 —— 刷新后页签回来
 *   2. 开关即时性 —— 关闭持久化后【不做任何页签操作】刷新，页签不回来
 *      （这是"开关没生效"bug 的核心回归）
 *   3. 脏数据防御 —— 存储里的重复项/失效路由/垃圾项不进 UI
 */
test.describe('页签持久化', () => {
  test.beforeEach(async ({ page }) => {
    await setLayoutPrefs(page, { tabsPersistence: true })
    await page.evaluate(() => localStorage.removeItem('admin.tabs'))
  })

  test('开两个页签 → 刷新 → 页签恢复', async ({ page }) => {
    // 注意：/dashboard 就是首页固定页签（HOME_PATH），不算"新开的页签" ——
    // 用两个非首页路由验证恢复语义
    await page.goto('/showcase/media-lab')
    await expect(page.locator('.pro-layout__tabs .n-tabs-tab')).toHaveCount(2, {
      timeout: 20_000
    })
    await page.goto('/showcase/live-tech')
    await expect(page.locator('.pro-layout__tabs .n-tabs-tab')).toHaveCount(3)

    await page.reload()
    // 落地路由 live-tech（immediate watch 补齐）+ 恢复的 media-lab + 首页
    await expect(page.locator('.pro-layout__tabs .n-tabs-tab')).toHaveCount(3)
    await expect(page.locator('.pro-layout__tabs')).toContainText('图片视频处理')
    await expect(page.locator('.pro-layout__tabs')).toContainText('技术专题')
  })

  test('关闭持久化开关后（不做任何页签操作）刷新 → 只剩首页', async ({
    page
  }) => {
    await page.goto('/showcase/media-lab')
    await expect(page.locator('.pro-layout__tabs .n-tabs-tab')).toHaveCount(2, {
      timeout: 20_000
    })

    // 通过配置抽屉真实切换开关（走 UI 而不是直接改 localStorage，
    // 因为要验证的正是 store 对开关变化的响应）
    await page.locator('.pro-layout__actions button').last().click()
    const row = page.locator('.set-group__row', { hasText: '页签持久化' })
    await row.locator('.n-switch').click()
    // 偏好落盘（appStore 的 watch 是 flush:'pre' 异步）+ 页签存储被清，
    // 都发生在微任务里 —— 给落盘留出时间再刷新
    await page
      .waitForFunction(
        () => {
          const layout = JSON.parse(localStorage.getItem('admin.layout') ?? '{}')
          return layout.tabsPersistence === false && localStorage.getItem('admin.tabs') === null
        },
        { timeout: 8000 }
      )
    await page.keyboard.press('Escape')
    // 落回首页再刷新：当前路由无论如何都会被 immediate watch 补回页签栏
    //（正在看的页面必须入栏），"干净工作区"验证的是【历史页签不回来】
    await page.goto('/dashboard')
    await expect(page.locator('.pro-layout__tabs .n-tabs-tab')).toHaveCount(1, {
      timeout: 20_000
    })

    await page.reload()
    await expect(page.locator('.pro-layout__tabs .n-tabs-tab')).toHaveCount(1)
    await expect(page.locator('.pro-layout__tabs')).toContainText('首页')
  })

  test('存储中的脏数据（重复/失效路由/坏项）不进页签栏', async ({ page }) => {
    await page.evaluate(() => {
      localStorage.setItem(
        'admin.tabs',
        JSON.stringify([
          { key: '/showcase/media-lab', label: '图片视频处理' },
          { key: '/showcase/media-lab', label: '重复项' },
          { key: '/deleted/route-xyz', label: '已下线页面' },
          null,
          { label: '缺key' },
          'garbage'
        ])
      )
    })
    await page.goto('/dashboard')
    // 恢复结果：首页 + 唯一合法且路由可达的 media-lab，重复/失效/坏项全部被滤掉
    await expect(page.locator('.pro-layout__tabs .n-tabs-tab')).toHaveCount(2, {
      timeout: 20_000
    })
    const texts = await page
      .locator('.pro-layout__tabs .n-tabs-tab')
      .allInnerTexts()
    expect(texts.filter((t) => t.includes('图片视频处理'))).toHaveLength(1)
    expect(texts.some((t) => t.includes('已下线'))).toBe(false)
  })
})
