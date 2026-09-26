<script setup lang="ts">
import { ref } from 'vue'
import ProCron from '../components/pro/ProCron.vue'

/**
 * ProCron 的 story。
 *
 * <p>注意看<b>编辑器内部的文案是中文的</b> —— 那是内核语言包的效果
 * （`locale="cn"`），不是我们写死的文案。
 * 把 locale 改成 `en` 就能立刻看到差别，这也是验证语言包真的接上了的方法：
 * 传错码（如 `zh-CN`）不会报错，只会静默变英文。
 */

const crontab = ref('0 12 * * *')
const spring = ref('0 0 12 * * ?')
const quartz = ref('0 0 12 * * ? 2026')
const disabledValue = ref('*/5 * * * *')
const emptyValue = ref('')
const broken = ref('0 12 * *')
const locale = ref('cn')
</script>

<template>
  <Story title="Pro 组件/ProCron" :layout="{ type: 'grid', width: '100%' }">
    <Variant
      title="crontab（5 段）"
      doc="编辑器用本地化片段把选择项拼成一句可读的话。「表达式」一行是原始值的回显，便于复制核对 —— 它不是「翻译」，两者别混为一谈。"
    >
      <ProCron v-model:value="crontab" format="crontab" />
    </Variant>

    <Variant title="spring（6 段，多一个秒）" doc="段数不同是格式差异，不是非法值。">
      <ProCron v-model:value="spring" format="spring" />
    </Variant>

    <Variant title="quartz（6 或 7 段）" doc="年份可选，所以 6 段与 7 段都算合法 —— 只认 7 段会把大量合法值判成非法。">
      <ProCron v-model:value="quartz" format="quartz" />
    </Variant>

    <Variant
      title="⚠️ 段数不对：结构性检查后不加载内核"
      doc="「0 12 * *」只有 4 段，与 crontab 的 5 段不符。此时显示原始值与原因，并且**完全不加载内核** —— 把坏值喂给编辑器只会渲染出一个错乱的状态。"
    >
      <ProCron v-model:value="broken" format="crontab" />
    </Variant>

    <Variant
      title="locale 切换（验证语言包真的接上了）"
      doc="⚠️ 可用码就是内核语言包的文件名（cn / en / de / ja / ko / ru …）。传 zh-CN 不会报错，只会静默回退英文。"
    >
      <n-space vertical :size="12">
        <n-select
          v-model:value="locale"
          :options="[
            { label: 'cn（简体中文）', value: 'cn' },
            { label: 'en（English）', value: 'en' },
            { label: 'ja（日本語）', value: 'ja' },
            { label: 'zh-CN（❌ 不存在此码，会回退英文）', value: 'zh-CN' }
          ]"
          style="max-width: 320px"
        />
        <ProCron v-model:value="crontab" format="crontab" :locale="locale" />
      </n-space>
    </Variant>

    <Variant title="禁用态" doc="内核只有 disabled，没有独立的只读态 —— 因此这里也不造一个假的 readonly。">
      <ProCron v-model:value="disabledValue" format="crontab" disabled />
    </Variant>

    <Variant title="空值" doc="空值不算错：「还没填」与「填错了」是两件事，前者不该报警。">
      <ProCron v-model:value="emptyValue" format="crontab" />
    </Variant>
  </Story>
</template>
