package com.automation.util;

import com.automation.config.Config;
import com.automation.config.Environment;
import com.automation.excel.ExcelReader;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TemplateGenerator {
    public static void main(String[] args) {
        String environment = "local";
        String excelFile = "src/test/resources/testdata/test-data.xlsx";
        String sheetName = "TestCases";
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--env" -> {
                    if (i + 1 < args.length) environment = args[++i];
                }
                case "--file" -> {
                    if (i + 1 < args.length) excelFile = args[++i];
                }
                case "--sheet" -> {
                    if (i + 1 < args.length) sheetName = args[++i];
                }
            }
        }
        
        Config config = Config.getInstance();
        config.setEnvironment(Environment.fromString(environment));
        config.setExcelFilePath(excelFile);
        config.setSheetName(sheetName);
        
        ExcelReader reader = new ExcelReader(config);
        try {
            reader.createTemplateIfNotExists();
            log.info("Template generated successfully at: {}", excelFile);
        } catch (Exception e) {
            log.error("Failed to generate template: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}