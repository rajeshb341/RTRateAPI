package com.automation.rater;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The TestData workbook: one row per test case, one column per input attribute.
 * Column headers look like "15:Insured/Location/State" - the number before the colon is the Rater row.
 */
@Slf4j
public final class RaterTestData {
    private static final String ID_HEADER = "TestCaseId";

    private RaterTestData() {}

    /** Builds test cases from the dropdown values of the Rater inputs. Case 1 uses the Rater's current values. */
    public static List<RaterTestCase> generate(RaterSettings settings, List<RaterAttribute> attributes, int count, Long seed) {
        Random random = seed == null ? new Random() : new Random(seed);
        List<RaterAttribute> inputs = attributes.stream().filter(a -> "INPUT".equals(a.getRole())).toList();
        List<RaterTestCase> cases = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            RaterTestCase tc = new RaterTestCase(String.format("TC%03d", i));
            for (RaterAttribute attr : inputs) {
                String value = i == 1 ? attr.currentText() : pick(settings, attr, tc, random);
                tc.inputs().put(attr.getRow(), value);
            }
            cases.add(tc);
        }
        return cases;
    }

    private static String pick(RaterSettings settings, RaterAttribute attr, RaterTestCase tc, Random random) {
        List<String> candidates = new ArrayList<>(settings.overrideValues(attr.getRow()));
        if (candidates.isEmpty() && attr.getDependsOnRow() != null) {
            candidates = attr.getValuesByParent().getOrDefault(tc.inputs().get(attr.getDependsOnRow()), List.of());
        }
        if (candidates.isEmpty()) {
            candidates = attr.getValues();
        }
        return candidates.isEmpty() ? attr.currentText() : candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * Writes a new test data workbook. An existing file is never overwritten (it holds the user's edited cases):
     * the generated data goes to "&lt;name&gt;_generated_&lt;timestamp&gt;.xlsx" next to it instead.
     * The file is written to a temp file first, so a failed write can never leave an empty workbook behind.
     */
    public static Path write(Path file, String sheetName, List<RaterAttribute> attributes, List<RaterTestCase> cases) throws IOException {
        if (Files.exists(file) && Files.size(file) > 0) {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path target = file.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + "_generated_" + stamp
                + (dot > 0 ? name.substring(dot) : ""));
            log.warn("{} already exists and is NOT overwritten; generated data goes to {}", file, target);
            file = target;
        }
        List<RaterAttribute> inputs = attributes.stream().filter(a -> "INPUT".equals(a.getRole())).toList();
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue(ID_HEADER);
            for (int c = 0; c < inputs.size(); c++) {
                header.createCell(c + 1).setCellValue(inputs.get(c).getRow() + ":" + inputs.get(c).path());
            }
            header.forEach(cell -> cell.setCellStyle(bold));

            for (int r = 0; r < cases.size(); r++) {
                Row row = sheet.createRow(r + 1);
                row.createCell(0).setCellValue(cases.get(r).id());
                for (int c = 0; c < inputs.size(); c++) {
                    row.createCell(c + 1).setCellValue(cases.get(r).inputs().getOrDefault(inputs.get(c).getRow(), ""));
                }
            }
            sheet.createFreezePane(1, 1);
            for (int c = 0; c <= inputs.size(); c++) sheet.autoSizeColumn(c);

            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                wb.write(out);
            }
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Test data with {} case(s) written to {}", cases.size(), file);
        return file;
    }

    public static List<RaterTestCase> read(Path file, String sheetName) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Test data file not found: " + file + " (create it with --generate)");
        }
        // Read-only: a run never writes to this file. Copy first so a file that is open in Excel/WPS can still be read.
        Path snapshot = Files.createTempFile("test-data-", ".xlsx");
        try {
            Files.copy(file, snapshot, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IOException("Cannot read " + file + " - close it in Excel/WPS and retry (" + e.getMessage() + ")", e);
        }
        try (InputStream in = Files.newInputStream(snapshot); Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheet(sheetName);
            if (sheet == null) throw new IOException("Sheet '" + sheetName + "' not found in " + file);

            Row header = sheet.getRow(0);
            Map<Integer, Integer> rowByColumn = new LinkedHashMap<>();
            for (int c = 1; c < header.getLastCellNum(); c++) {
                Cell cell = header.getCell(c);
                String text = cell == null ? "" : text(cell);
                int colon = text.indexOf(':');
                if (colon > 0) rowByColumn.put(c, Integer.parseInt(text.substring(0, colon).trim()));
            }

            List<RaterTestCase> cases = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || row.getCell(0) == null || text(row.getCell(0)).isEmpty()) continue;
                RaterTestCase tc = new RaterTestCase(text(row.getCell(0)));
                rowByColumn.forEach((col, raterRow) -> tc.inputs().put(raterRow, text(row.getCell(col))));
                cases.add(tc);
            }
            log.info("Read {} test case(s) from {}", cases.size(), file);
            return cases;
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    private static String text(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            // Excel/WPS may turn "06/21/2026" into a real date; the Rater and the API expect MM/dd/yyyy text
            case NUMERIC -> org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)
                ? new java.text.SimpleDateFormat("MM/dd/yyyy").format(cell.getDateCellValue())
                : JsonText.number(BigDecimal.valueOf(cell.getNumericCellValue()));
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case STRING -> cell.getStringCellValue().trim();
            case FORMULA -> cell.getCachedFormulaResultType() == CellType.NUMERIC
                ? JsonText.number(BigDecimal.valueOf(cell.getNumericCellValue())) : cell.getStringCellValue().trim();
            default -> "";
        };
    }
}
