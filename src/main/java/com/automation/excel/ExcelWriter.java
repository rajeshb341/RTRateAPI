package com.automation.excel;

import com.automation.config.Config;
import com.automation.config.ExcelColumns;
import com.automation.config.TestCaseData;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Slf4j
public class ExcelWriter {
    private final Config config;
    private Workbook workbook;
    private Sheet sheet;

    public ExcelWriter() {
        this.config = Config.getInstance();
    }

    public ExcelWriter(Config config) {
        this.config = config;
    }

    public void writeResults(List<TestCaseData> testCases) throws IOException {
        if (testCases == null || testCases.isEmpty()) {
            log.warn("No test cases to write results for");
            return;
        }

        openWorkbookForWriting();
        try {
            for (TestCaseData testCase : testCases) {
                // Not executed in this run: leave any previous results in the sheet alone
                if (testCase.getOverallStatus() == null || testCase.getOverallStatus().isEmpty()) {
                    continue;
                }
                Row row = sheet.getRow(testCase.getRowNumber());
                if (row == null) {
                    row = sheet.createRow(testCase.getRowNumber());
                }
                writeTestCaseResult(testCase, row);
            }
            
            saveWorkbook();
            log.info("Results written to Excel: {} test cases updated", testCases.size());
        } finally {
            closeWorkbook();
        }
    }

    public void writeTestCaseResult(TestCaseData testCase) throws IOException {
        openWorkbookForWriting();
        try {
            int rowNum = testCase.getRowNumber();
            Row row = sheet.getRow(rowNum);
            
            if (row == null) {
                row = sheet.createRow(rowNum);
            }

            writeCell(row, ExcelColumns.ACTUAL_STATUS_CODE, testCase.getActualStatusCode());
            writeCell(row, ExcelColumns.ACTUAL_RESPONSE_BODY, testCase.getResponseBody());
            writeCell(row, ExcelColumns.ACTUAL_VALUES, formatMap(testCase.getActualValues()));
            writeCell(row, ExcelColumns.PASS_FAIL_STATUS, formatMap(testCase.getPassFailStatus()));
            writeCell(row, ExcelColumns.OVERALL_STATUS, testCase.getOverallStatus());
            writeCell(row, ExcelColumns.ERROR_MESSAGE, testCase.getErrorMessage());
            writeCell(row, ExcelColumns.EXECUTION_TIME_MS, testCase.getExecutionTimeMs());

            applyStatusStyle(row, ExcelColumns.OVERALL_STATUS, testCase.getOverallStatus());
            
            saveWorkbook();
        } finally {
            closeWorkbook();
        }
    }

    private void writeTestCaseResult(TestCaseData testCase, Row row) {
        writeCell(row, ExcelColumns.ACTUAL_STATUS_CODE, testCase.getActualStatusCode());
        writeCell(row, ExcelColumns.ACTUAL_RESPONSE_BODY, testCase.getResponseBody());
        writeCell(row, ExcelColumns.ACTUAL_VALUES, formatMap(testCase.getActualValues()));
        writeCell(row, ExcelColumns.PASS_FAIL_STATUS, formatMap(testCase.getPassFailStatus()));
        writeCell(row, ExcelColumns.OVERALL_STATUS, testCase.getOverallStatus());
        writeCell(row, ExcelColumns.ERROR_MESSAGE, testCase.getErrorMessage());
        writeCell(row, ExcelColumns.EXECUTION_TIME_MS, testCase.getExecutionTimeMs());

        applyStatusStyle(row, ExcelColumns.OVERALL_STATUS, testCase.getOverallStatus());
    }

    private void writeCell(Row row, int columnIndex, Object value) {
        Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        
        if (value == null) {
            cell.setBlank();
            return;
        }

        if (value instanceof Integer) {
            cell.setCellValue((Integer) value);
        } else if (value instanceof Long) {
            cell.setCellValue((Long) value);
        } else if (value instanceof Double) {
            cell.setCellValue((Double) value);
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value);
        } else {
            String text = value.toString();
            // Excel cells hold at most 32,767 characters
            if (text.length() > 32000) {
                text = text.substring(0, 32000) + "...[truncated]";
            }
            cell.setCellValue(text);
        }
    }

    private String formatMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return sb.toString();
    }

    private void applyStatusStyle(Row row, int columnIndex, String status) {
        Cell cell = row.getCell(columnIndex);
        if (cell == null) return;

        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        
        if ("PASS".equalsIgnoreCase(status)) {
            font.setColor(IndexedColors.DARK_GREEN.getIndex());
            style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        } else if ("FAIL".equalsIgnoreCase(status)) {
            font.setColor(IndexedColors.RED.getIndex());
            style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        } else if ("ERROR".equalsIgnoreCase(status)) {
            font.setColor(IndexedColors.RED.getIndex());
            style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
        } else {
            font.setColor(IndexedColors.BLACK.getIndex());
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        }
        
        style.setFont(font);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        cell.setCellStyle(style);
    }

    private void openWorkbookForWriting() throws IOException {
        Path filePath = Paths.get(config.getExcelFilePath());
        
        if (!Files.exists(filePath)) {
            throw new IOException("Excel file not found: " + filePath.toAbsolutePath());
        }

        try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
            workbook = new XSSFWorkbook(fis);
            sheet = workbook.getSheet(config.getSheetName());
            
            if (sheet == null) {
                throw new IOException("Sheet '" + config.getSheetName() + "' not found in workbook");
            }
        }
    }

    private void saveWorkbook() throws IOException {
        Path filePath = Paths.get(config.getExcelFilePath());
        // Serialise to memory first so a failed write can never truncate the file
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        workbook.write(buffer);
        try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
            buffer.writeTo(fos);
        }
    }

    private void closeWorkbook() {
        if (workbook != null) {
            try {
                workbook.close();
            } catch (IOException e) {
                log.warn("Error closing workbook: {}", e.getMessage());
            }
        }
    }

    public void writeSingleResult(TestCaseData testCase) throws IOException {
        openWorkbookForWriting();
        try {
            int rowNum = testCase.getRowNumber();
            Row row = sheet.getRow(rowNum);
            
            if (row == null) {
                row = sheet.createRow(rowNum);
            }
            
            writeTestCaseResult(testCase, row);
            saveWorkbook();
            log.debug("Written result for test case: {}", testCase.getTestCaseId());
        } finally {
            closeWorkbook();
        }
    }
}