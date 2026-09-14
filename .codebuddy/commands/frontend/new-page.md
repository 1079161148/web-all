---
description: "生成前端页面：列表页(ProTable) / 表单页 / 详情页 / 树表页，含权限、字典、状态矩阵、路由与 API 接入"
argument-hint: "[模块名] [类型：list|form|detail|tree-table|dashboard]"
---

# 生成前端页面

模块名：**$1**
页面类型：**$2**

请先读取 `.codebuddy/rules/frontend-architecture.mdc`、`.codebuddy/rules/ui-component-policy.mdc` 与 `后台管理系统功能设计方案.md` 的「11.4 Pro 组件层」与「11.9 交互规范：状态矩阵」章节。

## 第 1 步：确认前置条件（缺失则先问）

| 项 | 需要确认 |
|---|---|
| 后端接口 | 是否已完成？OpenAPI 是否已生成？对应 hook 名称是什么？ |
| 权限码 | 列表/新增/编辑/删除/导出 分别是什么权限码 |
| 字典编码 | 状态等枚举字段用哪个字典 |
| 是否有同类页面 | 是否可参考现有页面保持一致性 |

**若后端接口不存在，先告知我并停下** —— 不要手写接口类型绕过契约优先。

## 第 2 步：文件产出

```
apps/admin/src/views/$1/
├─ index.vue          # 主页面
├─ components/        # 页面私有组件（如编辑弹窗）
├─ types.ts           # 仅页面私有类型（接口类型必须来自 @admin/api）
└─ api.ts             # 仅业务组合逻辑（基础请求来自 @admin/api）
```

## 第 3 步：按类型生成

### `list`（列表页 —— 最常用）

```vue
<template>
  <ProTable
    :columns="columns"
    :request="get$1Page"
    :toolbar="['create', 'export', 'refresh', 'column-setting']"
    :row-actions="rowActions"
    row-key="id"
  />
</template>
```

必须包含：

| 项 | 要求 |
|---|---|
| 列定义 | `ProColumn[]`，`dict` 声明字典、`search` 声明搜索控件、`permission` 声明权限 |
| 请求 | 使用 `@admin/api` 生成的 hook，**禁止手写类型或请求** |
| 工具栏 | 新增 / 导出 / 刷新 / 列设置，各项带 `permission` |
| 行操作 | 编辑 / 删除等，危险操作加 `confirm` |
| 状态矩阵 | 由 ProTable 内置；若自定义需覆盖 加载/空/错误/无权限 四态 |
| 筛选同步 | 筛选项与分页同步到 URL query（可分享、可刷新恢复） |

### `form`（表单页）

- `ProForm` schema 驱动，校验规则来自生成的 Zod schema
- 提交走 mutation hook，成功后 invalidate 相关 query
- **提交按钮必须防重复提交**（loading + 禁用）
- 危险操作二次确认；可逆操作提供撤销

### `detail`（详情页）

- `ProDescriptions` + 字典翻译
- 敏感字段由字段权限自动处理，**页面不要自己写脱敏逻辑**

### `tree-table`（树表页）

- `ProTree` 或 ProTable 的树形模式
- 懒加载子节点，不一次性拉全树

### `dashboard`（看板页）

- `ProChart` 封装 ECharts，**option 原样透传，不重定义**
- 图表懒加载 + 自适应 + 主题联动
- 多接口并行请求（`Promise.all` / `useQueries`），避免瀑布

## 第 4 步：路由与菜单

- 页面路径：通常由**后端菜单树**驱动，不需要前端硬编码
- 若需静态路由（如工作台），在路由文件登记并说明原因
- **不要在前端硬编码菜单项**

## 必须遵守

| # | 约束 |
|---|---|
| 1 | **禁止直接 import `naive-ui` / `vxe-table` / `form-create`**，只能从 `@admin/ui` 引入 |
| 2 | **禁止手写接口类型与请求函数** |
| 3 | **服务端数据不进 Pinia**，用 TanStack Query |
| 4 | **禁止硬编码颜色 / 间距 / 字号**，走 `@admin/theme` Token |
| 5 | 空态必须有**可操作引导**（不是"暂无数据"） |
| 6 | 错误态必须有**重试按钮 + traceId**（可复制） |
| 7 | 危险操作二次确认，可逆操作提供撤销 |
| 8 | 表格单元格内**不放组件库组件**（性能） |
| 9 | 文案走 i18n，不写死中文 |
| 10 | 页面代码目标：列表页 ≤ 60 行 script |

## 输出要求

1. 先列出**文件清单**与关键设计决策
2. 创建文件
3. 说明：**权限码使用情况**、**依赖的 hook 来源**、**下一步待办**（如补 i18n key、补测试）
4. 若 $2 类型不合法或缺失，**先问我**，不要默认按列表页生成
