package com.automation.excel;

import com.automation.config.Config;
import com.automation.config.TestCaseData;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

@Slf4j
public class ExcelManager {
    private final ExcelReader reader;
    private final ExcelWriter writer;

    public ExcelManager() {
        this.reader = new ExcelReader();
        this.writer = new ExcelWriter();
    }

    public ExcelManager(Config config) {
        this.reader = new ExcelReader(config);
        this.writer = new ExcelWriter(config);
    }

    public List<TestCaseData> loadTestCases() throws IOException {
        log.info("Loading test cases from Excel...");
        reader.createTemplateIfNotExists();
        return reader.readTestCases();
    }

    public void saveResults(List<TestCaseData> testCases) throws IOException {
        log.info("Saving results to Excel...");
        writer.writeResults(testCases);
    }

    public void saveResult(TestCaseData testCase) throws IOException {
        writer.writeSingleResult(testCase);
    }

    public TestCaseData loadTestCase(int rowNumber) throws IOException {
        return reader.readTestCase(rowNumber);
    }
}