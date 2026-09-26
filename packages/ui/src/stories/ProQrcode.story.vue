<script setup lang="ts">
import { ref } from 'vue'
import ProQrcode from '../components/pro/ProQrcode.vue'

/**
 * ProQrcode 的 story。
 *
 * <p>内容刻意用**真实业务形态**（邀请链接、设备凭据 JSON）而不是 "hello"：
 * 二维码的信息容量是硬约束，短文本生成的码稀疏易扫，长文本会立刻变密 ——
 * 只有真实内容才能回答"这个尺寸在实际场景里还扫得动吗"。
 */

const inviteUrl = ref('https://example.com/admin/invite?token=8f3a9c21d4e5b6a7&tenant=main')

/** 设备凭据：长度接近真实绑定凭证，用来观察容量边界。 */
const devicePayload = ref(
  JSON.stringify({
    tenant: 'main',
    deviceId: 'GW-2026-0926-0001',
    issuedAt: '2026-09-26T09:00:00+08:00',
    sign: 'a1b2c3d4e5f60718293a4b5c6d7e8f90'
  })
)
</script>

<template>
  <Story title="Pro 组件/ProQrcode" :layout="{ type: 'grid', width: '33%' }">
    <Variant title="基础用法（邀请链接）">
      <ProQrcode :value="inviteUrl" />
    </Variant>

    <Variant title="可下载 + 品牌色 + 更大尺寸" doc="下载导出的是 PNG（用 toBlob，不生成 base64 字符串）">
      <ProQrcode
        :value="inviteUrl"
        :size="200"
        foreground="#1e3a8a"
        downloadable
        download-name="invite"
      />
    </Variant>

    <Variant title="高纠错级别 H" doc="计划叠加 Logo 时必须用 H：遮挡部分靠纠错恢复">
      <ProQrcode :value="inviteUrl" level="H" :size="180" />
    </Variant>

    <Variant title="长内容（容量边界）" doc="同样的尺寸下码明显变密，识别距离会缩短">
      <ProQrcode :value="devicePayload" :size="200" level="L" />
    </Variant>

    <Variant title="空内容" doc="清空画布且不报错 —— 清空输入框是正常操作">
      <ProQrcode value="" :size="160" />
    </Variant>
  </Story>
</template>
