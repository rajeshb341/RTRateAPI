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

Compares the RT rating API response with the values the Rater workbook (`Worksheet in RT Common Lloyds Rater BRD V4.0 11-11-2026.xlsx`, set by `rater.workbook`) calculates.

Renewal cases (Transaction Type row 14 = `Renewal`) also fill the Rater's renewal inputs (rows 89-95):
- Book Of Business Adjustment (89) `No`, Min / Max Renewal Reserve Factor (90/91) `0.5` / `0.7`, Renewal Adjustment Type (95) `No Adjustment` (`rater.input.renewal.<row>`; a test data value wins).
- The renewal Insurance Score Range / Applicable (92/93) are the case's rows 41 / 40 (`rater.input.valueFrom.<row>=<source>|*`).
- The request gets QuoteType `Renewal` and BookOfBusinessAdjustment, Minimum/MaximumRenewalReserveFactor and RenewalAdjustmentType (also in wind requests).
- Book Of Business Adjustment is the test data column `89:...BookOfBusinessAdjustment` (Yes / No, Renewal cases only).
- Wind Deductible (row 45) is kept in the test data as the API fraction (0.01 / 0.02 / 0.04 / 0.05 / 0.1, never empty); the Rater gets its dropdown wording via `rater.input.alias.45`.
- HI / KY / MA / MD cases rate the Rater with Insurance Score Range "Not Applicable" (`rater.request.stateRule.insuranceScoreNA.raterInputs`), so C147 takes the Rate Tables factor 0 like the API.
- Rater row 229 is now labelled NonWindFDR; the API still returns it as `FDRWithoutInsuranceScoreFactor`. The renewal calculation rows (230-352) have no API response fields yet, so they are not compared.
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
- `rater.properties` - workbook path, input/output rows, dropdown overrides, tolerance, report folder, configurations, range rows
- `rater-request-mapping.properties` - input row -> JSON path in the request body (`<CONFIG>.row.N` overrides per configuration)
- `rater-value-mapping.txt` - Rater wording -> API value (optionally per configuration)
- `rater-response-mapping.properties` - output row -> JsonPath in the response
- `templates/<CONFIG>.json` - request template of each configuration (no token; it is filled from `APIToken.txt`)
- `local.properties` - `rt.endpoint` (override with `-DrtEndpoint=...` or `RT_ENDPOINT`)

Configurations: every test case is one of 10 configurations, chosen from its Subline (row 9) and Occupancy Type (row 26):
HO3, HO5, HO6, HO8, DP1, DP3 and DP1/DP3 + Builders Risk / Vacant. Its request starts from that configuration's template.
Other combinations (e.g. HO3 + Vacant) and valuation pairs a subline is not rated with are reported as `Skipped`.
Range values (insurance score `614-652`, roof age `0 - 5 Years`) are sent as one number inside the range
(`rater.range.rows`, `rater.range.pick=min|mid|max`); distance-to-coast bands are sent as their rate-table key.

Wind coverage: a case with PLWindCoverage (row 43) = `Yes` also sends the building's wind attributes - test data columns
`Request:<Name>` (RoofType, RoofSlope, MitigationOpenings, ...; not written to the Rater) plus the `wind.default.*` values of
`rater-request-mapping.properties` (TigerCQApplicable=Y, ...). The API models the Gross AAL itself, so after the API call each
wind case is recalculated in the Rater with the API's Gross AAL as row 44 (`rater.wind.*` in `rater.properties`).
In a state where TigerCQ does not apply (`rater.request.stateRule.tigerCQNA.states`) a wind case is rated without wind
(`rater.wind.offRule`): the Rater gets PLWindCoverage `No`, and the request keeps the wind request's shape with
PLWindCoverage `No` and TigerCQApplicable `N` (TigerCQApplicable `N` with wind `Yes` is rejected with HTTP 500).

