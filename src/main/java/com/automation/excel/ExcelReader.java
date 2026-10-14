package com.automation.excel;

import com.automation.config.Config;
import com.automation.config.ExcelColumns;
import com.automation.config.TestCaseData;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Slf4j
public class ExcelReader {
    private final Config config;
    private Workbook workbook;
    private Sheet sheet;

    public ExcelReader() {
        this.config = Config.getInstance();
    }

    public ExcelReader(Config config) {
        this.config = config;
    }

    public List<TestCaseData> readTestCases() throws IOException {
        List<TestCaseData> testCases = new ArrayList<>();
        
        openWorkbook();
        try {
            if (sheet == null) {
                log.warn("Sheet '{}' not found in workbook", config.getSheetName());
                return testCases;
            }

            int startRow = config.getStartRow();
            int endRow = config.getEndRow() > 0 ? config.getEndRow() : sheet.getLastRowNum();

            log.info("Reading test cases from row {} to {} (0-indexed)", startRow, endRow);

            for (int i = startRow; i <= endRow && i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isRowEmpty(row)) {
                    continue;
                }

                TestCaseData testCase = parseRow(row, i);
                if (testCase != null && testCase.isEnabled()) {
                    testCases.add(testCase);
                    log.debug("Loaded test case: {} - {}", testCase.getTestCaseId(), testCase.getTestCaseName());
                } else if (testCase != null) {
                    log.info("Skipping disabled test case: {}", testCase.getTestCaseId());
                }
            }

            log.info("Total enabled test cases loaded: {}", testCases.size());
        } finally {
            closeWorkbook();
        }

