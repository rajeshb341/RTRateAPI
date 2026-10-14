package com.automation.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiRequest {
    private String url;
    private String method;
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private Map<String, String> pathParams;
    private Object body;
    private String bodyType;

    public static ApiRequestBuilder builder() {
        return new ApiRequestBuilder();
    }

    public static class ApiRequestBuilder {
        private Map<String, String> headers = new LinkedHashMap<>();
        private Map<String, String> queryParams = new LinkedHashMap<>();
        private Map<String, String> pathParams = new LinkedHashMap<>();

        public ApiRequestBuilder header(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        public ApiRequestBuilder queryParam(String key, String value) {
            this.queryParams.put(key, value);
            return this;
        }

        public ApiRequestBuilder pathParam(String key, String value) {
            this.pathParams.put(key, value);
            return this;
        }
    }
}