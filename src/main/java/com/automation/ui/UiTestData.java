package com.automation.ui;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The UI-only fields of each iteration: the "UI:&lt;field&gt;" columns of the TestData sheet (the same row the API request
 * is built from), e.g. UI:OfficeName, UI:Agency_name, UI:OccupationType. Keyed by TestCaseId; the "UI:" prefix is dropped.
 * The fields the UI shares with the API (address, policy form, Coverage A ...) come from the API request instead
 * (config/ui-input-mapping.properties). Cells are read as shown in Excel (text).
 */
public final class UiTestData {
    public static final String SHEET = "TestData";
    public static final String PREFIX = "UI:";

    private final Map<String, Map<String, String>> rows;

    private UiTestData(Map<String, Map<String, String>> rows) {
        this.rows = rows;
    }

    /** Reads the UI: columns (from a temporary copy, so the workbook may stay open in Excel). */
    public static UiTestData read(Path workbook) throws IOException {
        Path copy = Files.createTempFile("ui-test-data", ".xlsx");
        try {
            Files.copy(workbook, copy, StandardCopyOption.REPLACE_EXISTING);
            try (InputStream in = Files.newInputStream(copy); Workbook wb = WorkbookFactory.create(in)) {
                Sheet sheet = wb.getSheet(SHEET);
                if (sheet == null) throw new IOException("Sheet '" + SHEET + "' not found in " + workbook);
                DataFormatter text = new DataFormatter();
                Row header = sheet.getRow(0);
                Map<Integer, String> uiColumns = new LinkedHashMap<>();
                for (int c = 1; c < header.getLastCellNum(); c++) {
                    Cell cell = header.getCell(c);
                    String name = cell == null ? "" : text.formatCellValue(cell).trim();
                    if (name.startsWith(PREFIX)) uiColumns.put(c, name.substring(PREFIX.length()).trim());
                }
                Map<String, Map<String, String>> rows = new LinkedHashMap<>();
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null || row.getCell(0) == null) continue;
                    String id = text.formatCellValue(row.getCell(0)).trim();
                    if (id.isEmpty()) continue;
                    Map<String, String> values = new LinkedHashMap<>();
                    uiColumns.forEach((c, name) -> values.put(name, text.formatCellValue(row.getCell(c)).trim()));
                    rows.put(id, Collections.unmodifiableMap(values));
                }
                return new UiTestData(rows);
            }
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    /** The UI: values of a TestCaseId (empty when the row has none). */
    public Map<String, String> row(String testCaseId) {
        return rows.getOrDefault(testCaseId, Map.of());
    }
}
