<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { NButton, NTag } from '@admin/ui'

/**
 * DeepSeek Harness 工作台（dsh Web UI 的原样嵌入页）。
 *
 * <h3>为什么是 iframe 而不是"复刻它的界面"</h3>
 * 需求是 <b>100% 原样的 Harness</b>：它是 DeepSeek 官方开源的 Agent Harness
 * （"一切皆插件"，会随插件生态持续变化）。任何"用我们的组件重画一遍"的方案
 * 都会在 dsh 发版时立刻过期。iframe 是唯一能保证"我们看到的永远等于官方
 * 界面"的载体 —— 版本升级对我们零成本。
 *
 * <h3>为什么 iframe 指向同源 /harness/ 而不是 127.0.0.1:3080</h3>
 * dsh 的会话 cookie 是 host-only、无 SameSite 属性（浏览器按 Lax 处理），
 * 跨站 iframe 里不会携带 → 页面永远 401。同源反代（vite dev 中间件 /
 * 生产 nginx）后 cookie 种在中台域名下，Host 由代理改写为
 * 127.0.0.1:3080，dsh 的 authority 校验才通过。详见 vite.config.ts 的
 * dev-harness-proxy 插件注释。
 *
 * <h3>认证握手由代理完成</h3>
 * dsh 首次访问需要带一次性 token（`/?token=xxx`，来自启动日志）。
 * 本页面不做这件事 —— 代理层在首次请求时读启动日志、自动完成
 * token 换 cookie 的握手并对浏览器种 cookie。页面只负责"探测可用性 →
 * 渲染 iframe"，职责边界清晰。
 */

type HarnessState = 'checking' | 'ready' | 'offline'

const state = ref<HarnessState>('checking')
/** 挂在 iframe 上的 key：刷新时换值强制重新加载（比 iframe.contentWindow 更可靠）。 */
const frameKey = ref(0)

/**
 * iframe 高度用**实测**而不是 `calc(100vh - Npx)`。
 *
 * <p>硬编码减法必须同时猜中顶栏高、页签高、内容区上下内边距 —— 任何一处
 * 调整（换主题、加页签、改内边距）都会让 iframe 底部漏出一条白边或溢出
 * 出滚动条（实测：140px 的估算就漏了一条）。这里在挂载/窗口尺寸变化时
 * 量一次"容器顶边到视口底部"的真实距离，永远贴合当前布局。
 */
const hostEl = ref<HTMLDivElement | null>(null)
const barEl = ref<HTMLDivElement | null>(null)
const frameHeight = ref('70vh')

function measure(): void {
  const el = hostEl.value
  if (!el) {
    return
  }
  // ⚠️ 从**工具条下沿**起算，而不是容器上沿：主体在工具条下方，
  // 按容器上沿算会多出一个工具条的高度 → iframe 溢出视口
  // （实测 720 高视口下溢出 22px，页面出现滚动条）。
  const barHeight = barEl.value?.getBoundingClientRect().height ?? 36
  const top = el.getBoundingClientRect().top + barHeight
  // 预留与布局内边距等宽的底部间隙，避免 iframe 紧贴视口边缘
  const bottomGap = 16
  frameHeight.value = `${Math.max(320, Math.round(window.innerHeight - top - bottomGap))}px`
}

/** 探测 Harness 是否可用：代理在 dsh 未运行时返回 502，据此区分。 */
async function probe(): Promise<void> {
  state.value = 'checking'
  try {
    const response = await fetch('/harness/', { method: 'GET', redirect: 'follow' })
    state.value = response.ok ? 'ready' : 'offline'
  } catch {
    // 网络层失败（dev server 未起等）：同样按不可用处理
    state.value = 'offline'
  }
}

function reload(): void {
  frameKey.value += 1
}

function openExternal(): void {
  window.open('/harness/', '_blank', 'noopener')
}

const statusType = computed(() => {
  if (state.value === 'ready') {
    return 'success'
  }
  return state.value === 'checking' ? 'default' : 'error'
})

const statusText = computed(() => {
  if (state.value === 'ready') {
    return '已连接'
  }
  return state.value === 'checking' ? '检测中…' : '未启动'
})

onMounted(() => {
  measure()
  window.addEventListener('resize', measure)
  void probe()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', measure)
})
</script>

<template>
  <!-- 沉浸式容器：顶部一条紧凑工具条，其余全部留给 Harness 本身 -->
  <div ref="hostEl" class="harness">
    <div ref="barEl" class="harness__bar">
      <span class="harness__title">DeepSeek Harness</span>
      <NTag size="small" :type="statusType">{{ statusText }}</NTag>
      <span class="harness__spacer" />
      <NButton size="tiny" quaternary :disabled="state !== 'ready'" @click="reload">
        重新加载
      </NButton>
      <NButton size="tiny" quaternary :disabled="state !== 'ready'" @click="openExternal">
        新窗口打开
      </NButton>
    </div>

    <!-- 未启动：给出明确的启动命令，而不是让用户看一片空白 -->
    <div
      v-if="state === 'offline'"
      class="harness__stage"
      :style="{ height: frameHeight }"
    >
      <p class="harness__offline-title">Harness 服务未运行</p>
      <p class="harness__offline-tip">
        在项目根目录执行下面的命令启动（首次运行会下载依赖，约 1~2 分钟），完成后点「重新检测」：
      </p>
      <pre class="harness__cmd">pnpm harness</pre>
      <p class="harness__offline-tip">
        工作原理：dsh 监听 127.0.0.1:3080，本页通过同源代理 <code>/harness/</code> 访问它
        —— 代理负责改写 Host/Origin、注入一次性 token 完成会话握手，并把 dsh 的
        <code>/api</code> 请求与 <code>/api/remote.mux</code>（WebSocket）一并转发。
      </p>
      <NButton size="small" type="primary" @click="probe">重新检测</NButton>
    </div>

    <div v-else-if="state === 'checking'" class="harness__stage" :style="{ height: frameHeight }">
      <p class="harness__offline-tip">正在检测 Harness 服务…</p>
    </div>

    <iframe
      v-else
      :key="frameKey"
      class="harness__frame"
      :style="{ height: frameHeight }"
      src="/harness/"
      title="DeepSeek Harness"
    />
  </div>
</template>

<style scoped>
/* 容器本身不设高度：工具条 + 主体按内容自然堆叠，
   主体高度由 measure() 精确给出（见上方注释） */
.harness {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.harness__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  flex: none;
}

.harness__title {
  font-weight: 600;
  font-size: 13px;
}

.harness__spacer {
  flex: 1;
}

.harness__frame {
  width: 100%;
  border: 1px solid rgba(128, 128, 128, 0.18);
  border-radius: 8px;
  background: #fff;
  display: block;
}

.harness__stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  text-align: center;
  padding: 0 40px;
  box-sizing: border-box;
  border: 1px dashed rgba(128, 128, 128, 0.28);
  border-radius: 8px;
}

.harness__offline-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.harness__offline-tip {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  opacity: 0.75;
  max-width: 640px;
}

.harness__cmd {
  margin: 4px 0;
  padding: 8px 16px;
  border-radius: 6px;
  background: rgba(128, 128, 128, 0.12);
  font-size: 13px;
}
</style>
