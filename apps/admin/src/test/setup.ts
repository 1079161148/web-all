/**
 * vitest 全局 setup：补齐 jsdom 未实现的浏览器 API。
 *
 * jsdom 覆盖了 History/localStorage，但不实现 matchMedia ——
 * app store 顶层用它检测系统暗色偏好（跟随系统主题），缺它整个
 * store 链都无法初始化。stub 返回"浅色、不变化"，与测试无关紧要，
 * 只要结构正确。
 */
if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false
    })
  })
}
