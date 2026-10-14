package com.automation.tests;

import com.automation.api.model.ApiRequest;
import com.automation.api.model.ApiResponse;
import com.automation.config.TestCaseData;
import com.automation.core.BaseApiTest;
import com.automation.core.TestDataProvider;
import org.testng.annotations.*;

public class DataDrivenApiTest extends BaseApiTest {

    @Test(dataProvider = "testCases", dataProviderClass = TestDataProvider.class, 
          description = "Data-driven API test from Excel")
    public void testApiFromExcel(TestCaseData testCase) {
        // Execute test case using base class method
        TestCaseData result = executeTestCase(testCase);
        
        // Assert overall pass
        org.testng.Assert.assertEquals(result.getOverallStatus(), "PASS",
            "Test case " + testCase.getTestCaseId() + " failed: " + result.getErrorMessage());
        
        // Assert status code
        org.testng.Assert.assertEquals(result.getActualStatusCode(), testCase.getExpectedStatusCodeAsInt(),
            "Status code mismatch for " + testCase.getTestCaseId());
    }

    @Test(dataProvider = "smokeTests", dataProviderClass = TestDataProvider.class,
          description = "Smoke tests from Excel", groups = {"smoke"})
    public void testSmoke(TestCaseData testCase) {
        TestCaseData result = executeTestCase(testCase);
        org.testng.Assert.assertEquals(result.getOverallStatus(), "PASS",
            "Smoke test " + testCase.getTestCaseId() + " failed");
    }

    @Test(dataProvider = "regressionTests", dataProviderClass = TestDataProvider.class,
          description = "Regression tests from Excel", groups = {"regression"})
    public void testRegression(TestCaseData testCase) {
        TestCaseData result = executeTestCase(testCase);
        org.testng.Assert.assertEquals(result.getOverallStatus(), "PASS",
            "Regression test " + testCase.getTestCaseId() + " failed");
    }

    @Test(description = "Simple GET request test")
    public void testSimpleGet() {
        ApiRequest request = ApiRequest.builder()
            .url("/api/health")
            .method("GET")
            .build();
        
        ApiResponse response = apiClient.execute(request);
        
        org.testng.Assert.assertEquals(response.getStatusCode(), 200);
        org.testng.Assert.assertTrue(response.getBody().contains("UP"));
    }

    @Test(description = "Test with query parameters")
    public void testWithQueryParams() {
        ApiRequest request = ApiRequest.builder()
            .url("/api/users")
            .method("GET")
            .queryParam("page", "1")
            .queryParam("size", "10")
            .build();
        
        ApiResponse response = apiClient.execute(request);
        
        org.testng.Assert.assertEquals(response.getStatusCode(), 200);
    }

    @Test(description = "Test with headers")
    public void testWithCustomHeaders() {
        ApiRequest request = ApiRequest.builder()
            .url("/api/users/123")
            .method("GET")
            .header("X-Custom-Header", "test-value")
            .header("Accept", "application/json")
            .build();
        
        ApiResponse response = apiClient.execute(request);
        
        org.testng.Assert.assertEquals(response.getStatusCode(), 200);
    }
}