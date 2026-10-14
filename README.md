# API Automation Framework

A modular, production-ready API automation framework built with **Playwright Java**, **TestNG**, **Apache POI**, and **Jayway JsonPath**.

## Features

- **Data-Driven Testing**: Test cases driven from Excel workbooks
- **API Execution**: Full HTTP client using Playwright's APIRequest
- **JsonPath Extraction**: Extract and validate response attributes using JsonPath expressions
- **Excel Reporting**: Write back Actual Values and Pass/Fail Status directly to Excel
- **Multi-Environment**: Support for local, dev, QA, prod environments
- **Parallel Execution**: TestNG parallel execution support
- **Comprehensive Logging**: SLF4J/Logback with file and console output
- **Allure Reporting**: Integrated Allure report generation
- **Mock Server**: Built-in contract testing with mocked endpoints

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
| AssertJ | 3.24.2 |
| Allure TestNG | 2.24.0 |

## Project Structure

```
src/
├── main/java/com/automation/
│   ├── config/           # Configuration classes
│   ├── api/
│   │   ├── client/       # API client (Playwright-based)
│   │   ├── model/        # Request/Response models
│   │   └── request/      # Request builder utilities
│   ├── excel/            # Excel read/write utilities
│   ├── jsonpath/         # JsonPath extraction & validation
│   ├── core/             # Core test execution engine
│   ├── mocks/            # Mock API server for contract testing
│   └── util/             # Utility classes
├── test/java/com/automation/
│   ├── tests/            # Test classes
│   └── contract/         # Contract tests
└── test/resources/
    ├── testdata/         # Excel test data files
    ├── config/           # Environment configs
    ├── testng.xml        # TestNG suite configuration
    └── logback.xml       # Logging configuration
```

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- Playwright browsers (auto-installed)

### Installation

```bash
# Clone and build
git clone <repo>
cd api-automation-framework
mvn clean install -DskipTests

# Install Playwright browsers
mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install"
```

### Generate Test Data Template

```bash
# Generate Excel template
mvn exec:java -Dexec.mainClass="com.automation.util.TemplateGenerator" -Dexec.args="--env local --file src/test/resources/testdata/test-data.xlsx --sheet TestCases"
```

### Run Tests

```bash
# Run all tests (local environment)
mvn test

# Run with specific environment
mvn test -Denvironment=qa

# Run specific test suite
mvn test -DsuiteXmlFile=src/test/resources/testng.xml

# Run with Allure report
mvn test allure:serve
```

## RT Rater Validation (API vs Rater Excel)

Compares the RT rating API response with the values the Rater workbook (`LloydsRater.xlsx`) calculates.
The Rater is recalculated by WPS Office / Excel through COM (`scripts/rater/*.ps1`); Apache POI cannot evaluate it correctly.

```bash
# The single test data file is src/test/resources/testdata/test-data.xlsx (sheet TestData): edit your cases there.
# Runs only read it; --generate never overwrites it (it writes test-data_generated_<timestamp>.xlsx instead).
# 1. (Optional) create new test data from the Rater dropdowns
mvn compile exec:java -Dexec.mainClass=com.automation.rater.RaterValidationRunner -Dexec.args="--generate 10"

# 2. Run: inputs -> Rater (Expected) and -> API (Actual), compare, write target/rater-output/RaterValidationReport_*.xlsx
mvn compile exec:java -Dexec.mainClass=com.automation.rater.RaterValidationRunner
#    optional: -Dexec.args="--cases TC001,TC002"
```

Every execution writes to its own folder `target/rater-output/Run_<yyyyMMdd_HHmmss>/`:
```
console-log.txt                       log of the run (INFO level, tokens never logged)
RaterValidationReport_<ts>.xlsx       Results (per attribute Pass/Fail) + Summary (per test case)
iterations/TC001/request.json         request sent for that iteration (token masked)
iterations/TC001/response.json        response received (response.txt if not JSON) + response-status.txt
working/                              Rater inputs/expected values as JSON (and filled Raters if enabled)
```

Configuration (all in `src/main/resources/config/`):
- `rater.properties` - workbook path, input/output rows, dropdown overrides, tolerance, report folder
- `rater-request-mapping.properties` - input row -> JSON path in the request body
- `rater-response-mapping.properties` - output row -> JsonPath in the response (fill these in)

## Excel Test Data Format

The framework reads test cases from an Excel file with the following columns:

| Column | Description | Required |
|--------|-------------|----------|
| TestCaseId | Unique identifier | Yes |
| TestCaseName | Human-readable name | Yes |
| Description | Test description | No |
| Endpoint | API endpoint (with path params) | Yes |
| HttpMethod | GET, POST, PUT, DELETE, PATCH | Yes |
| Headers | Key:value pairs (one per line) | No |
| RequestBody | Request payload (JSON/XML) | No |
| QueryParams | Key:value pairs (one per line) | No |
| PathParams | Key:value pairs (one per line) | No |
| ExpectedStatusCode | Expected HTTP status | Yes |
| ExpectedResponseBody | Full expected response (optional) | No |
| JsonPathExpressions | JsonPath expressions (one per line) | No |
| ExpectedValues | Expected values for each JsonPath | No |
| TestData | Additional test data | No |
| Enabled | Y/N to enable/disable test | Yes |
| Priority | HIGH/MEDIUM/LOW | No |
| Category | SMOKE/REGRESSION/CONTRACT | No |

