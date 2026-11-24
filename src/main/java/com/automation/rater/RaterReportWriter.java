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
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Writes the output Excel: one row per test case and output attribute (Results), a per-case summary (Summary) and a
 * per-configuration summary (Configurations).
 * Results: Pass / Fail; N/A = the Rater marks the attribute as not applicable to the configuration (not compared);
 * Skipped = the test case was not run (unsupported configuration or invalid test data).
 */
@Slf4j
public final class RaterReportWriter {
    public static final String PASS = "Pass";
    public static final String FAIL = "Fail";
    public static final String NOT_APPLICABLE = "N/A";
    public static final String SKIPPED = "Skipped";

    private RaterReportWriter() {}

    public record Line(String testCaseId, String attributePath, String expected, String actual, String result, String remarks,
                       String configuration) {
        public Line(String testCaseId, String attributePath, String expected, String actual, String result, String remarks) {
            this(testCaseId, attributePath, expected, actual, result, remarks, "");
        }
    }

    /** Pass / Fail / N/A / Skipped counts. */
    private static final class Counts {
        int pass, fail, notApplicable, skipped;

        void add(String result) {
            switch (result) {
                case PASS -> pass++;
                case NOT_APPLICABLE -> notApplicable++;
                case SKIPPED -> skipped++;
                default -> fail++;
            }
        }

        String result() {
            if (fail > 0) return FAIL;
            if (pass == 0 && notApplicable == 0 && skipped > 0) return SKIPPED;
            return PASS;
        }
    }

    public static void write(Path file, List<Line> lines) throws IOException {
        write(file, lines, "Attribute Path (D/E/F)", "Expected (Rater)", "Actual (API)");
    }

    /** As {@link #write(Path, List)} with other titles for the attribute, Expected and Actual columns (e.g. API vs UI). */
    public static void write(Path file, List<Line> lines, String attributeTitle, String expectedTitle, String actualTitle)
        throws IOException {
        // Streaming workbook: rows go to a temporary file instead of memory (a full run has over 500,000 rows); repeated
        // texts share one string table, which keeps the file small
        SXSSFWorkbook streaming = new SXSSFWorkbook(null, 1000, true, true);
        try (Workbook wb = streaming) {
            CellStyle header = style(wb, null, true);
            CellStyle pass = style(wb, IndexedColors.LIGHT_GREEN, false);
            CellStyle fail = style(wb, IndexedColors.ROSE, false);
            CellStyle neutral = style(wb, IndexedColors.GREY_25_PERCENT, false);
            CellStyle plain = style(wb, null, false);

            Sheet results = wb.createSheet("Results");
            String[] titles = {"Test Case ID", attributeTitle, expectedTitle, actualTitle, "Result", "Remarks", "Configuration"};
            Row head = results.createRow(0);
            for (int c = 0; c < titles.length; c++) cell(head, c, titles[c], header);

            int r = 1;
            for (Line l : lines) {
                Row row = results.createRow(r++);
                cell(row, 0, l.testCaseId(), plain);
                cell(row, 1, l.attributePath(), plain);
                cell(row, 2, l.expected(), plain);
                cell(row, 3, l.actual(), plain);
                cell(row, 4, l.result(), resultStyle(l.result(), pass, fail, neutral));
                cell(row, 5, l.remarks(), plain);
                cell(row, 6, l.configuration(), plain);
            }
            results.createFreezePane(0, 1);
            results.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(1, r - 1), 0, titles.length - 1));
            fitColumns(results);

