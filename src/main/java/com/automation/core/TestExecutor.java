package com.automation.core;

import com.automation.api.client.ApiClient;
import com.automation.api.model.ApiRequest;
import com.automation.api.model.ApiResponse;
import com.automation.api.request.RequestBuilder;
import com.automation.config.Config;
import com.automation.config.TestCaseData;
import com.automation.excel.ExcelManager;
import com.automation.jsonpath.JsonPathExtractor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
public class TestExecutor {
    private final Config config;
    private final ExcelManager excelManager;
    private final RequestBuilder requestBuilder;
    private final JsonPathExtractor jsonPathExtractor;
    private final ApiClient apiClient;
    private final ExecutorService executorService;

    public TestExecutor() {
        this(Config.getInstance());
    }

    public TestExecutor(Config config) {
        this.config = config;
        this.excelManager = new ExcelManager(config);
        this.requestBuilder = new RequestBuilder();
        this.jsonPathExtractor = new JsonPathExtractor();
        this.apiClient = new ApiClient(config);
        this.executorService = Executors.newFixedThreadPool(
            Math.min(config.getIntProperty("parallelThreads", 4), 10)
        );
    }

    public ExecutionResult executeAll() throws IOException {
        log.info("Starting test execution...");
        long suiteStartTime = System.currentTimeMillis();
        
        List<TestCaseData> testCases = excelManager.loadTestCases();
        
        if (testCases.isEmpty()) {
            log.warn("No test cases to execute");
            return new ExecutionResult(0, 0, 0, 0, 0);
        }

        log.info("Executing {} test cases", testCases.size());
        
        // Execute sequentially for now (can be parallelized later)
        List<TestCaseData> results = testCases.parallelStream()
            .map(this::executeTestCase)
            .collect(Collectors.toList());

        // Save results
        excelManager.saveResults(results);

        long suiteTime = System.currentTimeMillis() - suiteStartTime;
        
        long passed = results.stream().filter(tc -> "PASS".equals(tc.getOverallStatus())).count();
        long failed = results.stream().filter(tc -> "FAIL".equals(tc.getOverallStatus())).count();
        long errors = results.stream().filter(tc -> "ERROR".equals(tc.getOverallStatus())).count();
        long skipped = results.stream().filter(tc -> "SKIPPED".equals(tc.getOverallStatus())).count();

        ExecutionResult result = new ExecutionResult(
            results.size(), passed, failed, errors, suiteTime
        );
        
        log.info("Execution completed in {}ms: Total={}, Passed={}, Failed={}, Errors={}, Skipped={}",
            suiteTime, results.size(), passed, failed, errors, skipped);
        
        return result;
    }

    public TestCaseData executeTestCase(TestCaseData testCase) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("Executing: {} - {}", testCase.getTestCaseId(), testCase.getTestCaseName());
            
            // Build request
            ApiRequest request = requestBuilder.build(testCase);
            
            // Execute with retry
            ApiResponse response = executeWithRetry(request, testCase);
            
            // Capture results
            testCase.setActualStatusCode(response.getStatusCode());
            testCase.setResponseBody(response.getBody());
            testCase.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            
            // Extract and validate
            jsonPathExtractor.extractAndCompare(testCase, response.getBody());
            
            log.debug("Completed: {} - Status: {} ({}ms)", 
                testCase.getTestCaseId(), testCase.getOverallStatus(), testCase.getExecutionTimeMs());
            
        } catch (Exception e) {
            testCase.setErrorMessage(e.getMessage());
            testCase.setOverallStatus("ERROR");
            testCase.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            log.error("Test case {} error: {}", testCase.getTestCaseId(), e.getMessage(), e);
        }
        
        return testCase;
    }

    private ApiResponse executeWithRetry(ApiRequest request, TestCaseData testCase) {
        int maxRetries = config.getRetryCount();
        Exception lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                ApiResponse response = apiClient.execute(request);
                
                // Don't retry on client errors (4xx)
                if (response.isClientError() && attempt < maxRetries) {
                    log.warn("Client error ({}), not retrying: {}", response.getStatusCode(), testCase.getTestCaseId());
                    return response;
                }
                
                // Retry on server errors (5xx) and network errors
                if (response.isServerError() || response.getStatusCode() == 0) {
                    if (attempt < maxRetries) {
                        log.warn("Attempt {} failed for {}, retrying... (status: {})", 
                            attempt + 1, testCase.getTestCaseId(), response.getStatusCode());
                        Thread.sleep(1000 * (attempt + 1)); // Exponential backoff
                        continue;
                    }
                }
                
                return response;
                
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    log.warn("Attempt {} failed for {}, retrying... Error: {}", 
                        attempt + 1, testCase.getTestCaseId(), e.getMessage());
                    try {
                        Thread.sleep(1000 * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        // All retries exhausted
        return ApiResponse.builder()
            .statusCode(0)
            .statusText("MAX_RETRIES_EXCEEDED")
            .body(lastException != null ? lastException.getMessage() : "Unknown error")
            .contentType("text/plain")
            .responseTimeMs(0)
            .url(request.getUrl())
            .method(request.getMethod())
            .build();
    }

    public CompletableFuture<ExecutionResult> executeAllAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeAll();
            } catch (IOException e) {
                log.error("Async execution failed", e);
                return new ExecutionResult(0, 0, 0, 0, 0);
            }
        }, executorService);
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        apiClient.close();
    }

    public static class ExecutionResult {
        private final int total;
        private final long passed;
        private final long failed;
        private final long errors;
        private final long durationMs;

        public ExecutionResult(int total, long passed, long failed, long errors, long durationMs) {
            this.total = total;
            this.passed = passed;
            this.failed = failed;
            this.errors = errors;
            this.durationMs = durationMs;
        }

        public int getTotal() { return total; }
        public long getPassed() { return passed; }
        public long getFailed() { return failed; }
        public long getErrors() { return errors; }
        public long getDurationMs() { return durationMs; }
        public double getPassRate() { return total > 0 ? (double) passed / total * 100 : 0; }

        @Override
        public String toString() {
            return String.format("ExecutionResult{total=%d, passed=%d, failed=%d, errors=%d, duration=%dms, passRate=%.2f%%}",
                total, passed, failed, errors, durationMs, getPassRate());
        }
    }
}