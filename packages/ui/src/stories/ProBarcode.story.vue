<script setup lang="ts">
import { ref } from 'vue'
import ProBarcode from '../components/pro/ProBarcode.vue'

/**
 * ProBarcode 的 story。
 *
 * <p>重点展示**码制约束**：不同码制对内容长度与字符集有硬要求，
 * 输入不合法时必须给出可读提示（而不是空白或控制台报错）——
 * 这是用户"边输边试"时的真实路径。
 */

/** CODE128：可编码任意 ASCII，最通用。 */
const assetCode = ref('ZD-2026-0926-0001')
/** EAN13：必须 12~13 位数字。 */
const ean = ref('690123456789')
/** 非法内容：故意用字母喂给 EAN13，展示错误提示。 */
const invalidEan = ref('ABC123456789')
</script>

<template>
  <Story title="Pro 组件/ProBarcode">
    <Variant title="CODE128（默认，通用）">
      <ProBarcode :value="assetCode" />
    </Variant>

    <Variant title="EAN13 + 可下载 SVG" doc="导出矢量原件：条码有物理精度要求，矢量件打印不失真">
      <ProBarcode :value="ean" format="EAN13" downloadable download-name="ean13" />
    </Variant>

    <Variant title="紧凑样式" doc="窄条 + 矮高，适合表格行内展示">
      <ProBarcode
        :value="assetCode"
        :width="1.4"
        :height="40"
        :font-size="11"
        :margin="4"
      />
    </Variant>

    <Variant title="非法内容提示" doc="EAN13 不接受字母：组件给出提示而不是静默失败">
      <ProBarcode :value="invalidEan" format="EAN13" />
    </Variant>

    <Variant title="空内容" doc="不渲染任何条，也不报错">
      <ProBarcode value="" />
    </Variant>
  </Story>
</template>
