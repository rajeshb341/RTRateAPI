package com.automation.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseData {
    private int rowNumber;
    private String testCaseId;
    private String testCaseName;
    private String description;
    private String endpoint;
    private String httpMethod;
    private String headers;
    private String requestBody;
    private String queryParams;
    private String pathParams;
    private String expectedStatusCode;
    private String expectedResponseBody;
    private String jsonPathExpressions;
    private String expectedValues;
    private String testData;
    private String enabled;
    private String priority;
    private String category;
    
    @Builder.Default
    private Map<String, String> actualValues = new LinkedHashMap<>();
    
    @Builder.Default
    private Map<String, String> passFailStatus = new LinkedHashMap<>();
    
    @Builder.Default
    private String overallStatus = "PENDING";
    
    @Builder.Default
    private String errorMessage = "";
    
    @Builder.Default
    private long executionTimeMs = 0;
    
    @Builder.Default
    private String responseBody = "";
    
    @Builder.Default
    private int actualStatusCode = 0;

    public boolean isEnabled() {
        return "Y".equalsIgnoreCase(enabled) || "YES".equalsIgnoreCase(enabled) || "TRUE".equalsIgnoreCase(enabled);
    }

    public int getExpectedStatusCodeAsInt() {
        try {
            return Integer.parseInt(expectedStatusCode);
        } catch (NumberFormatException e) {
            return 200;
        }
    }
}