package com.automation.jsonpath;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
public class JsonPathValidator {
    private static final Configuration JSON_PATH_CONFIG = Configuration.builder()
        .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
        .options(Option.SUPPRESS_EXCEPTIONS)
        .build();

    public static ValidationResult validate(String jsonBody, Map<String, String> validations) {
        ValidationResult result = new ValidationResult();
        
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            result.addError("$", "EMPTY_BODY", "non-empty JSON body");
            return result;
        }
        
        for (Map.Entry<String, String> entry : validations.entrySet()) {
            String path = entry.getKey();
            String expected = entry.getValue();
            
            try {
                Object actual = JsonPath.using(JSON_PATH_CONFIG).parse(jsonBody).read(path);
                boolean passed = compareValues(expected, actual);
                
                if (passed) {
                    result.addPass(path, expected, actualToString(actual));
                } else {
                    result.addFail(path, expected, actualToString(actual));
                }
            } catch (Exception e) {
                result.addError(path, "NOT_FOUND", expected);
            }
        }
        
        return result;
    }

    public static ValidationResult validateSchema(String jsonBody, String schemaPath) {
        ValidationResult result = new ValidationResult();
        
        try {
            boolean valid = JsonPath.using(JSON_PATH_CONFIG).parse(jsonBody).read(schemaPath) != null;
            if (valid) {
                result.addPass("schema", "valid", "valid");
            } else {
                result.addFail("schema", "valid", "invalid");
            }
        } catch (Exception e) {
            result.addError("schema", "ERROR", e.getMessage());
        }
        
        return result;
    }

    public static boolean exists(String jsonBody, String jsonPath) {
        try {
            return JsonPath.using(JSON_PATH_CONFIG).parse(jsonBody).read(jsonPath) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static int count(String jsonBody, String jsonPath) {
        try {
            Object result = JsonPath.using(JSON_PATH_CONFIG).parse(jsonBody).read(jsonPath);
            if (result instanceof List) {
                return ((List<?>) result).size();
            }
            return result != null ? 1 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean compareValues(String expected, Object actual) {
        if (expected == null && actual == null) return true;
        if (expected == null || actual == null) return false;
        
        String actualStr = actualToString(actual);
        
        // Handle special values
        if ("NULL".equals(expected) && (actual == null || "NULL".equals(actualStr))) return true;
        if ("EMPTY".equals(expected) && actualStr.isEmpty()) return true;
        
        // Try numeric comparison
        try {
            double expNum = Double.parseDouble(expected);
            double actNum = Double.parseDouble(actualStr);
            return Math.abs(expNum - actNum) < 0.0001;
        } catch (NumberFormatException ignored) {
        }
        
        // Boolean comparison
        if ("true".equalsIgnoreCase(expected) || "false".equalsIgnoreCase(expected)) {
            return expected.equalsIgnoreCase(actualStr);
        }
        
        return expected.trim().equals(actualStr.trim());
    }

    private static String actualToString(Object actual) {
        if (actual == null) return "NULL";
        if (actual instanceof List) {
            List<?> list = (List<?>) actual;
            if (list.isEmpty()) return "EMPTY_ARRAY";
            return list.toString();
        }
        return actual.toString();
    }

    public static class ValidationResult {
        private final Map<String, ValidationEntry> results = new LinkedHashMap<>();
        private int passCount = 0;
        private int failCount = 0;
        private int errorCount = 0;

        public void addPass(String path, String expected, String actual) {
            results.put(path, new ValidationEntry(path, expected, actual, "PASS", null));
            passCount++;
        }

        public void addFail(String path, String expected, String actual) {
            results.put(path, new ValidationEntry(path, expected, actual, "FAIL", null));
            failCount++;
        }

        public void addError(String path, String actual, String expected) {
            results.put(path, new ValidationEntry(path, expected, actual, "ERROR", null));
            errorCount++;
        }

        public boolean isAllPassed() {
            return failCount == 0 && errorCount == 0;
        }

        public int getPassCount() { return passCount; }
        public int getFailCount() { return failCount; }
        public int getErrorCount() { return errorCount; }
        public Map<String, ValidationEntry> getResults() { return results; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ValidationResult{pass=").append(passCount)
              .append(", fail=").append(failCount)
              .append(", error=").append(errorCount)
              .append("}");
            return sb.toString();
        }
    }

    public static class ValidationEntry {
        private final String path;
        private final String expected;
        private final String actual;
        private final String status;
        private final String error;

        public ValidationEntry(String path, String expected, String actual, String status, String error) {
            this.path = path;
            this.expected = expected;
            this.actual = actual;
            this.status = status;
            this.error = error;
        }

        public String getPath() { return path; }
        public String getExpected() { return expected; }
        public String getActual() { return actual; }
        public String getStatus() { return status; }
        public String getError() { return error; }

        @Override
        public String toString() {
            return String.format("[%s] %s: expected=%s, actual=%s", status, path, expected, actual);
        }
    }
}