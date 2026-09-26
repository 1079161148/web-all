<script setup lang="ts">
import ProTable from '../components/pro/ProTable.vue'
import type { ProBatchAction, ProColumn, ProRowAction, ProTablePage, ProTableQuery } from '../types'

/**
 * ProTable 的 story。
 *
 * <h3>为什么请求函数是本地 mock 而不是复用 @admin/api</h3>
 * 组件库**不能依赖业务接口层**（那是它可复用的前提，见 registry/dict.ts 的说明）。
 * story 同理：它演示的是「传进来的请求函数会被怎么调用」，
 * 用一个确定性的内存数据源比接后端更能说明问题，也避免 story 因环境不可用而失败。
 */

interface Row {
  id: number
  username: string
  nickname: string
  status: string
  sex: string
  createTime: string
}

const STATUS = ['ACTIVE', 'DISABLED', 'LOCKED']
const SEX = ['0', '1', '2']

/** 固定公式生成数据：确定性（无随机），便于将来接视觉回归。 */
const ALL: Row[] = Array.from({ length: 57 }, (_, i) => ({
  id: i + 1,
  username: `user${String(i + 1).padStart(3, '0')}`,
  nickname: `用户${i + 1}`,
  status: STATUS[i % 3] as string,
  sex: SEX[i % 3] as string,
  createTime: new Date(Date.UTC(2026, 0, 1 + (i % 28), 8 + (i % 10), i % 60)).toISOString()
}))

/**
 * 模拟真实后端：分页 + 排序 + 条件过滤都在这里做。
 *
 * <p>刻意保留 200ms 延迟 —— 没有延迟就看不到加载态与骨架屏，
 * 而那正是本组件最需要被评审的部分之一。
 */
async function request(query: ProTableQuery): Promise<ProTablePage<Row>> {
  await new Promise((resolve) => setTimeout(resolve, 200))

  let rows = [...ALL]
  if (query.username) {
    rows = rows.filter((row) => row.username.includes(String(query.username)))
  }
  if (query.status) {
    rows = rows.filter((row) => row.status === query.status)
  }
  if (query.sortField && query.sortOrder) {
    const field = query.sortField as keyof Row
    const factor = query.sortOrder === 'asc' ? 1 : -1
    rows.sort((a, b) => (String(a[field]) > String(b[field]) ? factor : -factor))
  }

  const page = Number(query.page ?? 1)
  const size = Number(query.size ?? 20)
  return {
    records: rows.slice((page - 1) * size, page * size),
    total: rows.length,
    page,
    size
  }
}

/** 空数据源：用于演示空状态（真实空态比"造一条假数据"更难被评审到）。 */
async function requestEmpty(): Promise<ProTablePage<Row>> {
  await new Promise((resolve) => setTimeout(resolve, 120))
  return { records: [], total: 0, page: 1, size: 20 }
}

/** 失败数据源：演示错误态下组件不会卡在 loading。 */
async function requestFail(): Promise<ProTablePage<Row>> {
  await new Promise((resolve) => setTimeout(resolve, 120))
  throw new Error('模拟的接口失败')
}

const columns: ProColumn<Row>[] = [
  { key: 'username', title: '账号', width: 130, search: 'input', searchPlaceholder: '模糊匹配' },
  { key: 'nickname', title: '姓名', width: 120, search: 'input' },
  // dict 声明后：单元格自动渲染为标签，且搜索区可显式给 options
  {
    key: 'status',
    title: '状态',
    width: 110,
    dict: 'sys_user_status',
    search: 'select',
    options: [
      { label: '正常', value: 'ACTIVE' },
      { label: '停用', value: 'DISABLED' },
      { label: '锁定', value: 'LOCKED' }
    ]
  },
  // ⚠️ 刻意包含一个"字典里没有"的码值（见未命中态变体）
  { key: 'sex', title: '性别', width: 90, dict: 'sys_user_sex' },
  { key: 'createTime', title: '创建时间', width: 180, sortable: true, render: 'datetime' }
]

const rowActions: ProRowAction<Row>[] = [
  { key: 'edit', label: '编辑', onClick: () => undefined },
  { key: 'delete', label: '删除', danger: true, confirm: '确定删除该用户？', onClick: () => undefined },
  // 已被锁定的账号不能再停用 —— 用 disabled 让限制可见，而不是点了才被拒绝
  { key: 'toggle', label: '停用', disabled: (row) => row.status === 'LOCKED', onClick: () => undefined }
]

const batchActions: ProBatchAction<Row>[] = [
  { key: 'export', label: '批量导出', onClick: () => undefined },
  { key: 'delete', label: '批量删除', danger: true, confirm: '确定删除所选用户？', onClick: () => undefined }
]

/** 只有一行的数据源：演示分页器在总数很少时的表现。 */
async function requestSingle(): Promise<ProTablePage<Row>> {
  return { records: ALL.slice(0, 1), total: 1, page: 1, size: 20 }
}
</script>

<template>
  <Story title="Pro 组件/ProTable" :layout="{ type: 'grid', width: '100%' }">
    <Variant title="基础用法：只写列定义 + 请求函数" doc="页面侧的全部代码就是这两项。搜索区由列的 search 声明自动派生。">
      <ProTable :columns="columns" :request="request" />
    </Variant>

    <Variant title="多选与批量操作" doc="声明 batchActions 后自动出现多选列 —— 多选由批量操作派生，而不是独立开关。">
      <ProTable :columns="columns" :request="request" :batch-actions="batchActions" />
    </Variant>

    <Variant title="行操作（含禁用态）" doc="禁用态是可见的限制：'停用' 在已锁定行上不可点，而不是点了报错。">
      <ProTable :columns="columns" :request="request" :row-actions="rowActions" />
    </Variant>

    <Variant title="空数据（带引导操作）" doc="空态给一个明确入口，比只留一行『暂无数据』有用。">
      <ProTable
        :columns="columns"
        :request="requestEmpty"
        empty-text="还没有任何用户"
        empty-action-text="创建第一个用户"
      />
    </Variant>

    <Variant title="加载失败" doc="失败时清空数据并抛 error 事件；组件不自行弹提示（提示方式是应用级决策）。">
      <ProTable :columns="columns" :request="requestFail" />
    </Variant>

    <Variant title="精简工具栏 + 不分页场景" doc="用 toolbar 精确控制工具栏动作。">
      <ProTable :columns="columns" :request="requestSingle" :toolbar="['refresh']" />
    </Variant>
  </Story>
</template>
