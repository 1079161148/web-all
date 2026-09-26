package com.webadmin.application.tool.port;

import java.util.List;

/**
 * Excel 读写端口（数据导入导出）。
 *
 * <h3>为什么是端口而不是直接用 POI</h3>
 * POI 是重依赖且属于实现细节；application 层只关心"字节 → 行数据"、
 * "行数据 → 字节"。这样：
 * <ul>
 *   <li>Excel 的版本兼容（xls/xlsx）、流式读取（大文件 SXSSF）等技术选择
 *       都可以换，业务逻辑不动</li>
 *   <li>架构门禁可约束 POI 只出现在 infrastructure（见 pom 注释）</li>
 * </ul>
 *
 * <p>行列统一用字符串交换：类型解释（日期/枚举/数字）是<b>业务规则</b>，
 * 归应用层；适配器只负责把单元格值无损转成字符串。
 */
public interface ExcelPort {

    /** 解析结果：表头 + 数据行（保持原始顺序与原始字符串值）。 */
    record Sheet(List<String> headers, List<List<String>> rows) {
    }

    /**
     * 解析第一个工作表。
     *
     * @throws IllegalArgumentException 文件不是可识别的 Excel / 结构为空
     */
    Sheet parse(byte[] content);

    /** 生成 xlsx（自动列宽；全字符串单元格）。 */
    byte[] write(String sheetName, List<String> headers, List<List<String>> rows);
}
