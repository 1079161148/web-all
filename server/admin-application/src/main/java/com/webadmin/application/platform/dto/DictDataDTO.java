package com.webadmin.application.platform.dto;

/**
 * 字典项读模型。
 *
 * <h3>前端 {@code useDict} 的契约就是它</h3>
 * 前端拿到 {@code dictLabel / dictValue / cssClass} 即可完成
 * "下拉选项"与"表格字典标签"两种渲染，无需再请求第二次。
 *
 * <p>因此这三个字段的稳定性比其他字段更重要 ——
 * 改动它们会同时影响所有使用字典的页面。
 */
public record DictDataDTO(
        Long id,
        String dictType,
        /** 展示值。 */
        String dictLabel,
        /** 存储值（回传后端的就是它）。 */
        String dictValue,
        Integer sort,
        /** 标签样式：success / warning / error / info / default。前端 DictTag 直接映射成颜色。 */
        String cssClass,
        String listClass,
        Boolean isDefault,
        String status,
        String remark,
        Long sourceTenantId
) {
}
