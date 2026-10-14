package com.automation.jsonpath;

import com.automation.config.TestCaseData;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

@Slf4j
public class JsonPathExtractor {
    private static final Configuration JSON_PATH_CONFIG = Configuration.builder()
        .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
        .options(Option.SUPPRESS_EXCEPTIONS)
        .build();

    public Map<String, String> extractAll(String jsonBody, String jsonPathExpressions) {
        Map<String, String> results = new LinkedHashMap<>();
        
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            log.warn("Empty JSON body provided for extraction");
            return results;
        }
        
        if (jsonPathExpressions == null || jsonPathExpressions.trim().isEmpty()) {
            log.debug("No JsonPath expressions provided");
            return results;
        }

        String[] expressions = jsonPathExpressions.split("\\r?\\n");
        for (String expression : expressions) {
            expression = expression.trim();
            if (expression.isEmpty() || expression.startsWith("#")) {
                continue;
            }
            
            // Support format: "alias=$.path.to.value" or just "$.path.to.value"
            String alias = expression;
            String path = expression;
            
            int equalsIndex = expression.indexOf('=');
            if (equalsIndex > 0) {
                alias = expression.substring(0, equalsIndex).trim();
                path = expression.substring(equalsIndex + 1).trim();
            }
            
            String value = extractSingle(jsonBody, path);
            results.put(alias, value);
            
            log.debug("Extracted [{}] = [{}] from path: {}", alias, value, path);
        }
        
        return results;
    }

    public String extractSingle(String jsonBody, String jsonPath) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            return "NULL_BODY";
        }
        
        if (jsonPath == null || jsonPath.trim().isEmpty()) {
            return "EMPTY_PATH";
        }
        
        try {
            Object result = JsonPath.using(JSON_PATH_CONFIG).parse(jsonBody).read(jsonPath.trim());
            
            if (result == null) {
                return "NULL";
            }
            
            if (result instanceof List) {
                List<?> list = (List<?>) result;
                if (list.isEmpty()) {
                    return "EMPTY_ARRAY";
                }
                return list.toString();
            }
            
            return result.toString();
            
        } catch (PathNotFoundException e) {
            log.debug("JsonPath not found: {} - {}", jsonPath, e.getMessage());
            return "NOT_FOUND";
        } catch (Exception e) {
            log.warn("Error extracting JsonPath '{}': {}", jsonPath, e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    public Map<String, String> extractAndCompare(TestCaseData testCase, String responseBody) {
        Map<String, String> actualValues = extractAll(responseBody, testCase.getJsonPathExpressions());
        Map<String, String> passFailStatus = new LinkedHashMap<>();
        
        if (testCase.getExpectedValues() != null && !testCase.getExpectedValues().trim().isEmpty()) {
            String[] expectedLines = testCase.getExpectedValues().split("\\r?\\n");
            String[] expressionLines = testCase.getJsonPathExpressions().split("\\r?\\n");
            
            for (int i = 0; i < Math.min(expectedLines.length, expressionLines.length); i++) {
                String expectedLine = expectedLines[i].trim();
                String expressionLine = expressionLines[i].trim();
                
                if (expectedLine.isEmpty() || expectedLine.startsWith("#")) continue;
                if (expressionLine.isEmpty() || expressionLine.startsWith("#")) continue;
                
                String alias = expressionLine;
                int equalsIndex = expressionLine.indexOf('=');
                if (equalsIndex > 0) {
                    alias = expressionLine.substring(0, equalsIndex).trim();
                }
                
                String actualValue = actualValues.get(alias);
                boolean passed = compareValues(expectedLine, actualValue);
                passFailStatus.put(alias, passed ? "PASS" : "FAIL");
                
                log.debug("Comparison [{}] Expected: [{}] Actual: [{}] Result: {}", 
                    alias, expectedLine, actualValue, passed ? "PASS" : "FAIL");
            }
        }
        
        // Also check status code
        String statusKey = "statusCode";
        int expectedStatus = testCase.getExpectedStatusCodeAsInt();
        int actualStatus = testCase.getActualStatusCode();
        boolean statusPassed = expectedStatus == actualStatus;
        passFailStatus.put(statusKey, statusPassed ? "PASS" : "FAIL");
        actualValues.put(statusKey, String.valueOf(actualStatus));
        
        testCase.getActualValues().putAll(actualValues);
        testCase.getPassFailStatus().putAll(passFailStatus);
        
        // Determine overall status
        boolean allPassed = passFailStatus.values().stream().allMatch("PASS"::equals);
        testCase.setOverallStatus(allPassed ? "PASS" : "FAIL");
        
        return actualValues;
    }

    private boolean compareValues(String expected, String actual) {
        if (expected == null && actual == null) return true;
        if (expected == null || actual == null) return false;
        
        // Handle special values
        if ("NULL".equals(expected) && ("NULL".equals(actual) || "NOT_FOUND".equals(actual))) return true;
        if ("EMPTY".equals(expected) && actual.isEmpty()) return true;
        if ("EMPTY_ARRAY".equals(expected) && "EMPTY_ARRAY".equals(actual)) return true;
        if ("NOT_FOUND".equals(expected) && "NOT_FOUND".equals(actual)) return true;
        
        // Try numeric comparison
        try {
            double expNum = Double.parseDouble(expected);
            double actNum = Double.parseDouble(actual);
            return Math.abs(expNum - actNum) < 0.0001;
        } catch (NumberFormatException ignored) {
            // Not numbers, do string comparison
        }
        
        // Try boolean comparison
        if ("true".equalsIgnoreCase(expected) || "false".equalsIgnoreCase(expected)) {
            return expected.equalsIgnoreCase(actual);
        }
        
        // String comparison (trim both)
        return expected.trim().equals(actual.trim());
    }

    public boolean validateJsonPath(String jsonBody, String jsonPath) {
        try {
            JsonPath.using(JSON_PATH_CONFIG).parse(jsonBody).read(jsonPath);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Object readRaw(String jsonBody, String jsonPath) {
        try {
            return JsonPath.using(JSON_PATH_CONFIG).parse(jsonBody).read(jsonPath);
        } catch (Exception e) {
            return null;
        }
    }
}