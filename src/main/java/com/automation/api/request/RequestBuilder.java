package com.automation.api.request;

import com.automation.api.model.ApiRequest;
import com.automation.config.TestCaseData;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class RequestBuilder {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(.+?)\\}\\}");
    
    private final Map<String, String> variables = new LinkedHashMap<>();

    public RequestBuilder() {}

    public RequestBuilder variable(String key, String value) {
        variables.put(key, value);
        return this;
    }

    public RequestBuilder variables(Map<String, String> vars) {
        if (vars != null) {
            variables.putAll(vars);
        }
        return this;
    }

    public ApiRequest build(TestCaseData testCase) {
        String baseUrl = testCase.getEndpoint();
        
        // Build headers
        Map<String, String> headers = parseKeyValuePairs(testCase.getHeaders());
        headers = resolvePlaceholders(headers);
        
        // Build query params
        Map<String, String> queryParams = parseKeyValuePairs(testCase.getQueryParams());
        queryParams = resolvePlaceholders(queryParams);
        
        // Build path params
        Map<String, String> pathParams = parseKeyValuePairs(testCase.getPathParams());
        pathParams = resolvePlaceholders(pathParams);
        
        // Resolve body
        String body = resolvePlaceholders(testCase.getRequestBody());
        
        // Resolve URL with path params
        String resolvedUrl = resolvePathParams(baseUrl, pathParams);

        return ApiRequest.builder()
            .url(resolvedUrl)
            .method(testCase.getHttpMethod())
            .headers(headers)
            .queryParams(queryParams)
            .pathParams(pathParams)
            .body(body.isEmpty() ? null : body)
            .bodyType(determineBodyType(headers))
            .build();
    }

    private Map<String, String> parseKeyValuePairs(String input) {
        Map<String, String> result = new LinkedHashMap<>();
        if (input == null || input.trim().isEmpty()) {
            return result;
        }

        String[] lines = input.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                result.put(key, value);
            }
        }
        return result;
    }

    private Map<String, String> resolvePlaceholders(Map<String, String> map) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            resolved.put(entry.getKey(), resolvePlaceholders(entry.getValue()));
        }
        return resolved;
    }

    private String resolvePlaceholders(String input) {
        if (input == null) return "";
        
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = variables.getOrDefault(placeholder, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }

    private String resolvePathParams(String url, Map<String, String> pathParams) {
        if (url == null || pathParams == null || pathParams.isEmpty()) {
            return url;
        }
        
        String resolved = url;
        for (Map.Entry<String, String> entry : pathParams.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
            resolved = resolved.replace(":" + entry.getKey(), entry.getValue());
        }
        return resolved;
    }

    private String determineBodyType(Map<String, String> headers) {
        String contentType = headers.get("Content-Type");
        if (contentType != null) {
            if (contentType.contains("json")) return "JSON";
            if (contentType.contains("xml")) return "XML";
            if (contentType.contains("form")) return "FORM";
        }
        return "RAW";
    }

    public static ApiRequest fromTestCase(TestCaseData testCase) {
        return new RequestBuilder().build(testCase);
    }
}