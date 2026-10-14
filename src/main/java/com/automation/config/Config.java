package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@Slf4j
public class Config {
    private static Config instance;
    private final Properties properties = new Properties();
    
    @Getter @Setter private Environment environment = Environment.LOCAL;
    @Getter @Setter private String baseUrl;
    @Getter @Setter private int timeoutMs = 30000;
    @Getter @Setter private int retryCount = 3;
    @Getter @Setter private boolean followRedirects = true;
    @Getter @Setter private String excelFilePath = "src/test/resources/testdata/test-data.xlsx";
    @Getter @Setter private String sheetName = "TestCases";
    @Getter @Setter private int startRow = 1;
    @Getter @Setter private int endRow = -1;
    @Getter @Setter private String reportOutputDir = "target/allure-results";
    @Getter @Setter private boolean headless = true;
    @Getter @Setter private String defaultContentType = "application/json";
    @Getter @Setter private String authToken;
    @Getter @Setter private String apiKey;

    private Config() {
        loadConfiguration();
    }

    public static synchronized Config getInstance() {
        if (instance == null) {
            instance = new Config();
        }
        return instance;
    }

    public static void reset() {
        instance = null;
    }

    private void loadConfiguration() {
        loadFromPropertiesFile();
        loadFromSystemProperties();
        loadFromEnvironmentVariables();
        applyDefaults();
        logConfiguration();
    }

    private void loadFromPropertiesFile() {
        // Later files override earlier ones: defaults first, then the environment-specific file.
        String[] configFiles = {
            "application.properties",
            "config/default.properties",
            "config/" + environment.name().toLowerCase() + ".properties"
        };

        for (String configFile : configFiles) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(configFile)) {
                if (input != null) {
                    properties.load(input);
                    log.info("Loaded configuration from: {}", configFile);
                }
            } catch (Exception e) {
                log.debug("Could not load config file {}: {}", configFile, e.getMessage());
            }
        }
    }

    private void loadFromSystemProperties() {
        String env = System.getProperty("environment");
        if (env != null) {
            this.environment = Environment.fromString(env);
        }
        
        String baseUrl = System.getProperty("baseUrl");
        if (baseUrl != null) {
            this.baseUrl = baseUrl;
        }
        
        String excelPath = System.getProperty("excelFilePath");
        if (excelPath != null) {
            this.excelFilePath = excelPath;
        }
        
        String sheet = System.getProperty("sheetName");
        if (sheet != null) {
            this.sheetName = sheet;
        }
        
        String timeout = System.getProperty("timeoutMs");
        if (timeout != null) {
            this.timeoutMs = Integer.parseInt(timeout);
        }
    }

    private void loadFromEnvironmentVariables() {
        String env = System.getenv("ENVIRONMENT");
        if (env != null) {
            this.environment = Environment.fromString(env);
        }
        
        String baseUrl = System.getenv("BASE_URL");
        if (baseUrl != null) {
            this.baseUrl = baseUrl;
        }
        
        String authToken = System.getenv("AUTH_TOKEN");
        if (authToken != null) {
            this.authToken = authToken;
        }
        
        String apiKey = System.getenv("API_KEY");
        if (apiKey != null) {
            this.apiKey = apiKey;
        }
    }

    private void applyDefaults() {
        if (this.baseUrl == null || this.baseUrl.isEmpty()) {
            this.baseUrl = this.environment.getBaseUrl();
        }
        
        if (this.excelFilePath == null || this.excelFilePath.isEmpty()) {
            this.excelFilePath = "src/test/resources/testdata/test-data.xlsx";
        }
        
        Path excelPath = Paths.get(this.excelFilePath);
        if (!excelPath.isAbsolute()) {
            this.excelFilePath = Paths.get("").toAbsolutePath().resolve(excelPath).toString();
        }
    }

    private void logConfiguration() {
        log.info("=== Framework Configuration ===");
        log.info("Environment: {}", environment.getName());
        log.info("Base URL: {}", baseUrl);
        log.info("Timeout (ms): {}", timeoutMs);
        log.info("Retry Count: {}", retryCount);
        log.info("Excel File: {}", excelFilePath);
        log.info("Sheet Name: {}", sheetName);
        log.info("Report Output: {}", reportOutputDir);
        log.info("Headless: {}", headless);
        log.info("================================");
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }
    
    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
    
    public int getIntProperty(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid integer value for property '{}': {}", key, value);
            }
        }
        return defaultValue;
    }
    
    public boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }
}