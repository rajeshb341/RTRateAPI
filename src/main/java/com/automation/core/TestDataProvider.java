package com.automation.core;

import com.automation.config.TestCaseData;
import com.automation.excel.ExcelManager;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.DataProvider;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class TestDataProvider {
    private static final ExcelManager excelManager = new ExcelManager();
    private static List<TestCaseData> cachedTestCases;

    @DataProvider(name = "testCases", parallel = true)
    public static Iterator<Object[]> provideTestCases() throws IOException {
        if (cachedTestCases == null) {
            cachedTestCases = excelManager.loadTestCases();
            log.info("DataProvider loaded {} test cases", cachedTestCases.size());
        }
        
        return cachedTestCases.stream()
            .filter(TestCaseData::isEnabled)
            .map(tc -> new Object[]{tc})
            .collect(Collectors.toList())
            .iterator();
    }

    @DataProvider(name = "testCasesByCategory")
    public static Iterator<Object[]> provideTestCasesByCategory() throws IOException {
        if (cachedTestCases == null) {
            cachedTestCases = excelManager.loadTestCases();
        }
        
        return cachedTestCases.stream()
            .filter(TestCaseData::isEnabled)
            .map(tc -> new Object[]{tc})
            .collect(Collectors.toList())
            .iterator();
    }

    @DataProvider(name = "smokeTests")
    public static Iterator<Object[]> provideSmokeTests() throws IOException {
        if (cachedTestCases == null) {
            cachedTestCases = excelManager.loadTestCases();
        }
        
        return cachedTestCases.stream()
            .filter(TestCaseData::isEnabled)
            .filter(tc -> "SMOKE".equalsIgnoreCase(tc.getCategory()))
            .map(tc -> new Object[]{tc})
            .collect(Collectors.toList())
            .iterator();
    }

    @DataProvider(name = "regressionTests")
    public static Iterator<Object[]> provideRegressionTests() throws IOException {
        if (cachedTestCases == null) {
            cachedTestCases = excelManager.loadTestCases();
        }
        
        return cachedTestCases.stream()
            .filter(TestCaseData::isEnabled)
            .filter(tc -> "REGRESSION".equalsIgnoreCase(tc.getCategory()))
            .map(tc -> new Object[]{tc})
            .collect(Collectors.toList())
            .iterator();
    }

    /** The same instances the data providers hand to the tests, so results written back include what was executed. */
    public static synchronized List<TestCaseData> getTestCases() throws IOException {
        if (cachedTestCases == null) {
            cachedTestCases = excelManager.loadTestCases();
        }
        return cachedTestCases;
    }

    public static void clearCache() {
        cachedTestCases = null;
    }
}