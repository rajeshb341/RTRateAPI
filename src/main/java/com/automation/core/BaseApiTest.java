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
import org.testng.annotations.*;

import java.io.IOException;
import java.util.List;

@Slf4j
public abstract class BaseApiTest {
    protected Config config;
    protected ApiClient apiClient;
    protected ExcelManager excelManager;
    protected RequestBuilder requestBuilder;
    protected JsonPathExtractor jsonPathExtractor;
    protected List<TestCaseData> testCases;

    @BeforeSuite(alwaysRun = true)
    public void beforeSuite() {
        log.info("=== Starting API Test Suite ===");
        config = Config.getInstance();
        excelManager = new ExcelManager(config);
        requestBuilder = new RequestBuilder();
        jsonPathExtractor = new JsonPathExtractor();
    }

    @BeforeTest(alwaysRun = true)
    public void beforeTest() {
        log.info("=== Starting Test: {} ===", getClass().getSimpleName());
        // @BeforeSuite only runs on one test-class instance, so initialise here too
        if (config == null) config = Config.getInstance();
        if (excelManager == null) excelManager = new ExcelManager(config);
        if (requestBuilder == null) requestBuilder = new RequestBuilder();
        if (jsonPathExtractor == null) jsonPathExtractor = new JsonPathExtractor();
        // {{rtToken}} in the sheet (header + body) is filled from APIToken.txt so the token never sits in Excel
        String rtToken = com.automation.rt.RtRequestLoader.readToken(java.nio.file.Paths.get(
            System.getProperty("rtTokenFile", com.automation.rt.RtApiRunner.DEFAULT_DIR + "/APIToken.txt")));
        if (rtToken != null) requestBuilder.variable("rtToken", rtToken);
        apiClient = new ApiClient(config);
    }

    @BeforeClass(alwaysRun = true)
    public void beforeClass() {
        try {
            testCases = TestDataProvider.getTestCases();
            log.info("Loaded {} test cases", testCases.size());
        } catch (IOException e) {
            log.error("Failed to load test cases: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to load test cases", e);
        }
    }

    @AfterClass(alwaysRun = true)
    public void afterClass() {
        try {
            if (testCases != null && !testCases.isEmpty()) {
                excelManager.saveResults(testCases);
                log.info("Test results saved to Excel");
                copyResultsWorkbook();
            }
        } catch (IOException e) {
            log.error("Failed to save test results: {}", e.getMessage(), e);
        }
    }

    @AfterTest(alwaysRun = true)
    public void afterTest() {
        if (apiClient != null) {
            apiClient.close();
        }
        log.info("=== Completed Test: {} ===", getClass().getSimpleName());
    }

    @AfterSuite(alwaysRun = true)
    public void afterSuite() {
        log.info("=== Completed API Test Suite ===");
    }

    protected TestCaseData executeTestCase(TestCaseData testCase) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("Executing test case: {} - {}", testCase.getTestCaseId(), testCase.getTestCaseName());
            
            // Build request
            ApiRequest request = requestBuilder.build(testCase);
            
            saveRequestFile(testCase, request);

            // Execute request
            ApiResponse response = apiClient.execute(request);
            saveResponseFile(testCase, response);

            // Capture response
            testCase.setActualStatusCode(response.getStatusCode());
            testCase.setResponseBody(response.getBody());
            testCase.setExecutionTimeMs(System.currentTimeMillis() - startTime);

            // Status 0 = request never completed (e.g. connection refused); keep the reason in the sheet
            if (response.getStatusCode() == 0) {
                testCase.setErrorMessage(response.getStatusText() + ": " + response.getBody());
            }

            // Extract and compare
            jsonPathExtractor.extractAndCompare(testCase, response.getBody());
            
