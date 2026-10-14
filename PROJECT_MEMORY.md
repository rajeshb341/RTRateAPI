# API Automation Framework - Project Memory

## Project Overview
**Modular, production-ready API automation framework** using Playwright Java, TestNG, Apache POI, Jayway JsonPath. Drives tests from Excel, executes APIs, extracts/validates response via JsonPath, writes results back to Excel.

## Tech Stack
| Component | Version |
|-----------|---------|
| Java | 17+ |
| Playwright | 1.44.0 |
| TestNG | 7.8.0 |
| Apache POI | 5.2.5 |
| Jayway JsonPath | 2.9.0 |
| Lombok | 1.18.30 |
| SLF4J/Logback | 2.0.9 / 1.4.11 |
| Jackson | 2.15.2 |
| Allure TestNG | 2.24.0 |

## Project Structure
```
src/main/java/com/automation/
├── config/           # Config, Environment, TestCaseData, ExcelColumns
├── api/client/       # ApiClient (Playwright HTTP client with retry)
├── api/model/        # ApiRequest, ApiResponse POJOs
├── api/request/      # RequestBuilder (builds request from TestCaseData)
├── excel/            # ExcelReader, ExcelWriter, ExcelManager
├── jsonpath/         # JsonPathExtractor, JsonPathValidator
├── core/             # BaseApiTest, TestExecutor, TestDataProvider, TestExecutionListener, FrameworkRunner
├── mocks/            # MockApiServer (11 predefined endpoints)
└── util/             # TemplateGenerator
src/test/java/com/automation/
├── tests/DataDrivenApiTest.java     # Data-driven tests from Excel
└── contract/MockContractTest.java   # Contract tests vs mock server
src/test/resources/
├── testng.xml       # TestNG suite (parallel=methods, thread-count=4)
└── logback.xml      # Logging config
src/main/resources/config/
├── default.properties
└── local.properties
```

## Core Classes & Responsibilities

### Configuration
- **Config.java** - Singleton, loads from properties files, system props, env vars. Supports local/dev/qa/prod environments.
- **Environment.java** - Enum: LOCAL, DEV, QA, PROD with base URLs.
- **TestCaseData.java** - POJO for one test case row (input + output fields).
- **ExcelColumns.java** - Column indices and header names for Excel I/O.

### API Execution
- **ApiClient.java** - Playwright APIRequestContext wrapper. Handles auth, headers, retry (3x), timeout (30s), redirects.
- **ApiRequest/ApiResponse.java** - Immutable data models with builders.
- **RequestBuilder.java** - Converts TestCaseData → ApiRequest. Resolves `{{placeholders}}`, path params `{userId}`, parses key:value multiline strings.

### Excel I/O
- **ExcelReader.java** - Reads test cases from sheet, skips disabled rows, creates template if missing.
- **ExcelWriter.java** - Writes results back: ActualStatusCode, ActualResponseBody, ActualValues, PassFailStatus, OverallStatus, ErrorMessage, ExecutionTimeMs. Color-codes PASS/FAIL/ERROR.
- **ExcelManager.java** - Facade for load/save operations.

### JsonPath Validation
- **JsonPathExtractor.java** - `extractAll(json, expressions)` returns Map<alias, value>. Supports `alias=$.path` or bare `$.path`. Compares expected vs actual with type coercion (numeric, boolean, string). Sets OverallStatus.
- **JsonPathValidator.java** - Standalone validator with ValidationResult/ValidationEntry.

### Test Execution
- **BaseApiTest.java** - TestNG base: @BeforeSuite/Class/Test, @AfterClass/Test/Suite. Loads Excel in @BeforeClass, saves in @AfterClass. Provides `executeTestCase(TestCaseData)`.
- **TestExecutor.java** - Standalone runner (used by FrameworkRunner). Executes with retry logic, parallel stream support, returns ExecutionResult.
- **TestDataProvider.java** - @DataProvider methods: testCases, smokeTests, regressionTests, testCasesByCategory.
- **TestExecutionListener.java** - ITestListener logs start/pass/fail/skip, records TestCaseData results.
- **FrameworkRunner.java** - CLI entry: `java -jar --env qa --excel file.xlsx --sheet SmokeTests`.

### Mock Server (Contract Testing)
- **MockApiServer.java** - In-memory mock with 11 endpoints:
  - GET/POST/PUT/DELETE /api/users/{id}
  - GET /api/users, /api/health
  - POST /api/login
  - Error endpoints: 400, 401, 404, 500
- **MockContractTest.java** - 11 contract tests using inline TestCaseData (no Excel needed).

## Excel Test Data Format
Columns (0-24): RowNumber, TestCaseId, TestCaseName, Description, Endpoint, HttpMethod, Headers, RequestBody, QueryParams, PathParams, ExpectedStatusCode, ExpectedResponseBody, JsonPathExpressions, ExpectedValues, TestData, Enabled, Priority, Category, **ActualStatusCode, ActualResponseBody, ActualValues, PassFailStatus, OverallStatus, ErrorMessage, ExecutionTimeMs**

**JsonPath Expressions** (one per line):
```
$.id
userName=$.name
firstEmail=$.users[0].email
```

**ExpectedValues** (one per line, matching order):
```
123
John Doe
john@example.com
```

## Running Tests
```bash
# Generate Excel template
mvn exec:java -Dexec.mainClass="com.automation.util.TemplateGenerator"

# Contract tests (no external deps)
mvn test -Dtest=MockContractTest

# All tests
mvn test

# Specific environment
mvn test -Denvironment=qa -DbaseUrl=https://qa-api.example.com

# Allure report
mvn test allure:serve
```

## Key Patterns
- **Placeholders in Excel**: `{{authToken}}`, `{{apiKey}}` resolved via RequestBuilder.variables()
- **Path params**: `/api/users/{userId}` + PathParams: `userId=123`
- **Multiline headers/params**: `Key1:Value1\nKey2:Value2`
- **Disabled tests**: Enabled=N skips execution
- **Categories**: SMOKE, REGRESSION, CONTRACT for filtered runs

## Extension Points
- Add custom JsonPath validators in JsonPathValidator
- Custom authentication: `apiClient.setAuthToken()` or `addDefaultHeader()`
- New environments: add `config/<env>.properties`
- Parallel execution: configure thread-count in testng.xml

## CI/CD Ready
- Maven profiles: local, dev, qa, prod
- Allure reporting integrated
- Headless mode configurable
- Exit code non-zero on failures (FrameworkRunner)

## Files to Modify for Production
1. `src/main/resources/config/<env>.properties` - Real base URLs, timeouts
2. `src/test/resources/testdata/test-data.xlsx` - Real test cases
3. `MockApiServer.java` - Replace with real API or WireMock
4. Add authentication logic in ApiClient.setupDefaultHeaders()