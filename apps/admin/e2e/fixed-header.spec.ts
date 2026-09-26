import { expect, setLayoutPrefs, test } from './helpers/auth'

/**
 * 固定头部（fixedHeader）：布局引擎最核心的布局语义。
 *
 * 历史 bug：naive 的 n-layout 会把子元素包进 .n-layout-scroll-container，
 * flex 布局没有下沉到这一层，长内容把滚动发生在包装层 —— 头部跟着滚走。
 * 本用例锁住修复：滚动内容区时 header/tabs 的视口位置不得变化。
 */
test.describe('固定头部', () => {
  test('开启固定：滚动内容区，头部与页签钉在原地', async ({ page }) => {
    await setLayoutPrefs(page, { fixedHeader: true, layoutMode: 'vertical' })
    // media-lab 是长页面（内容高度远超视口），天然适合验证滚动行为
    await page.goto('/showcase/media-lab')
    await expect(page.locator('.pro-layout__header')).toBeVisible({
      timeout: 20_000
    })

    const before = await page.locator('.pro-layout__header').boundingBox()
    expect(before).not.toBeNull()

    // 固定模式：滚动发生在内容区内部的 n-scrollbar（外层被 overflow:hidden 禁滚，
    // 自动跳过）；flow 模式：滚动发生在 main 根容器 —— 根排第一，两种模式通吃
    const scrolled = await page.evaluate(() => {
      const candidates = [
        document.querySelector('.pro-layout__main'),
        document.querySelector('.pro-layout__content .n-scrollbar-container')
      ]
      for (const scroller of candidates) {
        if (scroller && scroller.scrollHeight > scroller.clientHeight) {
          scroller.scrollTop = 400
          return true
        }
      }
      return false
    })
    expect(scrolled).toBe(true)
    await page.waitForTimeout(600)

    const after = await page.locator('.pro-layout__header').boundingBox()
    expect(Math.abs((after?.y ?? 0) - (before?.y ?? 0))).toBeLessThan(1)
  })

  test('关闭固定：滚动时头部跟随内容（flow 模式语义）', async ({ page }) => {
    await setLayoutPrefs(page, { fixedHeader: false, layoutMode: 'vertical' })
    await page.goto('/showcase/media-lab')
    await expect(page.locator('.pro-layout__header')).toBeVisible({
      timeout: 20_000
    })

    const before = await page.locator('.pro-layout__header').boundingBox()
    await page.evaluate(() => {
      const candidates = [
        document.querySelector('.pro-layout__main'),
        document.querySelector('.pro-layout__content .n-scrollbar-container')
      ]
      for (const scroller of candidates) {
        if (scroller && scroller.scrollHeight > scroller.clientHeight) {
          scroller.scrollTop = 400
          return
        }
      }
    })
    await page.waitForTimeout(600)

    const after = await page.locator('.pro-layout__header').boundingBox()
    // flow 模式头部必须离开原位（跟随内容滚走）—— 差值应明显大于 0
    expect(Math.abs((after?.y ?? 0) - (before?.y ?? 0))).toBeGreaterThan(50)
  })
})