            log.info("Test case {} completed with status: {} ({}ms)", 
                testCase.getTestCaseId(), testCase.getOverallStatus(), testCase.getExecutionTimeMs());
            
        } catch (Exception e) {
            testCase.setErrorMessage(e.getMessage());
            testCase.setOverallStatus("ERROR");
            testCase.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            log.error("Test case {} failed with error: {}", testCase.getTestCaseId(), e.getMessage(), e);
        }
        
        return testCase;
    }

    // ---- per-run output: target/api-output/Run_<timestamp>/iterations/<TestCaseId>/request.json, response.json ----
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String RUN_STAMP = java.time.LocalDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    private static final java.nio.file.Path RUN_DIR = java.nio.file.Paths.get("target", "api-output", "Run_" + RUN_STAMP);

    private java.nio.file.Path caseDir(TestCaseData testCase) throws IOException {
        java.nio.file.Path dir = RUN_DIR.resolve("iterations").resolve(testCase.getTestCaseId());
        java.nio.file.Files.createDirectories(dir);
        return dir;
    }

    /** Saves the request as sent; Token / Authorization are masked (header and body). Never fails the test. */
    private void saveRequestFile(TestCaseData testCase, ApiRequest request) {
        try {
            var node = MAPPER.createObjectNode();
            node.put("method", request.getMethod());
            node.put("url", request.getUrl());
            var headers = node.putObject("headers");
            if (request.getHeaders() != null) {
                request.getHeaders().forEach((k, v) -> headers.put(k,
                    k.equalsIgnoreCase("Token") || k.equalsIgnoreCase("Authorization") ? "****" : v));
            }
            Object body = request.getBody();
            if (body == null) {
                node.putNull("body");
            } else {
                try {
                    var json = MAPPER.readTree(body.toString());
                    if (json.path("ServiceRequestDetail").isObject() && json.path("ServiceRequestDetail").has("Token")) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("ServiceRequestDetail")).put("Token", "****");
                    }
                    node.set("body", json);
                } catch (Exception notJson) {
                    node.put("body", body.toString());
                }
            }
            java.nio.file.Files.writeString(caseDir(testCase).resolve("request.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Could not save request file for {}: {}", testCase.getTestCaseId(), e.getMessage());
        }
    }

    /** Saves the response (also failed / status 0 ones) plus the HTTP status line. Never fails the test. */
    private void saveResponseFile(TestCaseData testCase, ApiResponse response) {
        try {
            String body = response.getBody() == null ? "" : response.getBody()
                .replaceAll("(\"Token\"\\s*:\\s*\")[^\"]*(\")", "$1****$2");
            String text;
            String name;
            try {
                text = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(body));
                name = "response.json";
            } catch (Exception notJson) {
                text = body;
                name = "response.txt";
            }
            java.nio.file.Path dir = caseDir(testCase);
            java.nio.file.Files.writeString(dir.resolve(name), text, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.writeString(dir.resolve("response-status.txt"),
                "HTTP " + response.getStatusCode() + " " + response.getStatusText() + " (" + response.getResponseTimeMs() + " ms)"
                    + System.lineSeparator(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Could not save response file for {}: {}", testCase.getTestCaseId(), e.getMessage());
        }
    }

    /** Copies the results workbook into the run folder so each run keeps its own output Excel. */
    private void copyResultsWorkbook() {
        try {
            java.nio.file.Files.createDirectories(RUN_DIR);
            java.nio.file.Files.copy(java.nio.file.Paths.get(config.getExcelFilePath()),
                RUN_DIR.resolve("TestResults_" + RUN_STAMP + ".xlsx"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Run output saved in {}", RUN_DIR.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Could not copy results workbook: {}", e.getMessage());
        }
    }

    protected void assertTestCasePassed(TestCaseData testCase) {
        org.testng.Assert.assertEquals(testCase.getOverallStatus(), "PASS", 
            "Test case " + testCase.getTestCaseId() + " failed: " + testCase.getErrorMessage());
    }

    protected void assertStatusCode(TestCaseData testCase, int expectedStatus) {
        org.testng.Assert.assertEquals(testCase.getActualStatusCode(), expectedStatus,
            "Status code mismatch for " + testCase.getTestCaseId());
    }

    protected void assertJsonPathValue(TestCaseData testCase, String jsonPath, String expectedValue) {
        String actualValue = testCase.getActualValues().get(jsonPath);
        org.testng.Assert.assertEquals(actualValue, expectedValue,
            "JsonPath value mismatch for " + testCase.getTestCaseId() + " at " + jsonPath);
    }
}