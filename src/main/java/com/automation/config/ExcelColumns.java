package com.automation.config;

public final class ExcelColumns {
    public static final int ROW_NUMBER = 0;
    public static final int TEST_CASE_ID = 1;
    public static final int TEST_CASE_NAME = 2;
    public static final int DESCRIPTION = 3;
    public static final int ENDPOINT = 4;
    public static final int HTTP_METHOD = 5;
    public static final int HEADERS = 6;
    public static final int REQUEST_BODY = 7;
    public static final int QUERY_PARAMS = 8;
    public static final int PATH_PARAMS = 9;
    public static final int EXPECTED_STATUS_CODE = 10;
    public static final int EXPECTED_RESPONSE_BODY = 11;
    public static final int JSON_PATH_EXPRESSIONS = 12;
    public static final int EXPECTED_VALUES = 13;
    public static final int TEST_DATA = 14;
    public static final int ENABLED = 15;
    public static final int PRIORITY = 16;
    public static final int CATEGORY = 17;
    
    // Output columns (written back after execution)
    public static final int ACTUAL_STATUS_CODE = 18;
    public static final int ACTUAL_RESPONSE_BODY = 19;
    public static final int ACTUAL_VALUES = 20;
    public static final int PASS_FAIL_STATUS = 21;
    public static final int OVERALL_STATUS = 22;
    public static final int ERROR_MESSAGE = 23;
    public static final int EXECUTION_TIME_MS = 24;

    public static final String[] HEADER_NAMES = {
        "RowNumber", "TestCaseId", "TestCaseName", "Description", "Endpoint", 
        "HttpMethod", "Headers", "RequestBody", "QueryParams", "PathParams",
        "ExpectedStatusCode", "ExpectedResponseBody", "JsonPathExpressions", 
        "ExpectedValues", "TestData", "Enabled", "Priority", "Category",
        "ActualStatusCode", "ActualResponseBody", "ActualValues", 
        "PassFailStatus", "OverallStatus", "ErrorMessage", "ExecutionTimeMs"
    };

    private ExcelColumns() {
        throw new UnsupportedOperationException("Utility class");
    }
}