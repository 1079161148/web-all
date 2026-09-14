<script setup lang="ts">
import TenantListView from '@/views/tenant/TenantListView.vue'

/**
 * 租户管理页面（动态路由入口）。
 *
 * <h3>为什么这里是一层薄包装</h3>
 * 后端菜单的 `component` 字段值是 `iam/tenant/index`，
 * 而动态路由会扫描 views 目录建映射表（扫描模式见 `router/dynamic.ts`），
 * 因此文件必须落在 `views/iam/tenant/index.vue` 这个路径上。
 *
 * <p>真正的实现暂时保留在 `views/tenant/TenantListView.vue`。
 * 这一层包装是<b>路径对齐</b>，不是架构分层 —— 把组件放在 `iam/` 下，
 * 是为了让"前端目录 = 后端菜单 = 限界上下文"三者一致，便于按模块定位代码。
 *
 * <p>待 P2 把 ProTable 内核换成 vxe-table 时，会把实现直接内联到本文件并删除旧路径，
 * 届时这层包装消失。现在保留它，是为了不在同一轮改动里同时做
 * "目录重构"与"组件内核替换"两件高风险的事。
 *
 * <h3>⚠️ 一个写注释时踩到的坑（供后续参考）</h3>
 * 本注释块最初写了 glob 通配模式作为示例，结果类型检查报了一串莫名其妙的语法错误。
 * 原因：通配模式的字符序列里包含 <b>星号紧跟斜杠</b>，
 * 而这正是块注释的结束标记 —— 注释被提前闭合，后面的说明文字被当成代码解析。
 * <b>在块注释里提到通配模式时必须绕开或改写</b>，否则错误位置会指向注释内容本身，
 * 与真正的成因（少了一个注释结束符）看起来毫无关系。
 */
</script>

<template>
  <tenant-list-view />
</template>