        return testCases;
    }

    public TestCaseData readTestCase(int rowNumber) throws IOException {
        openWorkbook();
        try {
            if (sheet == null) {
                return null;
            }

            Row row = sheet.getRow(rowNumber);
            if (row == null || isRowEmpty(row)) {
                return null;
            }

            return parseRow(row, rowNumber);
        } finally {
            closeWorkbook();
        }
    }

    private TestCaseData parseRow(Row row, int rowNumber) {
        try {
            return TestCaseData.builder()
                .rowNumber(rowNumber)
                .testCaseId(getCellValueAsString(row, ExcelColumns.TEST_CASE_ID))
                .testCaseName(getCellValueAsString(row, ExcelColumns.TEST_CASE_NAME))
                .description(getCellValueAsString(row, ExcelColumns.DESCRIPTION))
                .endpoint(getCellValueAsString(row, ExcelColumns.ENDPOINT))
                .httpMethod(getCellValueAsString(row, ExcelColumns.HTTP_METHOD))
                .headers(getCellValueAsString(row, ExcelColumns.HEADERS))
                .requestBody(getCellValueAsString(row, ExcelColumns.REQUEST_BODY))
                .queryParams(getCellValueAsString(row, ExcelColumns.QUERY_PARAMS))
                .pathParams(getCellValueAsString(row, ExcelColumns.PATH_PARAMS))
                .expectedStatusCode(getCellValueAsString(row, ExcelColumns.EXPECTED_STATUS_CODE))
                .expectedResponseBody(getCellValueAsString(row, ExcelColumns.EXPECTED_RESPONSE_BODY))
                .jsonPathExpressions(getCellValueAsString(row, ExcelColumns.JSON_PATH_EXPRESSIONS))
                .expectedValues(getCellValueAsString(row, ExcelColumns.EXPECTED_VALUES))
                .testData(getCellValueAsString(row, ExcelColumns.TEST_DATA))
                .enabled(getCellValueAsString(row, ExcelColumns.ENABLED))
                .priority(getCellValueAsString(row, ExcelColumns.PRIORITY))
                .category(getCellValueAsString(row, ExcelColumns.CATEGORY))
                .build();
        } catch (Exception e) {
            log.error("Error parsing row {}: {}", rowNumber, e.getMessage(), e);
            return null;
        }
    }

    private String getCellValueAsString(Row row, int columnIndex) {
        Cell cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return "";
        }

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue().toString();
                }
                double numericValue = cell.getNumericCellValue();
                if (numericValue == (long) numericValue) {
                    yield String.valueOf((long) numericValue);
                }
                yield String.valueOf(numericValue);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (IllegalStateException e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> "";
        };
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        
        for (int i = row.getFirstCellNum(); i <= row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String value = getCellValueAsString(row, i);
                if (!value.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private void openWorkbook() throws IOException {
        Path filePath = Paths.get(config.getExcelFilePath());
        
        if (!Files.exists(filePath)) {
            throw new IOException("Excel file not found: " + filePath.toAbsolutePath());
        }

        try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
            workbook = new XSSFWorkbook(fis);
            sheet = workbook.getSheet(config.getSheetName());
            
            if (sheet == null) {
                log.warn("Sheet '{}' not found. Available sheets: {}", 
                    config.getSheetName(), getSheetNames());
            }
        }
    }

    private List<String> getSheetNames() {
        List<String> names = new ArrayList<>();
        if (workbook != null) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                names.add(workbook.getSheetName(i));
            }
        }
        return names;
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

    public void createTemplateIfNotExists() throws IOException {
        Path filePath = Paths.get(config.getExcelFilePath());
        
        if (Files.exists(filePath)) {
            log.info("Excel file already exists: {}", filePath);
            return;
        }

        Files.createDirectories(filePath.getParent());
        
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet templateSheet = wb.createSheet(config.getSheetName());
            
            // Create header row
            Row headerRow = templateSheet.createRow(0);
            for (int i = 0; i < ExcelColumns.HEADER_NAMES.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(ExcelColumns.HEADER_NAMES[i]);
                
                // Style header
                CellStyle headerStyle = wb.createCellStyle();
                Font headerFont = wb.createFont();
                headerFont.setBold(true);
                headerStyle.setFont(headerFont);
                headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
                headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                cell.setCellStyle(headerStyle);
                
                // Auto-size column
                templateSheet.autoSizeColumn(i);
            }
            
            // Add sample data row
            Row sampleRow = templateSheet.createRow(1);
            sampleRow.createCell(ExcelColumns.TEST_CASE_ID).setCellValue("TC001");
            sampleRow.createCell(ExcelColumns.TEST_CASE_NAME).setCellValue("Get User By ID");
            sampleRow.createCell(ExcelColumns.DESCRIPTION).setCellValue("Retrieve user details by ID");
            sampleRow.createCell(ExcelColumns.ENDPOINT).setCellValue("/api/users/{userId}");
            sampleRow.createCell(ExcelColumns.HTTP_METHOD).setCellValue("GET");
            sampleRow.createCell(ExcelColumns.HEADERS).setCellValue("Content-Type:application/json\nAuthorization:Bearer {{authToken}}");
            sampleRow.createCell(ExcelColumns.REQUEST_BODY).setCellValue("");
            sampleRow.createCell(ExcelColumns.QUERY_PARAMS).setCellValue("includeDetails=true");
            sampleRow.createCell(ExcelColumns.PATH_PARAMS).setCellValue("userId=123");
            sampleRow.createCell(ExcelColumns.EXPECTED_STATUS_CODE).setCellValue("200");
            sampleRow.createCell(ExcelColumns.EXPECTED_RESPONSE_BODY).setCellValue("");
            sampleRow.createCell(ExcelColumns.JSON_PATH_EXPRESSIONS).setCellValue("$.id\n$.name\n$.email\n$.status");
            sampleRow.createCell(ExcelColumns.EXPECTED_VALUES).setCellValue("123\nJohn Doe\njohn.doe@example.com\nactive");
            sampleRow.createCell(ExcelColumns.TEST_DATA).setCellValue("");
            sampleRow.createCell(ExcelColumns.ENABLED).setCellValue("Y");
            sampleRow.createCell(ExcelColumns.PRIORITY).setCellValue("HIGH");
            sampleRow.createCell(ExcelColumns.CATEGORY).setCellValue("SMOKE");

            try (var fos = Files.newOutputStream(filePath)) {
                wb.write(fos);
            }
            
            log.info("Created template Excel file at: {}", filePath.toAbsolutePath());
        }
    }
}