package com.webadmin.infrastructure.excel;

import com.webadmin.application.tool.port.ExcelPort;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 端口的 POI 实现。
 *
 * <h3>单元格一律按字符串读取（DataFormatter）</h3>
 * Excel 的"2026/9/19"在文件里是数字 46239 + 显示格式。用
 * {@code getNumericCellValue()} 会拿到 46239，用 {@code getDateCellValue()}
 * 又依赖本地时区。{@link DataFormatter} 返回<b>用户在 Excel 里看到的文本</b>
 * —— 与"模板下载 → 用户填写"的往返语义一致，日期校验交给应用层按
 * 业务格式解析。
 *
 * <h3>行列边界</h3>
 * 遍历用 {@code sheet.getLastRowNum()} 与 {@code row.getLastCellNum()}：
 * 用户常在中间留空行/拖动列，按"存在即处理"的容错策略，
 * 缺失的单元格补空串（校验层按"必填缺失"报错，而不是这里抛异常）。
 */
@Component
public class PoiExcelAdapter implements ExcelPort {

    private static final DataFormatter FORMATTER = new DataFormatter();

    @Override
    public Sheet parse(byte[] content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var sheet = workbook.getSheetAt(0);
            int headerRowNum = sheet.getFirstRowNum();
            Row headerRow = sheet.getRow(headerRowNum);
            if (headerRow == null || headerRow.getLastCellNum() <= 0) {
                throw new IllegalArgumentException("Excel 首行（表头）为空");
            }

            List<String> headers = new ArrayList<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                headers.add(cellText(headerRow.getCell(c)));
            }

            List<List<String>> rows = new ArrayList<>();
            for (int r = headerRowNum + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    // 空行保留为空行：校验层按"整行必填缺失"给出明确的行级错误
                    List<String> empty = new ArrayList<>();
                    for (int c = 0; c < headers.size(); c++) {
                        empty.add("");
                    }
                    rows.add(empty);
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (int c = 0; c < headers.size(); c++) {
                    values.add(cellText(row.getCell(c)));
                }
                rows.add(values);
            }
            return new Sheet(headers, rows);
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法解析 Excel 文件：" + ex.getMessage(), ex);
        }
    }

    @Override
    public byte[] write(String sheetName, List<String> headers, List<List<String>> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                header.createCell(c).setCellValue(headers.get(c));
                sheet.setColumnWidth(c, 18 * 256);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(values.get(c) == null ? "" : values.get(c));
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("生成 Excel 失败：" + ex.getMessage(), ex);
        }
    }

    /** 单元格 → 显示文本（公式取缓存值，数字按显示格式）。 */
    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                return FORMATTER.formatCellValue(cell)
                        .isEmpty() ? "" : String.valueOf(cell.getNumericCellValue());
            } catch (Exception ex) {
                return "";
            }
        }
        return FORMATTER.formatCellValue(cell).trim();
    }
}
