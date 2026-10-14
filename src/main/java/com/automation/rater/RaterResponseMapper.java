package com.automation.rater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

/** Reads the Actual value of an output row from the API response, using the row -> JsonPath mapping from config. */
public class RaterResponseMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Configuration JSON_CONFIG = Configuration.builder()
        .jsonProvider(new JacksonJsonNodeJsonProvider())
        .mappingProvider(new JacksonMappingProvider())
        .build();

    /** NOT_MAPPED = no path configured; NOT_FOUND = path not present in the response; FOUND = value read. */
    public enum Status { NOT_MAPPED, NOT_FOUND, FOUND, BAD_RESPONSE }

    public record Actual(Status status, String value, String detail) {}

    private final RaterSettings settings;
    private final DocumentContext document;
    private final String parseError;

    public RaterResponseMapper(RaterSettings settings, String responseBody) {
        this.settings = settings;
        DocumentContext ctx = null;
        String error = null;
        try {
            MAPPER.readTree(responseBody);      // fail early with a clear message if it is not JSON
            ctx = JsonPath.using(JSON_CONFIG).parse(responseBody);
        } catch (Exception e) {
            error = "Response is not valid JSON: " + e.getMessage();
        }
        this.document = ctx;
        this.parseError = error;
    }

    public Actual read(int row) {
        String path = settings.responsePath(row);
        if (path.isEmpty()) {
            return new Actual(Status.NOT_MAPPED, "", "No response mapping configured for row " + row);
        }
        if (document == null) {
            return new Actual(Status.BAD_RESPONSE, "", parseError);
        }
        try {
            JsonNode node = document.read(path, JsonNode.class);
            return new Actual(Status.FOUND, JsonText.of(node), path);
        } catch (PathNotFoundException e) {
            return new Actual(Status.NOT_FOUND, "", "Attribute not found in response: " + path);
        } catch (Exception e) {
            return new Actual(Status.BAD_RESPONSE, "", "Cannot read " + path + ": " + e.getMessage());
        }
    }
}
