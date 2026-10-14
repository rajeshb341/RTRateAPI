package com.automation.rater;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the output Excel: one row per test case and output attribute, plus a per-case summary sheet. */
@Slf4j
public final class RaterReportWriter {
    public static final String PASS = "Pass";
    public static final String FAIL = "Fail";

    private RaterReportWriter() {}

    public record Line(String testCaseId, String attributePath, String expected, String actual, String result, String remarks) {}

    public static void write(Path file, List<Line> lines) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle header = style(wb, null, true);
            CellStyle pass = style(wb, IndexedColors.LIGHT_GREEN, false);
            CellStyle fail = style(wb, IndexedColors.ROSE, false);
            CellStyle plain = style(wb, null, false);

            Sheet results = wb.createSheet("Results");
            String[] titles = {"Test Case ID", "Attribute Path (D/E/F)", "Expected (Rater)", "Actual (API)", "Result", "Remarks"};
            Row head = results.createRow(0);
            for (int c = 0; c < titles.length; c++) cell(head, c, titles[c], header);

            int r = 1;
            for (Line l : lines) {
                Row row = results.createRow(r++);
                cell(row, 0, l.testCaseId(), plain);
                cell(row, 1, l.attributePath(), plain);
                cell(row, 2, l.expected(), plain);
                cell(row, 3, l.actual(), plain);
                cell(row, 4, l.result(), PASS.equals(l.result()) ? pass : fail);
                cell(row, 5, l.remarks(), plain);
            }
            results.createFreezePane(0, 1);
            results.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, r - 1), 0, titles.length - 1));
            for (int c = 0; c < titles.length; c++) results.autoSizeColumn(c);

            Sheet summary = wb.createSheet("Summary");
            Row sh = summary.createRow(0);
            String[] st = {"Test Case ID", "Attributes", "Pass", "Fail", "Result"};
            for (int c = 0; c < st.length; c++) cell(sh, c, st[c], header);
            Map<String, int[]> perCase = new LinkedHashMap<>();
            for (Line l : lines) {
                int[] counts = perCase.computeIfAbsent(l.testCaseId(), k -> new int[2]);
                counts[PASS.equals(l.result()) ? 0 : 1]++;
            }
            int s = 1;
            int totalPass = 0, totalFail = 0;
            for (Map.Entry<String, int[]> e : perCase.entrySet()) {
                int p = e.getValue()[0], f = e.getValue()[1];
                Row row = summary.createRow(s++);
                cell(row, 0, e.getKey(), plain);
                cell(row, 1, String.valueOf(p + f), plain);
                cell(row, 2, String.valueOf(p), plain);
                cell(row, 3, String.valueOf(f), plain);
                cell(row, 4, f == 0 ? PASS : FAIL, f == 0 ? pass : fail);
                totalPass += p;
                totalFail += f;
            }
            Row total = summary.createRow(s);
            cell(total, 0, "TOTAL", header);
            cell(total, 1, String.valueOf(totalPass + totalFail), header);
            cell(total, 2, String.valueOf(totalPass), header);
            cell(total, 3, String.valueOf(totalFail), header);
            cell(total, 4, totalFail == 0 ? PASS : FAIL, header);
            for (int c = 0; c < st.length; c++) summary.autoSizeColumn(c);

            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        log.info("Report written to {}", file.toAbsolutePath());
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? "" : value);
        c.setCellStyle(style);
    }

    private static CellStyle style(Workbook wb, IndexedColors fill, boolean bold) {
        CellStyle s = wb.createCellStyle();
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        if (fill != null) {
            s.setFillForegroundColor(fill.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (bold) {
            Font f = wb.createFont();
            f.setBold(true);
            s.setFont(f);
        }
        return s;
    }
}
