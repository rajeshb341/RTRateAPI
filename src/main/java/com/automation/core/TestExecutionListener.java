package com.automation.core;

import com.automation.config.TestCaseData;
import lombok.extern.slf4j.Slf4j;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class TestExecutionListener implements ITestListener {
    private final Map<String, TestCaseData> testCaseResults = new ConcurrentHashMap<>();

    @Override
    public void onTestStart(ITestResult result) {
        log.info(">>> Starting test: {}", result.getName());
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        log.info("<<< Test passed: {} ({}ms)", result.getName(), getDuration(result));
    }

    @Override
    public void onTestFailure(ITestResult result) {
        log.error("<<< Test failed: {} ({}ms) - {}", 
            result.getName(), getDuration(result), 
            result.getThrowable() != null ? result.getThrowable().getMessage() : "Unknown error");
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        log.warn("<<< Test skipped: {} - {}", result.getName(), 
            result.getThrowable() != null ? result.getThrowable().getMessage() : "No reason provided");
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        log.warn("Test failed but within success percentage: {}", result.getName());
    }

    @Override
    public void onStart(ITestContext context) {
        log.info("=== Test Suite Started: {} ===", context.getName());
        testCaseResults.clear();
    }

    @Override
    public void onFinish(ITestContext context) {
        log.info("=== Test Suite Finished: {} ===", context.getName());
        log.info("Tests run: {}, Passed: {}, Failed: {}, Skipped: {}",
            context.getAllTestMethods().length,
            context.getPassedTests().size(),
            context.getFailedTests().size(),
            context.getSkippedTests().size());
        
        // Log summary of test case results
        if (!testCaseResults.isEmpty()) {
            log.info("--- Test Case Summary ---");
            testCaseResults.values().forEach(tc -> 
                log.info("  {} - {} - {} ({}ms)", 
                    tc.getTestCaseId(), tc.getOverallStatus(), tc.getTestCaseName(), tc.getExecutionTimeMs())
            );
        }
    }

    public void recordTestCaseResult(TestCaseData testCase) {
        testCaseResults.put(testCase.getTestCaseId(), testCase);
    }

    private long getDuration(ITestResult result) {
        return result.getEndMillis() - result.getStartMillis();
    }
}