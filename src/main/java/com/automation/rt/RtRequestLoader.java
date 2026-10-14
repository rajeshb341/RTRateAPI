package com.automation.rt;

import com.automation.api.model.ApiRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the RT API request from the three input files:
 * the request body (JSON), the header specification ("Key: Value" per line) and the token ("Token:value").
 */
@Slf4j
public class RtRequestLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path requestFile;
    private final Path specFile;
    private final Path tokenFile;

    public RtRequestLoader(Path requestFile, Path specFile, Path tokenFile) {
        this.requestFile = requestFile;
        this.specFile = specFile;
        this.tokenFile = tokenFile;
    }

    /** Builds a POST request. The URL is left as given (may be null/empty when the endpoint is unknown yet). */
    public ApiRequest load(String url) throws IOException {
        return load(url, null);
    }

    /** Same as {@link #load(String)} but uses the given body instead of the request file (null = read the file). */
    public ApiRequest load(String url, String bodyOverride) throws IOException {
        String body = bodyOverride != null ? bodyOverride : readText(requestFile);
        validateJson(body);

        Map<String, String> headers = new LinkedHashMap<>(parseKeyValues(readText(specFile)));
        headers.putAll(parseKeyValues(readText(tokenFile)));

        // The API rejects the call (TOKEN_MISMATCH_IN_HEADER_PAYLOAD) unless header and body tokens match.
        if (!"false".equalsIgnoreCase(System.getProperty("rtSyncBodyToken")) && headers.containsKey("Token")) {
            body = syncBodyToken(body, headers.get("Token"));
        }

        return ApiRequest.builder()
            .url(url == null ? "" : url.trim())
            .method("POST")
            .headers(headers)
            .body(body)
            .bodyType("JSON")
            .build();
    }

    /** The "Token:value" entry of the token file, or null when the file or entry is missing. */
    public static String readToken(Path tokenFile) {
        try {
            return parseKeyValues(readText(tokenFile)).get("Token");
        } catch (IOException e) {
            log.warn("Could not read token file {}: {}", tokenFile, e.getMessage());
            return null;
        }
    }

    /** Sets ServiceRequestDetail.Token in the body to the given token; other content is untouched. */
    static String syncBodyToken(String body, String token) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode detail = root.path("ServiceRequestDetail");
        if (!detail.isObject()) {
            log.warn("Body has no ServiceRequestDetail object; token not synced");
            return body;
        }
        ((ObjectNode) detail).put("Token", token);
        log.info("Body ServiceRequestDetail.Token set from header token");
        return MAPPER.writeValueAsString(root);
    }

    static Map<String, String> parseKeyValues(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int idx = line.indexOf(':');
            if (idx <= 0) {
                log.warn("Ignoring malformed line (no 'Key:Value'): {}", line.length() > 40 ? line.substring(0, 40) + "..." : line);
                continue;
            }
            result.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return result;
    }

    static void validateJson(String body) throws IOException {
        JsonNode node = MAPPER.readTree(body);
        if (node == null || !node.isObject()) {
            throw new IOException("Request body is not a JSON object");
        }
    }

    /** The request file's text (BOM stripped), e.g. for use as a template. */
    public String readRequestTemplate() throws IOException {
        return readText(requestFile);
    }

    private static String readText(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Input file not found: " + path);
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        // strip UTF-8 BOM if present
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    /** Masks secrets for logging. */
    public static String mask(String key, String value) {
        if (value == null) return "";
        boolean secret = key.equalsIgnoreCase("Token") || key.equalsIgnoreCase("Authorization");
        if (!secret) return value;
        return value.length() <= 8 ? "****" : value.substring(0, 6) + "...(" + value.length() + " chars)";
    }
}
