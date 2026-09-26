<script setup lang="ts" generic="T extends object">
import { computed, ref, shallowRef } from 'vue'
import { NAlert, NButton, NSpace } from 'naive-ui'
import type { Cell } from 'exceljs'
import { ensureExcel } from '../../adapters/lazy'
import type { ProExcelColumn, ProExcelExpose, ProExcelRowError } from '../../types'

/**
 * Excel 导入导出向导（内核：exceljs）。
 *
 * <pre>{@code
 * const columns: ProExcelColumn<User>[] = [
 *   { key: 'username', title: '账号', required: true },
 *   { key: 'email', title: '邮箱', validate: (v) => /@/.test(String(v)) ? null : '邮箱格式不正确' }
 * ]
 *
 * <ProExcel ref="excelRef" :columns="columns" @imported="handleImport" />
 * // 服务端校验失败后：
 * excelRef.value?.downloadErrorRows(serverErrors)
 * }</pre>
 *
 * <h3>真正的价值在「错误行回写」，不是「能上传」</h3>
 * 只报"第 3 行邮箱格式不对"，用户还得回到几十上百行的大表里去找第 3 行 ——
 * 于是导入功能变成了一个"反复试错"的过程。
 * 而给一份<b>只含出错行、并多一列写明原因</b>的文件，改完直接重传即可。
 *
 * <p>因此本组件把客户端的行号与错误原因<b>保留成结构化数据</b>，
 * 并在界面上直接提供「下载错误行」。服务端返回的错误走同一个出口
 * （<b>客户端校验挡不住的规则，服务端一定会挡</b>，两条路径必须统一，
 * 否则用户会遇到"客户端过了、服务端又让你改，但给你的提示格式还不一样"）。
 *
 * <h3>⚠️ 行号口径：对外是「数据行序号」，不是 Excel 物理行号</h3>
 * 表头占一行，所以 {@code 数据行序号 + 1 = Excel 物理行号}。
 * 对外统一用数据行序号，是因为若直接把物理行号抛出去，
 * 提示"第 3 行有错"会让用户去看 Excel 的第 3 行（实际是第 2 条数据），
 * <b>于是改错了行</b> —— 而改错行比不提示更糟。
 *
 * <h3>⚠️ 导入按「表头标题」匹配列，不按列的位置</h3>
 * 用户几乎一定会在模板里调整列顺序、删掉不需要的列、或在中间插入自己的辅助列。
 * 按位置匹配的话，插入一列就会让<b>整表数据错位</b>，
 * 而且错得很安静（值都还在，只是串到别的字段上）。
 * 按标题匹配后，顺序与增删都不影响，匹配不到的表头直接忽略。
 */

const props = withDefaults(
  defineProps<{
    /**
     * 列定义。
     *
     * <p>⚠️ 泛型 prop 不给默认值（同 ProTable / ProTable 的说明）：
     * 一旦有默认值，Vue 的泛型推断会放弃并从调用方传入的类型退回泛型约束。
     */
    columns: ProExcelColumn<T>[]
    /** 工作表名。 */
    sheetName?: string
    /** 导出文件名（不含扩展名）。 */
    fileName?: string
    /**
     * 单次导入的最大行数。
     *
     * <p>浏览器里解析大文件会把主线程占满（exceljs 是同步解析），
     * 表现为整页卡死几十秒。与其"能跑但卡死"，不如提前拒绝并说明原因 ——
     * 需要更大批量时应当走后端异步导入。
     */
    maxImportRows?: number
    /** 单次导入的最大文件体积（MB）。 */
    maxFileSizeMb?: number
    /** 是否显示内置按钮组（关闭后可只用 ref 暴露的方法）。 */
    showActions?: boolean
  }>(),
  {
    sheetName: 'Sheet1',
    fileName: '导出数据',
    maxImportRows: 5000,
    maxFileSizeMb: 10,
    showActions: true
  }
)

const emit = defineEmits<{
  /** 校验通过的行。 */
  (e: 'imported', rows: T[]): void
  /** 存在问题的行（含行号与原因）。组件已自行展示，此事件供调用方记录/上报。 */
  (e: 'invalid', errors: Array<ProExcelRowError<T>>): void
  /** 内核加载或文件解析失败。 */
  (e: 'error', error: unknown): void
}>()

const EXCEL_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

const fileInput = ref<HTMLInputElement | null>(null)

