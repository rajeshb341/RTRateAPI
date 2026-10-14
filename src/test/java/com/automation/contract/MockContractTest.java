package com.automation.contract;
import com.automation.api.model.ApiRequest;
import com.automation.api.model.ApiResponse;
import com.automation.api.request.RequestBuilder;
import com.automation.config.Config;
import com.automation.config.Environment;
import com.automation.config.TestCaseData;
import com.automation.core.BaseApiTest;
import com.automation.jsonpath.JsonPathExtractor;
import com.automation.mocks.MockApiServer;
import org.testng.annotations.*;
import org.testng.Assert;

public class MockContractTest extends BaseApiTest {
    private static MockApiServer mockServer;
    private JsonPathExtractor jsonPathExtractor;

    @BeforeSuite
    public void setupMockServer() {
        mockServer = new MockApiServer(8080);
        mockServer.start();
        
        // Override config to use mock server
        Config config = Config.getInstance();
        config.setBaseUrl("http://localhost:8080");
        config.setEnvironment(Environment.LOCAL);
    }

    @AfterSuite
    public void teardownMockServer() {
        if (mockServer != null) {
            mockServer.close();
        }
    }

    @BeforeClass
    public void setup() {
        jsonPathExtractor = new JsonPathExtractor();
        super.beforeClass();
    }

    @Test(dataProvider = "contractTestCases", description = "Contract test for GET /api/users/{userId}")
    public void testGetUserById(TestCaseData testCase) {
        executeAndValidate(testCase);
    }

    @Test(dataProvider = "contractTestCases", description = "Contract test for GET /api/users")
    public void testListUsers(TestCaseData testCase) {
        executeAndValidate(testCase);
    }

    @Test(dataProvider = "contractTestCases", description = "Contract test for POST /api/users")
    public void testCreateUser(TestCaseData testCase) {
        executeAndValidate(testCase);
    }

    @Test(dataProvider = "contractTestCases", description = "Contract test for PUT /api/users/{userId}")
    public void testUpdateUser(TestCaseData testCase) {
        executeAndValidate(testCase);
    }

    @Test(dataProvider = "contractTestCases", description = "Contract test for DELETE /api/users/{userId}")
    public void testDeleteUser(TestCaseData testCase) {
        executeAndValidate(testCase);
    }

    @Test(dataProvider = "contractTestCases", description = "Contract test for GET /api/health")
    public void testHealthCheck(TestCaseData testCase) {
        executeAndValidate(testCase);
    }

    @Test(dataProvider = "contractTestCases", description = "Contract test for POST /api/login")
    public void testLogin(TestCaseData testCase) {
        executeAndValidate(testCase);
    }

    @Test(dataProvider = "contractTestCases", description = "Contract test for error endpoints")
    public void testErrorEndpoints(TestCaseData testCase) {
        executeAndValidate(testCase);
    }

    private void executeAndValidate(TestCaseData testCase) {
        // Build request
        ApiRequest request = new RequestBuilder().build(testCase);
        
        // Execute
        ApiResponse response = apiClient.execute(request);
        
        // Capture
        testCase.setActualStatusCode(response.getStatusCode());
        testCase.setResponseBody(response.getBody());
        
        // Validate status code
        Assert.assertEquals(response.getStatusCode(), testCase.getExpectedStatusCodeAsInt(),
            "Status code mismatch for " + testCase.getTestCaseId());
        
        // Extract and compare JSON paths
        jsonPathExtractor.extractAndCompare(testCase, response.getBody());
        
        // Assert all validations passed
        Assert.assertEquals(testCase.getOverallStatus(), "PASS",
            "Validation failed for " + testCase.getTestCaseId() + ": " + testCase.getErrorMessage());
        
        // Log results
        testCase.getPassFailStatus().forEach((key, status) -> 
            System.out.printf("  [%s] %s: expected=%s, actual=%s%n", 
                status, key, 
                testCase.getExpectedValues().contains(key) ? "configured" : "N/A",
                testCase.getActualValues().get(key)));
    }

    @DataProvider(name = "contractTestCases")
    public Object[][] provideContractTestCases() {
        return new Object[][]{
            {createTestCase("TC001", "Get User By ID", "GET", "/api/users/123", 200, 
                "id=123\nname=John Doe\nemail=john.doe@example.com\nstatus=active")},
            {createTestCase("TC002", "List Users", "GET", "/api/users", 200,
                "total=3\npage=1")},
            {createTestCase("TC003", "Create User", "POST", "/api/users", 201,
                "id=124\nname=New User\nstatus=active")},
            {createTestCase("TC004", "Update User", "PUT", "/api/users/123", 200,
                "id=123\nname=John Doe Updated\nemail=john.doe.updated@example.com")},
            {createTestCase("TC005", "Delete User", "DELETE", "/api/users/123", 204,
                "")},
            {createTestCase("TC006", "Health Check", "GET", "/api/health", 200,
                "status=UP\nversion=1.0.0")},
            {createTestCase("TC007", "User Login", "POST", "/api/login", 200,
                "token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.mock.token\nexpiresIn=3600")},
            {createTestCase("TC008", "Bad Request Error", "GET", "/api/error/400", 400,
                "error=Bad Request")},
            {createTestCase("TC009", "Unauthorized Error", "GET", "/api/error/401", 401,
                "error=Unauthorized")},
            {createTestCase("TC010", "Not Found Error", "GET", "/api/error/404", 404,
                "error=Not Found")},
            {createTestCase("TC011", "Internal Server Error", "GET", "/api/error/500", 500,
                "error=Internal Server Error")}
        };
    }

    private TestCaseData createTestCase(String id, String name, String method, String endpoint, 
                                        int expectedStatus, String expectedValues) {
        String jsonPaths = switch (id) {
            case "TC001" -> "id\nname\nemail\nstatus";
            case "TC002" -> "total\npage";
            case "TC003" -> "id\nname\nstatus";
            case "TC004" -> "id\nname\nemail";
            case "TC005" -> "";
            case "TC006" -> "status\nversion";
            case "TC007" -> "token\nexpiresIn";
            case "TC008", "TC009", "TC010", "TC011" -> "error";
            default -> "";
        };

        return TestCaseData.builder()
            .testCaseId(id)
            .testCaseName(name)
            .httpMethod(method)
            .endpoint(endpoint)
            .expectedStatusCode(String.valueOf(expectedStatus))
            .jsonPathExpressions(jsonPaths)
            .expectedValues(expectedValues)
            .enabled("Y")
            .priority("HIGH")
            .category("CONTRACT")
            .build();
    }
}