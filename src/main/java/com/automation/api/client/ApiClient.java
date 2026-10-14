package com.automation.api.client;

import com.automation.api.model.ApiRequest;
import com.automation.api.model.ApiResponse;
import com.automation.config.Config;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ApiClient implements AutoCloseable {
    private final Config config;
    private final Playwright playwright;
    private final APIRequestContext requestContext;
    private final Map<String, String> defaultHeaders;

    public ApiClient() {
        this(Config.getInstance());
    }

    public ApiClient(Config config) {
        this.config = config;
        this.playwright = Playwright.create();
        this.defaultHeaders = new HashMap<>();
        
        setupDefaultHeaders();
        
        this.requestContext = playwright.request().newContext(new APIRequest.NewContextOptions()
            .setBaseURL(config.getBaseUrl())
            .setExtraHTTPHeaders(defaultHeaders)
            .setTimeout(config.getTimeoutMs())
            .setIgnoreHTTPSErrors(true)
        );
        
        log.info("API Client initialized with base URL: {}", config.getBaseUrl());
    }

    private void setupDefaultHeaders() {
        defaultHeaders.put("Content-Type", config.getDefaultContentType());
        defaultHeaders.put("Accept", "application/json");
        
        if (config.getAuthToken() != null && !config.getAuthToken().isEmpty()) {
            defaultHeaders.put("Authorization", "Bearer " + config.getAuthToken());
        }
        
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            defaultHeaders.put("X-API-Key", config.getApiKey());
        }
    }

    public ApiResponse execute(ApiRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            String resolvedUrl = resolveUrl(request.getUrl(), request.getPathParams());
            
            RequestOptions options = RequestOptions.create()
                .setMethod(request.getMethod())
                .setData(request.getBody())
                .setTimeout(config.getTimeoutMs());
            if (request.getHeaders() != null) {
                request.getHeaders().forEach(options::setHeader);
            }
            if (request.getQueryParams() != null) {
                request.getQueryParams().forEach(options::setQueryParam);
            }

            if (config.isFollowRedirects()) {
                options.setMaxRedirects(10);
            }

            log.debug("Executing {} request to: {}", request.getMethod(), resolvedUrl);
            if (log.isDebugEnabled() && request.getHeaders() != null) {
                Map<String, String> shown = new HashMap<>(request.getHeaders());
                shown.replaceAll((k, v) -> k.equalsIgnoreCase("Token") || k.equalsIgnoreCase("Authorization") ? "****" : v);
                log.debug("Headers: {}", shown);
            }
            log.debug("Query Params: {}", request.getQueryParams());
            log.debug("Body: {}", request.getBody());

            APIResponse apiResponse = requestContext.fetch(resolvedUrl, options);
            
            long responseTime = System.currentTimeMillis() - startTime;
            
            return buildResponse(apiResponse, request, responseTime);
            
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("API request failed: {}", e.getMessage(), e);
            return buildErrorResponse(request, responseTime, e);
        }
    }

    public CompletableFuture<ApiResponse> executeAsync(ApiRequest request) {
        return CompletableFuture.supplyAsync(() -> execute(request));
    }

    private String resolveUrl(String url, Map<String, String> pathParams) {
        if (url == null) return "";
        
        String resolved = url;
        if (pathParams != null) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
                resolved = resolved.replace(":" + entry.getKey(), entry.getValue());
            }
        }
        return resolved;
    }

    private ApiResponse buildResponse(APIResponse apiResponse, ApiRequest request, long responseTime) {
        Map<String, String> responseHeaders = new HashMap<>();
        apiResponse.headers().forEach((k, v) -> responseHeaders.put(k, String.join(", ", v)));
        
        String responseBody = "";
        try {
            responseBody = apiResponse.text();
        } catch (Exception e) {
            log.warn("Could not read response body: {}", e.getMessage());
        }

        return ApiResponse.builder()
            .statusCode(apiResponse.status())
            .statusText(apiResponse.statusText())
            .headers(responseHeaders)
            .body(responseBody)
            .contentType(apiResponse.headers().getOrDefault("content-type", "unknown"))
            .responseTimeMs(responseTime)
            .url(request.getUrl())
            .method(request.getMethod())
            .build();
    }

    private ApiResponse buildErrorResponse(ApiRequest request, long responseTime, Exception e) {
        return ApiResponse.builder()
            .statusCode(0)
            .statusText("ERROR: " + e.getClass().getSimpleName())
            .headers(new HashMap<>())
            .body(e.getMessage())
            .contentType("text/plain")
            .responseTimeMs(responseTime)
            .url(request.getUrl())
            .method(request.getMethod())
            .build();
    }

    public void setAuthToken(String token) {
        defaultHeaders.put("Authorization", "Bearer " + token);
        config.setAuthToken(token);
    }

    public void setApiKey(String apiKey) {
        defaultHeaders.put("X-API-Key", apiKey);
        config.setApiKey(apiKey);
    }

    public void addDefaultHeader(String key, String value) {
        defaultHeaders.put(key, value);
    }

    public void removeDefaultHeader(String key) {
        defaultHeaders.remove(key);
    }

    public Map<String, String> getDefaultHeaders() {
        return new HashMap<>(defaultHeaders);
    }

    @Override
    public void close() {
        try {
            if (requestContext != null) {
                requestContext.dispose();
            }
            if (playwright != null) {
                playwright.close();
            }
            log.info("API Client closed");
        } catch (Exception e) {
            log.warn("Error closing API client: {}", e.getMessage());
        }
    }

    public APIRequestContext getRequestContext() {
        return requestContext;
    }
}