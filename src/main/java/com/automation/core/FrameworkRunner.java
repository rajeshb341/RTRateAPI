package com.automation.core;

import com.automation.config.Config;
import com.automation.config.Environment;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FrameworkRunner {
    public static void main(String[] args) {
        log.info("=== API Automation Framework Runner ===");
        
        // Parse command line arguments
        String environment = "local";
        String excelFile = null;
        String sheetName = null;
        boolean help = false;
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--env", "-e" -> {
                    if (i + 1 < args.length) {
                        environment = args[++i];
                    }
                }
                case "--excel", "-f" -> {
                    if (i + 1 < args.length) {
                        excelFile = args[++i];
                    }
                }
                case "--sheet", "-s" -> {
                    if (i + 1 < args.length) {
                        sheetName = args[++i];
                    }
                }
                case "--help", "-h" -> help = true;
            }
        }
        
        if (help) {
            printUsage();
            return;
        }
        
        // Initialize config
        Config config = Config.getInstance();
        config.setEnvironment(Environment.fromString(environment));
        
        if (excelFile != null) {
            config.setExcelFilePath(excelFile);
        }
        if (sheetName != null) {
            config.setSheetName(sheetName);
        }
        
        log.info("Environment: {}", config.getEnvironment().getName());
        log.info("Base URL: {}", config.getBaseUrl());
        log.info("Excel File: {}", config.getExcelFilePath());
        log.info("Sheet: {}", config.getSheetName());
        
        // Run tests
        TestExecutor executor = new TestExecutor(config);
        try {
            TestExecutor.ExecutionResult result = executor.executeAll();
            
            log.info("=== EXECUTION SUMMARY ===");
            log.info("Total Tests: {}", result.getTotal());
            log.info("Passed: {}", result.getPassed());
            log.info("Failed: {}", result.getFailed());
            log.info("Errors: {}", result.getErrors());
            log.info("Duration: {}ms", result.getDurationMs());
            log.info("Pass Rate: {:.2f}%", result.getPassRate());
            
            System.exit(result.getFailed() + result.getErrors() > 0 ? 1 : 0);
            
        } catch (Exception e) {
            log.error("Execution failed: {}", e.getMessage(), e);
            System.exit(1);
        } finally {
            executor.shutdown();
        }
    }
    
    private static void printUsage() {
        System.out.println("""
            API Automation Framework Runner
            
            Usage: java -jar api-automation-framework.jar [options]
            
            Options:
              --env, -e <environment>    Environment to run against (local, dev, qa, prod)
              --excel, -f <file>         Path to Excel test data file
              --sheet, -s <name>         Sheet name in Excel file
              --help, -h                 Show this help message
            
            Examples:
              java -jar api-automation-framework.jar --env local
              java -jar api-automation-framework.jar --env qa --excel testdata.xlsx --sheet SmokeTests
            """);
    }
}