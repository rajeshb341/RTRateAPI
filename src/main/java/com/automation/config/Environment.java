package com.automation.config;

import lombok.Getter;

@Getter
public enum Environment {
    LOCAL("local", "http://localhost:8080", "Local development environment"),
    DEV("dev", "https://dev-api.example.com", "Development environment"),
    QA("qa", "https://qa-api.example.com", "QA environment"),
    PROD("prod", "https://api.example.com", "Production environment");

    private final String name;
    private final String baseUrl;
    private final String description;

    Environment(String name, String baseUrl, String description) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.description = description;
    }

    public static Environment fromString(String value) {
        for (Environment env : values()) {
            if (env.name.equalsIgnoreCase(value)) {
                return env;
            }
        }
        return LOCAL;
    }
}