const excelModule = shallowRef<typeof import('../../adapters/excel') | null>(null)
const loading = ref(false)

/**
 * ⚠️ 这里必须用 {@code shallowRef}（在 ProChart 上已踩过同一个坑）。
 *
 * <p>{@code ref<Array<ProExcelRowError<T>>>} 会让模板里的类型经过
 * {@code UnwrapRef} 深度解包，而<b>解包后的类型与原类型不再互相兼容</b> ——
 * 于是把它传回 {@code downloadErrorRows()} 会报"类型不可赋值"，
 * 且报错信息展开成一坨结构、看不出真因。
 *
 * <p>{@code shallowRef} 不做深度解包，类型原样保留。
 * 语义上也更对：错误列表本来就是整体替换，不需要逐项响应式。
 */
const lastErrors = shallowRef<Array<ProExcelRowError<T>>>([])
const lastImported = ref(0)
const lastTotal = ref(0)

/** 错误明细最多展示几条（多了会把页面撑爆，且用户更该下载文件去改）。 */
const MAX_ERROR_PREVIEW = 10
const visibleErrors = computed(() => lastErrors.value.slice(0, MAX_ERROR_PREVIEW))

async function loadKernel(): Promise<typeof import('../../adapters/excel')> {
  if (!excelModule.value) {
    excelModule.value = await ensureExcel()
  }
  return excelModule.value
}

/**
 * 触发浏览器下载。
 *
 * <p>{@code revokeObjectURL} 用 setTimeout 延迟释放：立即释放有可能
 * 让下载被取消（不同浏览器对"click 之后对象是否还需要存在"的时机不一致），
 * 表现为"点了没反应"，且只在部分浏览器上出现。
 */
