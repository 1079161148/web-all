<script setup lang="ts">
import { ref } from 'vue'
import { NTag } from 'naive-ui'
import ProExcel from '../components/pro/ProExcel.vue'
import type { ProExcelColumn, ProExcelExpose, ProExcelRowError } from '../index'

/**
 * ProExcel 的 story。
 *
 * <p>导入这一环需要真实文件，story 里没法自动跑；
 * 因此这里把<b>「错误行回写」用构造的数据直接演示</b> ——
 * 它恰好是导入向导最容易被做漏、也最难在代码评审里看出来的一环。
 */

interface UserRow {
  username: string
  nickname: string
  email: string
  phone: string
}

const columns: ProExcelColumn<UserRow>[] = [
  { key: 'username', title: '账号', required: true },
  { key: 'nickname', title: '姓名', required: true },
  {
    key: 'email',
    title: '邮箱',
    validate: (value) => {
      if (!value) {
        return null
      }
      // 校验返回的是「原因」而不是布尔 —— 原因最终要写回 Excel 给用户看，
      // 若只返回布尔，调用方还得在别处再维护一份"字段 → 原因"的映射
      return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(value)) ? null : '邮箱格式不正确'
    }
  },
  {
    key: 'phone',
    title: '手机号',
    validate: (value) =>
      !value || /^1\d{10}$/.test(String(value)) ? null : '手机号应为 11 位数字'
  }
]

const received = ref(0)
const errorCount = ref(0)
const excelRef = ref<ProExcelExpose<UserRow> | null>(null)

/** 模拟服务端返回的错误（客户端校验挡不住业务规则，如"账号已存在"）。 */
const serverErrors: Array<ProExcelRowError<UserRow>> = [
  {
    rowIndex: 2,
    row: { username: 'zhangsan', nickname: '张三', email: 'zhangsan@example.com', phone: '13800000001' },
    message: '账号已存在，请更换'
  },
  {
    rowIndex: 5,
    row: { username: 'lisi', nickname: '李四', email: 'lisi@example.com', phone: '13800000002' },
    message: '账号已存在，请更换；邮箱域名不在允许列表内'
  }
]

function handleServerErrors(): void {
  void excelRef.value?.downloadErrorRows(serverErrors)
}

const exportRows: UserRow[] = [
  { username: 'admin', nickname: '系统管理员', email: 'admin@example.com', phone: '13812345678' },
  { username: 'zhangsan', nickname: '张三', email: 'zhangsan@example.com', phone: '13800000001' }
]
</script>

<template>
  <Story title="Pro 组件/ProExcel" :layout="{ type: 'grid', width: '100%' }">
    <Variant
      title="基础用法：导入 + 下载模板"
      doc="点「下载模板」会得到一个只有表头、且中文表头宽度已估算好的 xlsx（否则表头会显示成 ####）。导入按**表头标题**匹配列，因此用户调整列顺序、删列、插入辅助列都不会导致数据错位。"
    >
      <n-space vertical :size="12">
        <ProExcel
          ref="excelRef"
          :columns="columns"
          file-name="用户数据"
          @imported="(rows) => (received = rows.length)"
          @invalid="(errors) => (errorCount = errors.length)"
        />
        <n-tag v-if="received" size="small" type="success">收到 {{ received }} 行有效数据</n-tag>
        <n-tag v-if="errorCount" size="small" type="warning">{{ errorCount }} 行被拦下</n-tag>
      </n-space>
    </Variant>

    <Variant
      title="⚠️ 服务端错误回写（导入向导的真正价值）"
      doc="点下面的按钮，会下载一份「只含出错行 + 多一列写明原因」的 xlsx。只报「第 2 行有问题」，用户还得回大表里找第 2 行；给他一份待修正文件，改完直接重传。注意错误原因放在**最后一列** —— 放最前会让人按模板对齐时串列。"
    >
      <n-space vertical :size="12">
        <n-button size="small" type="primary" @click="handleServerErrors">
          模拟服务端返回 2 条错误 → 下载错误行
        </n-button>
        <n-alert type="info" title="为什么服务端错误要复用同一个出口">
          客户端校验挡不住的规则（账号已存在、配额超限）只能由服务端裁决。
          若两条路径各自出一份提示，用户会遇到"客户端过了、服务端又让你改，
          但提示格式还不一样"。
        </n-alert>
      </n-space>
    </Variant>

    <Variant title="导出数据" doc="表头加粗 + 冻结首行 —— 数据一多就必须能边滚边看表头。">
      <n-button size="small" @click="excelRef?.exportRows(exportRows)">
        导出 2 行示例数据
      </n-button>
    </Variant>

    <Variant
      title="列定义决定校验强度"
      doc="required 只拦空值；更细的规则写在 validate 里，返回字符串即错误原因。v-model 与业务字段名一致（key），因此同一份列定义也能直接喂给 ProTable。"
    >
      <n-alert type="default" title="本变体使用的规则">
        账号/姓名为必填；邮箱需符合格式；手机号需为 11 位数字。
        这些规则也会作用于「下载模板」得到的文件导入时。
      </n-alert>
    </Variant>

    <Variant
      title="只导出（隐藏内置按钮组）"
      doc="showActions=false 时隐藏内置按钮，只用 ref 暴露的方法 —— 适合把动作放在页面自己的工具栏里。"
    >
      <n-space vertical :size="12">
        <ProExcel ref="excelRef" :columns="columns" :show-actions="false" />
        <n-space :size="8">
          <n-button size="small" @click="excelRef?.downloadTemplate()">下载模板</n-button>
          <n-button size="small" @click="excelRef?.exportRows(exportRows)">导出数据</n-button>
        </n-space>
      </n-space>
    </Variant>
  </Story>
</template>