            Sheet summary = wb.createSheet("Summary");
            Row sh = summary.createRow(0);
            String[] st = {"Test Case ID", "Attributes", "Pass", "Fail", "Result", "N/A", "Configuration"};
            for (int c = 0; c < st.length; c++) cell(sh, c, st[c], header);
            Map<String, Counts> perCase = new LinkedHashMap<>();
            Map<String, String> caseConfiguration = new LinkedHashMap<>();
            for (Line l : lines) {
                perCase.computeIfAbsent(l.testCaseId(), k -> new Counts()).add(l.result());
                caseConfiguration.putIfAbsent(l.testCaseId(), l.configuration());
            }
            int s = 1;
            Counts total = new Counts();
            for (Map.Entry<String, Counts> e : perCase.entrySet()) {
                Counts c = e.getValue();
                Row row = summary.createRow(s++);
                cell(row, 0, e.getKey(), plain);
                cell(row, 1, String.valueOf(c.pass + c.fail + c.notApplicable), plain);
                cell(row, 2, String.valueOf(c.pass), plain);
                cell(row, 3, String.valueOf(c.fail), plain);
                cell(row, 4, c.result(), resultStyle(c.result(), pass, fail, neutral));
                cell(row, 5, String.valueOf(c.notApplicable), plain);
                cell(row, 6, caseConfiguration.get(e.getKey()), plain);
                total.pass += c.pass;
                total.fail += c.fail;
                total.notApplicable += c.notApplicable;
            }
            Row totalRow = summary.createRow(s);
            cell(totalRow, 0, "TOTAL", header);
            cell(totalRow, 1, String.valueOf(total.pass + total.fail + total.notApplicable), header);
            cell(totalRow, 2, String.valueOf(total.pass), header);
            cell(totalRow, 3, String.valueOf(total.fail), header);
            cell(totalRow, 4, total.fail == 0 ? PASS : FAIL, header);
            cell(totalRow, 5, String.valueOf(total.notApplicable), header);
            cell(totalRow, 6, "", header);
            fitColumns(summary);

            writeConfigurations(wb.createSheet("Configurations"), perCase, caseConfiguration, header, plain, pass, fail, neutral);

            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        } finally {
            streaming.dispose();      // deletes the temporary files
        }
        log.info("Report written to {}", file.toAbsolutePath());
    }

    /** One row per configuration: how many test cases ran and how they ended. */
    private static void writeConfigurations(Sheet sheet, Map<String, Counts> perCase, Map<String, String> caseConfiguration,
                                            CellStyle header, CellStyle plain, CellStyle pass, CellStyle fail, CellStyle neutral) {
        String[] titles = {"Configuration", "Test Cases", "Passed", "Failed", "Skipped", "Result"};
        Row head = sheet.createRow(0);
        for (int c = 0; c < titles.length; c++) cell(head, c, titles[c], header);
        Set<String> configurations = new LinkedHashSet<>(caseConfiguration.values());
        int r = 1;
        for (String configuration : configurations) {
            Counts cases = new Counts();
            caseConfiguration.forEach((id, cfg) -> {
                if (cfg.equals(configuration)) cases.add(perCase.get(id).result());
            });
            Row row = sheet.createRow(r++);
            cell(row, 0, configuration.isEmpty() ? "(none)" : configuration, plain);
            cell(row, 1, String.valueOf(cases.pass + cases.fail + cases.skipped), plain);
            cell(row, 2, String.valueOf(cases.pass), plain);
            cell(row, 3, String.valueOf(cases.fail), plain);
            cell(row, 4, String.valueOf(cases.skipped), plain);
            cell(row, 5, cases.result(), resultStyle(cases.result(), pass, fail, neutral));
        }
        fitColumns(sheet);
    }

    private static CellStyle resultStyle(String result, CellStyle pass, CellStyle fail, CellStyle neutral) {
        if (PASS.equals(result)) return pass;
        if (FAIL.equals(result)) return fail;
        return neutral;
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        String text = value == null ? "" : value;
        c.setCellValue(text);
        c.setCellStyle(style);
        int[] widths = WIDTHS.computeIfAbsent(row.getSheet(), s -> new int[32]);
        if (col < widths.length) widths[col] = Math.max(widths[col], text.length());
    }

    /** Longest text per column of each sheet being written, for the column widths (autoSizeColumn is too slow for 500,000 rows). */
    private static final Map<Sheet, int[]> WIDTHS = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** Sets each column wide enough for its longest text (at most 100 characters). */
    private static void fitColumns(Sheet sheet) {
        int[] widths = WIDTHS.remove(sheet);
        if (widths == null) return;
        for (int c = 0; c < widths.length; c++) {
            if (widths[c] > 0) sheet.setColumnWidth(c, Math.min(widths[c] + 3, 100) * 256);
        }
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