Address: every request gets a real address inside the case's ZIP, County and State (test data), fetched at run time from
OpenStreetMap Nominatim (`RaterGeocoder`): `LocationFullAddress` (e.g. `4298 Division Street, CHINCOTEAGUE, VA 23336, United
States`), `City`, `Latitude`, `Longitude`. The fetched address is checked against the State, ZIP and County; State, County and
`Zipcode1` stay the test data values. The County is spelled as in `config/rater-county-table.txt` (the Rater's County sheet) for the case's
subline (`rater.address.countyFile`); a test data county that is not there (e.g. a Connecticut planning region) is replaced by
the ZIP's county for the Rater and the request alike, so both rate the same territory. Lookups are cached in
`target/rater-output/geocode-cache.json` (one request a second; the first full run looks up the ~620 locations once).

Report results: `Pass` / `Fail`; `N/A` = the Rater marks the attribute as not calculated for the configuration (or the API
never returns it, e.g. WindAalFactor / Gross AAL without wind: `rater.compare.windOnly`); `Skipped` = case not run. The
`Configurations` sheet summarises each configuration.

Speed (a full run of ~6,500 cases):
- The Rater is recalculated with manual calculation while the inputs are written and one full recalculation per case
  (the same values as before, about 7x faster), and only the inputs that change from the previous case are written.
- The Rater runs in the background and hands over each case as soon as it is calculated; the API calls start at once and
  run `rater.api.threads` (default 4) at a time, each worker with its own connection. `1` = one call after the other.
- The console shows `Rater: n/total done` and `API: n/total done` with the time left.
- On HTTP 401 (expired token) the run waits up to `rater.api.tokenWaitMinutes` (default 15) for `APIToken.txt` to be
  updated, then sends the call again, instead of failing every remaining case.

## UI vs API Validation

Each test case is sent to the API (**Expected** = the API response) and entered in the UI (**Actual** = the values the UI
shows), with the same inputs. The Rater is not used.

```bash
mvn compile exec:java -Dexec.mainClass=com.automation.rater.UiApiValidationRunner
# the cases: rater.iterations (one iteration = the API call + its UI flow); e.g. -Drater.iterations=7350
```

- **Plug in the UI flow:** implement `com.automation.ui.UiRatingFlow` (Java + Playwright) and set `ui.flow.class` in
  `rater.properties`. `rate(UiCase)` gets the inputs keyed by API attribute name (`SublineName`, `Zipcode1`,
  `WindDeductiblePercentage` ...; `Parent.Name` when a name repeats, e.g. `PLWindCoverage.CoverageSelected`), the
  configuration, the transaction type, the full request JSON and the output keys to return. It returns `UiResult.of(outputs)`
  (output values keyed the same way, formatted as the API returns them) or `UiResult.failed(message, screenshot)`.
- One flow instance per worker thread (`ui.threads`, default 1): open the browser in the constructor, close it in `close()`.
- The default `ApiEchoUiRatingFlow` is a **stub**: it opens no UI and echoes the API response (everything passes, remarks say
  "STUB") - only to check the plumbing until the real flow is set.
- Output: `target/ui-output/Run_<stamp>/` with `UiValidationReport_<stamp>.xlsx` (Expected (API) / Actual (UI)),
  `ui-attributes.txt` (output keys -> API response paths) and `iterations/<id>/` (`request.json`, `response.json`,
  `ui-outputs.json`).
- Results: Pass / Fail (same tolerances as the Rater validation); N/A = the UI does not show the attribute, or neither the API
  nor the UI has it; a failed UI flow or API call fails the whole case with its message.
- **With the Rater validation (`ui.enabled=true`, `testng.xml`)**: each iteration of `rater.iterations` runs as its API call
  (compared with the Rater), then at once its UI flow with the same test data, then the next iteration - one iteration at a
  time. Both reports are in the same `target/rater-output/Run_<stamp>/`: `RaterValidationReport_*` (API vs Rater) and
  `UiValidationReport_*` (API vs UI); `iterations/<id>/` holds the API and UI files of the iteration.
- **Real UI (`RtUiRatingFlow`)**: logs in from `.env`, then per iteration runs the ConfiguredNew flow with data from the
  iteration's **own TestData row only**: the values of its API request (`config/ui-input-mapping.properties`: UI field =
  request JsonPath, `value.<field>.<API value>` = UI wording) + its `UI:<field>` columns (UI-only fields such as
  `UI:OfficeName`, `UI:Agency_name`, `UI:OccupationType`; ignored by the API side). Saved per iteration: `ui-input.json`, `ui-network/` (the UI's own XHR / fetch
  calls, secrets masked), `ui-response.json`, screenshot. Set `ui.response.urlPattern` to the UI's rating API to compare
  its response (same paths as the API response); until then the outputs are N/A.

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
