/**
 * Naive UI 基础元素「透传再导出」。
 *
 * <h3>解决一个真实的设计张力</h3>
 * 设计文档里有两条款看似冲突的约束：
 * <ol>
 *   <li>§11.2：`apps/**` 只能从 `@admin/ui` 引入，不得直接 import `naive-ui`（可替换性）</li>
 *   <li>§11.3：按钮 / 输入框 / 下拉等基础元素<b>不封装</b>，直接透传（避免过度封装）</li>
 * </ol>
 *
 * <p>如果基础元素要"不封装"又要"不从 naive-ui 引入"，答案就是本文件：
 * <b>原样 re-export，零包装代码</b>。既满足隔离（业务代码编译器里看不到 naive-ui），
 * 又不产生任何封装负担（没有任何 props 转换、没有中间组件、没有类型损失）。
 *
 * <h3>与「Pro 组件」的区别</h3>
 * <ul>
 *   <li>本文件 = 基础元素，<b>透传</b>（唯一改动是入口路径）</li>
 *   <li>`components/pro/*` = 重能力组件，<b>加约定</b>（搜索区、分页契约、状态矩阵…）</li>
 * </ul>
 * 判断标准：如果这里出现了任何逻辑，就说明放错地方了。
 */

// ---- 基础元素（透传） ----
export {
  NAlert,
  NBreadcrumb,
  NBreadcrumbItem,
  NButton,
  NCard,
  NCheckbox,
  NCheckboxGroup,
  NCollapse,
  NConfigProvider,
  NDatePicker,
  NDivider,
  NDrawer,
  NDrawerContent,
  NDropdown,
  NEmpty,
  NForm,
  NFormItem,
  NGlobalStyle,
  NGrid,
  NGridItem,
  NIcon,
  NInput,
  NInputNumber,
  NLayout,
  NLayoutContent,
  NLayoutHeader,
  NLayoutSider,
  NDescriptions,
  NDescriptionsItem,
  NImage,
  NMenu,
  NModal,
  NPagination,
  NPopconfirm,
  NPopover,
  NProgress,
  NRadio,
  NRadioButton,
  NRadioGroup,
  NResult,
  // ⚠️ NRow / NCol 不出现在任何 <template> 里，check:naive 门禁因此扫不到它们 ——
  // 但 form-create（ProForm 的内核）在渲染 col 栅格配置时按名字在全局解析
  // "NRow"/"NCol"，漏注册的表现是控制台刷
  // "Failed to resolve component: nRow / nCol"，且多列表单静默降级为单列堆叠。
  NRow,
  NCol,
  NScrollbar,
  NSelect,
  NSkeleton,
  NSpace,
  NSpin,
  NSwitch,
  NTab,
  NTabPane,
  NTabs,
  NTag,
  NText,
  NTimePicker,
  NTimeline,
  NTimelineItem,
  NTooltip,
  NTree,
  NTreeSelect,
  NUpload,
  NUploadDragger,
  darkTheme,
  dateZhCN,
  zhCN
} from 'naive-ui'

export type {
  FormInst,
  FormItemRule,
  GlobalTheme,
  GlobalThemeOverrides,
  MenuOption,
  SelectOption,
  TreeOption,
  UploadFileInfo
} from 'naive-ui'