function downloadBlob(content: ArrayBuffer, filename: string): void {
  const blob = new Blob([content], { type: EXCEL_MIME })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** 导出时是否包含某列（importOnly 的列只用于导入，如"错误原因"）。 */
const exportColumns = computed(() => props.columns.filter((column) => !column.importOnly))

/**
 * 列宽估算。
 *
 * <p>中文在 Excel 里占两个字符宽，所以用 {@code 标题长度 × 2}。
 * 不估算的话默认宽度会让中文表头显示成 {@code ####}，
 * 用户第一眼看到的就是"模板是坏的"。
 */
function estimateWidth(column: ProExcelColumn<T>): number {
  return column.width ?? Math.max(12, column.title.length * 2 + 4)
}

/** 把一行数据写进工作表。 */
function appendRow(
  sheet: import('exceljs').Worksheet,
  row: T,
  extra?: Record<string, unknown>
): void {
  const record: Record<string, unknown> = { ...extra }
  for (const column of exportColumns.value) {
    const raw = column.format ? column.format(row) : (row as Record<string, unknown>)[column.key]
    record[column.key] = raw ?? ''
  }
  sheet.addRow(record)
}

// ---------------------------------------------------------------------
// 导出 / 模板
// ---------------------------------------------------------------------

async function downloadTemplate(): Promise<void> {
  loading.value = true
  try {
    const { Workbook } = await loadKernel()
    const workbook = new Workbook()
    const sheet = workbook.addWorksheet(props.sheetName)
    sheet.columns = exportColumns.value.map((column) => ({
      header: column.title,
      key: column.key,
      width: estimateWidth(column)
    }))
    const buffer = await workbook.xlsx.writeBuffer()
    downloadBlob(buffer as ArrayBuffer, `${props.fileName}-导入模板.xlsx`)
  } catch (error) {
    emit('error', error)
  } finally {
    loading.value = false
  }
}

async function exportRows(rows: T[]): Promise<void> {
  loading.value = true
  try {
    const { Workbook } = await loadKernel()
    const workbook = new Workbook()
    const sheet = workbook.addWorksheet(props.sheetName)
    sheet.columns = exportColumns.value.map((column) => ({
      header: column.title,
      key: column.key,
      width: estimateWidth(column)
    }))
    for (const row of rows) {
      appendRow(sheet, row)
    }
    // 表头加粗 + 冻结首行：数据一多就必须能"边滚边看表头"
    sheet.getRow(1).font = { bold: true }
    sheet.views = [{ state: 'frozen', ySplit: 1 }]
    const buffer = await workbook.xlsx.writeBuffer()
    downloadBlob(buffer as ArrayBuffer, `${props.fileName}.xlsx`)
  } catch (error) {
    emit('error', error)
  } finally {
    loading.value = false
  }
}

/**
 * 错误行回写。
 *
 * <p>只输出<b>出错的行</b>（不输出全部）：用户的目标是"改完重传"，
 * 给他一份只含待修正行的文件比给全量更省事，也避免了"重传时把没出错的
 * 行又导入一次"造成重复数据。
 */
async function downloadErrorRows(errors: Array<ProExcelRowError<T>>): Promise<void> {
  if (errors.length === 0) {
    return
  }
  loading.value = true
  try {
    const { Workbook } = await loadKernel()
    const workbook = new Workbook()
    const sheet = workbook.addWorksheet('错误行')

    sheet.columns = [
      ...exportColumns.value.map((column) => ({
        header: column.title,
        key: column.key,
        width: estimateWidth(column)
      })),
      // 错误原因放在最后一列：放在最前会让用户在按模板对齐时容易串列
      { header: '错误原因', key: '__error', width: 40 }
    ]

    for (const item of errors) {
      appendRow(sheet, item.row ?? ({} as T), { __error: item.message })
    }

    sheet.getRow(1).font = { bold: true }
    sheet.views = [{ state: 'frozen', ySplit: 1 }]

    const buffer = await workbook.xlsx.writeBuffer()
    downloadBlob(buffer as ArrayBuffer, `${props.fileName}-错误行.xlsx`)
  } catch (error) {
    emit('error', error)
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------------
// 导入
// ---------------------------------------------------------------------

function triggerFilePick(): void {
  fileInput.value?.click()
}

/**
 * 读取单元格值。
 *
 * <p>⚠️ exceljs 的 {@code cell.value} <b>不总是原始值</b>：公式是
 * {@code { formula, result }}、富文本是 {@code { richText: [...] }}、
 * 超链接是 {@code { text, hyperlink }}。若直接拿来用，
 * 写进记录里的就是 {@code [object Object]} ——
 * 而它在界面上看起来"有值"，于是校验会通过、写库会失败。
 */
function readCellValue(cell: Cell): unknown {
  const value = cell.value
  if (value === null || value === undefined) {
    return ''
  }
  if (value instanceof Date) {
    return value
  }
  if (typeof value === 'object') {
    // 先经 unknown 再转：exceljs 的单元格值是一个联合类型，
    // 与本类型没有"足够的重叠"，直接断言会被 TS 判为可疑转换。
    // 这里是按"字段是否存在"逐个分支处理，运行期是安全的
    const record = value as unknown as Record<string, unknown>
    if ('result' in record) {
      return record.result ?? ''
    }
    if ('richText' in record && Array.isArray(record.richText)) {
      return (record.richText as Array<{ text?: string }>)
        .map((part) => part.text ?? '')
        .join('')
    }
    if ('text' in record) {
      return record.text
    }
    return String(value)
  }
  return value
}

function isEmptyValue(value: unknown): boolean {
  return value === null || value === undefined || value === ''
}

/** 逐列校验，返回错误文案列表。 */
function validateRow(record: T): string[] {
  const messages: string[] = []
  for (const column of props.columns) {
    const value = (record as Record<string, unknown>)[column.key]
    if (column.required && isEmptyValue(value)) {
      messages.push(`「${column.title}」为必填`)
      continue
    }
    const custom = column.validate?.(value, record)
    if (custom) {
      messages.push(custom)
    }
  }
  return messages
}

async function handleFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]

  // ⚠️ 立刻清空 input 的值：同一个文件第二次选择时不会触发 change，
  // 表现为"改了文件再传一次，没反应"。清空后每次选择都会触发。
  input.value = ''

  if (!file) {
    return
  }

  if (file.size > props.maxFileSizeMb * 1024 * 1024) {
    lastErrors.value = []
    emit('error', new Error(`文件超过 ${props.maxFileSizeMb} MB，请拆分后再导入`))
    return
  }

  loading.value = true
  lastErrors.value = []
  lastImported.value = 0
  lastTotal.value = 0

  try {
    const { Workbook } = await loadKernel()
    const workbook = new Workbook()
    await workbook.xlsx.load(await file.arrayBuffer())
    const sheet = workbook.worksheets[0]
    if (!sheet) {
      throw new Error('文件里没有任何工作表')
    }

    // 表头 → 字段：按标题匹配（见类注释）
    const keyByTitle = new Map(props.columns.map((column) => [column.title.trim(), column.key]))
    const keyByColumn = new Map<number, string>()
    sheet.getRow(1).eachCell((cell, columnNumber) => {
      const title = String(readCellValue(cell) ?? '').trim()
      const key = keyByTitle.get(title)
      if (key) {
        keyByColumn.set(columnNumber, key)
      }
    })

    if (keyByColumn.size === 0) {
      throw new Error(
        `表头与模板不匹配。期望的表头为：${props.columns.map((c) => c.title).join('、')}`
      )
    }

    const valid: T[] = []
    const errors: Array<ProExcelRowError<T>> = []
    let rowIndex = 0

    sheet.eachRow((row, rowNumber) => {
      if (rowNumber === 1) {
        return
      }
      // 整行都空：Excel 里拖拽/删除常常留下空行，不该算作"一行数据"
      const record: Record<string, unknown> = {}
      let hasValue = false
      for (const [columnNumber, key] of keyByColumn) {
        const value = readCellValue(row.getCell(columnNumber))
        record[key] = value
        if (!isEmptyValue(value)) {
          hasValue = true
        }
      }
      if (!hasValue) {
        return
      }

      rowIndex += 1
      if (rowIndex > props.maxImportRows) {
        return
      }

      const messages = validateRow(record as T)
      if (messages.length > 0) {
        errors.push({ rowIndex, row: record as T, message: messages.join('；') })
      } else {
        valid.push(record as T)
      }
    })

    lastTotal.value = rowIndex
    lastImported.value = valid.length
    lastErrors.value = errors

    if (valid.length > 0) {
      emit('imported', valid)
    }
    if (errors.length > 0) {
      emit('invalid', errors)
    }
  } catch (error) {
    emit('error', error)
  } finally {
    loading.value = false
  }
}

defineExpose<ProExcelExpose<T>>({
  downloadTemplate,
  exportRows,
  downloadErrorRows
})
</script>

<template>
  <div class="pro-excel">
    <n-space v-if="showActions" align="center" :size="8">
      <n-button :loading="loading" @click="triggerFilePick">导入</n-button>
      <n-button :disabled="loading" @click="downloadTemplate">下载模板</n-button>
      <input
        ref="fileInput"
        type="file"
        accept=".xlsx"
        class="pro-excel__file"
        @change="handleFileChange"
      />
    </n-space>

    <!-- 校验结果：直接把"哪些行、为什么"摆出来，并给出可下载的修正文件 -->
    <n-alert v-if="lastErrors.length > 0" type="warning" class="pro-excel__report">
      <p class="pro-excel__summary">
        共 {{ lastTotal }} 行：成功 {{ lastImported }} 行，<b>{{ lastErrors.length }} 行有问题</b>。
      </p>
      <ul class="pro-excel__list">
        <li v-for="item in visibleErrors" :key="item.rowIndex">
          第 {{ item.rowIndex }} 条数据：{{ item.message }}
        </li>
      </ul>
      <p v-if="lastErrors.length > MAX_ERROR_PREVIEW" class="pro-excel__more">
        仅显示前 {{ MAX_ERROR_PREVIEW }} 条，完整清单请下载下面的文件。
      </p>
      <n-button size="small" type="primary" @click="downloadErrorRows(lastErrors)">
        下载错误行（改完直接重传）
      </n-button>
    </n-alert>

    <n-alert
      v-else-if="lastImported > 0"
      type="success"
      class="pro-excel__report"
    >
      已成功导入 {{ lastImported }} 行。
    </n-alert>
  </div>
</template>

<style scoped>
/* 结构与尺寸，样式引用 Token（ui-component-policy 强行约束第 5 条） */
.pro-excel__file {
  display: none;
}

.pro-excel__report {
  margin-top: var(--wa-spacing-md);
}

.pro-excel__summary {
  margin: 0;
}

.pro-excel__list {
  margin: var(--wa-spacing-sm) 0;
  padding-left: var(--wa-spacing-lg);
  max-height: 220px;
  overflow-y: auto;
}

.pro-excel__more {
  margin: 0 0 var(--wa-spacing-sm);
  color: var(--wa-text-secondary);
  font-size: var(--wa-font-size-sm);
}
</style>
