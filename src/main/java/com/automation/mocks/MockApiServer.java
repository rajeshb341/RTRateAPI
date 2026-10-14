package com.automation.mocks;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class MockApiServer implements AutoCloseable {
    private final Playwright playwright;
    private final int port;
    private final Map<String, MockEndpoint> endpoints = new ConcurrentHashMap<>();
    private boolean running = false;

    public MockApiServer(int port) {
        this.port = port;
        this.playwright = Playwright.create();
        setupDefaultEndpoints();
    }

    public void start() {
        if (running) {
            log.warn("Mock server already running on port {}", port);
            return;
        }
        
        running = true;
        log.info("Mock API Server started on port {}", port);
        log.info("Available endpoints:");
        endpoints.forEach((path, endpoint) -> 
            log.info("  {} {} -> {}", endpoint.getMethod(), path, endpoint.getDescription()));
    }

    public void stop() {
        running = false;
        log.info("Mock API Server stopped");
    }

    @Override
    public void close() {
        stop();
        if (playwright != null) {
            playwright.close();
        }
    }

    public void addEndpoint(String method, String path, MockEndpoint endpoint) {
        String key = method.toUpperCase() + ":" + path;
        endpoints.put(key, endpoint);
        log.debug("Added mock endpoint: {} {}", method, path);
    }

    public void addEndpoint(String method, String path, int statusCode, String responseBody, String description) {
        addEndpoint(method, path, new MockEndpoint(method, path, statusCode, responseBody, description));
    }

    private void setupDefaultEndpoints() {
        // GET /api/users/{userId}
        addEndpoint("GET", "/api/users/123", 200, """
            {
              "id": 123,
              "name": "John Doe",
              "email": "john.doe@example.com",
              "status": "active",
              "createdAt": "2024-01-15T10:30:00Z",
              "roles": ["USER", "ADMIN"],
              "profile": {
                "firstName": "John",
                "lastName": "Doe",
                "avatar": "https://example.com/avatar/123.png"
              }
            }
            """, "Get user by ID");

        // GET /api/users
        addEndpoint("GET", "/api/users", 200, """
            {
              "users": [
                {"id": 1, "name": "John Doe", "email": "john@example.com", "status": "active"},
                {"id": 2, "name": "Jane Smith", "email": "jane@example.com", "status": "inactive"},
                {"id": 3, "name": "Bob Wilson", "email": "bob@example.com", "status": "active"}
              ],
              "total": 3,
              "page": 1,
              "pageSize": 10
            }
            """, "List users");

        // POST /api/users
        addEndpoint("POST", "/api/users", 201, """
            {
              "id": 124,
              "name": "New User",
              "email": "newuser@example.com",
              "status": "active",
              "createdAt": "2024-01-15T10:30:00Z"
            }
            """, "Create new user");

        // PUT /api/users/{userId}
        addEndpoint("PUT", "/api/users/123", 200, """
            {
              "id": 123,
              "name": "John Doe Updated",
              "email": "john.doe.updated@example.com",
              "status": "active",
              "updatedAt": "2024-01-15T11:00:00Z"
            }
            """, "Update user");

        // DELETE /api/users/{userId}
        addEndpoint("DELETE", "/api/users/123", 204, "", "Delete user");

        // GET /api/health
        addEndpoint("GET", "/api/health", 200, """
            {
              "status": "UP",
              "version": "1.0.0",
              "timestamp": "2024-01-15T10:30:00Z"
            }
            """, "Health check");

        // POST /api/login
        addEndpoint("POST", "/api/login", 200, """
            {
              "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.mock.token",
              "expiresIn": 3600,
              "user": {
                "id": 123,
                "name": "John Doe",
                "email": "john.doe@example.com"
              }
            }
            """, "User login");

        // Error endpoints
        addEndpoint("GET", "/api/error/400", 400, """
            {"error": "Bad Request", "message": "Invalid input parameters"}
            """, "Bad request error");
        
        addEndpoint("GET", "/api/error/401", 401, """
            {"error": "Unauthorized", "message": "Invalid or expired token"}
            """, "Unauthorized error");
        
        addEndpoint("GET", "/api/error/404", 404, """
            {"error": "Not Found", "message": "Resource not found"}
            """, "Not found error");
        
        addEndpoint("GET", "/api/error/500", 500, """
            {"error": "Internal Server Error", "message": "Something went wrong"}
            """, "Internal server error");
    }

    public APIResponse handleRequest(String method, String path, Map<String, String> headers, String body) {
        String key = method.toUpperCase() + ":" + path;
        MockEndpoint endpoint = endpoints.get(key);
        
        // Try with path parameters
        if (endpoint == null) {
            for (Map.Entry<String, MockEndpoint> entry : endpoints.entrySet()) {
                String pattern = entry.getKey().split(":")[1];
                if (matchPath(pattern, path)) {
                    endpoint = entry.getValue();
                    break;
                }
            }
        }
        
        if (endpoint == null) {
            log.warn("No mock endpoint for: {} {}", method, path);
            return createResponse(404, "{\"error\":\"Not Found\",\"message\":\"No mock endpoint for " + method + " " + path + "\"}");
        }
        
        log.info("Mock response: {} {} -> {}", method, path, endpoint.getStatusCode());
        return createResponse(endpoint.getStatusCode(), endpoint.getResponseBody());
    }

    private boolean matchPath(String pattern, String path) {
        // Simple path parameter matching
        String regex = pattern.replaceAll("\\{[^}]+\\}", "[^/]+");
        return path.matches(regex);
    }

    private APIResponse createResponse(int statusCode, String body) {
        // This is a simplified mock - in reality we'd need to use Playwright's mock API
        // For now, return a mock-like response
        return null; // Playwright handles this differently
    }

    public static class MockEndpoint {
        private final String method;
        private final String path;
        private final int statusCode;
        private final String responseBody;
        private final String description;

        public MockEndpoint(String method, String path, int statusCode, String responseBody, String description) {
            this.method = method;
            this.path = path;
            this.statusCode = statusCode;
            this.responseBody = responseBody;
            this.description = description;
        }

        public String getMethod() { return method; }
        public String getPath() { return path; }
        public int getStatusCode() { return statusCode; }
        public String getResponseBody() { return responseBody; }
        public String getDescription() { return description; }
    }
}