### Example Row

```
TC001 | Get User By ID | Retrieve user details | /api/users/{userId} | GET | Content-Type:application/json\nAuthorization:Bearer {{authToken}} |  | includeDetails=true | userId=123 | 200 |  | $.id\n$.name\n$.email\n$.status | 123\nJohn Doe\njohn.doe@example.com\nactive |  | Y | HIGH | SMOKE
```

### JsonPath Expression Format

```
# Simple path
$.id
$.name

# With alias (alias=path)
userId=$.id
userName=$.name
userEmail=$.email
userStatus=$.status

# Array access
firstUserName=$.users[0].name
allEmails=$.users[*].email
```

## Configuration

### Environment Properties

Create environment-specific properties in `src/main/resources/config/`:

- `local.properties` - Local development
- `dev.properties` - Development
- `qa.properties` - QA
- `prod.properties` - Production

### System Properties

Override configuration at runtime:

```bash
mvn test -Denvironment=qa -DbaseUrl=https://qa-api.example.com -DexcelFilePath=custom-data.xlsx -DsheetName=SmokeTests
```

### Environment Variables

```bash
export ENVIRONMENT=qa
export BASE_URL=https://qa-api.example.com
export AUTH_TOKEN=your-jwt-token
export API_KEY=your-api-key
```

## Writing Tests

### Data-Driven Test (from Excel)

```java
public class MyApiTest extends BaseApiTest {
    
    @Test(dataProvider = "testCases", dataProviderClass = TestDataProvider.class)
    public void testFromExcel(TestCaseData testCase) {
        TestCaseData result = executeTestCase(testCase);
        assertTestCasePassed(result);
    }
}
```

### Programmatic Test

```java
@Test
public void testCustomApi() {
    ApiRequest request = ApiRequest.builder()
        .url("/api/users/123")
        .method("GET")
        .header("Authorization", "Bearer token")
        .queryParam("include", "profile")
        .build();
    
    ApiResponse response = apiClient.execute(request);
    
    Assert.assertEquals(response.getStatusCode(), 200);
    Assert.assertTrue(response.getBody().contains("John Doe"));
}
```

## JsonPath Validation

The framework automatically extracts values using JsonPath and compares with expected values:

```java
// In Excel:
// JsonPathExpressions: $.id\n$.name\n$.email
// ExpectedValues: 123\nJohn Doe\njohn.doe@example.com

// Results written back to Excel:
// ActualValues: id: 123\nname: John Doe\nemail: john.doe@example.com
// PassFailStatus: id: PASS\nname: PASS\nemail: PASS
// OverallStatus: PASS
```

## Contract Testing

Run contract tests against the built-in mock server:

```bash
mvn test -Dtest=MockContractTest
```

The mock server provides predefined endpoints:
- GET/POST/PUT/DELETE `/api/users/{id}`
- GET `/api/users`
- GET `/api/health`
- POST `/api/login`
- Error endpoints (400, 401, 404, 500)

## Reports

### Allure Report

```bash
# Generate and serve report
mvn allure:serve

# Generate static report
mvn allure:report
```

### Excel Report

Test results are written back to the source Excel file with:
- Actual Status Code
- Actual Response Body
- Extracted Actual Values
- Pass/Fail Status per validation
- Overall Status (PASS/FAIL/ERROR)
- Error Message (if any)
- Execution Time

## Extending the Framework

### Custom JsonPath Validators

```java
public class CustomValidator {
    public static boolean validateEmail(String email) {
        return email != null && email.matches("^[^@]+@[^@]+\\.[^@]+$");
    }
}
```

### Custom Authentication

```java
apiClient.setAuthToken("your-jwt-token");
// or
apiClient.addDefaultHeader("X-API-Key", "your-key");
```

### Parallel Execution

Configure in `testng.xml`:

```xml
<suite name="API Suite" parallel="methods" thread-count="4">
```

## CI/CD Integration

### GitHub Actions

```yaml
- name: Run API Tests
  run: mvn test -Denvironment=qa -DbaseUrl=${{ secrets.QA_API_URL }}
  
- name: Upload Allure Report
  uses: actions/upload-artifact@v3
  with:
    name: allure-results
    path: target/allure-results
```

## Troubleshooting

### Common Issues

1. **Playwright browsers not installed**
   ```bash
   mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install"
   ```

2. **Excel file locked**
   - Close Excel before running tests
   - Ensure no other process has the file open

3. **JsonPath not found**
   - Verify JSON response structure
   - Check JsonPath expression syntax

4. **Connection refused**
   - Verify base URL is correct
   - Check mock server is running (for contract tests)

## License

MIT License - See LICENSE file for details.
## First-time setup after cloning

The token files are not in the repository. In the project root:

1. Copy `APIRequest.example.txt` to `APIRequest.txt` and put your token in `ServiceRequestDetail.Token`.
2. Copy `APIToken.example.txt` to `APIToken.txt` (`Token:<your token>`).
3. Make sure `RequestSpecifications.txt` holds your header values (EventName, OwnerId, Mode, Environment).
4. Edit the cases in `src/test/resources/testdata/test-data.xlsx` (sheet `TestData`) and choose what runs with `rater.iterations` in `src/main/resources/config/rater.properties` (empty = all).

Requirements: Java 17+, WPS Office (`KET.Application` registered) or Microsoft Excel, and network access to the RT endpoint.
