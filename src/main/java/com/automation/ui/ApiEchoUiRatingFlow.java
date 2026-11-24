package com.automation.ui;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stand-in for the real UI flow until it is ready: it does NOT open any UI - it returns the API response's own values
 * (iterations/&lt;id&gt;/response.json), so every attribute passes. It only proves the plumbing (inputs in, outputs out,
 * report). Never use its results as UI evidence: the run log and the report remarks say "STUB".
 */
public class ApiEchoUiRatingFlow implements UiRatingFlow {
    public static final String STUB = "STUB - no UI opened, values echoed from the API response";

    @Override
    public UiResult rate(UiCase testCase) throws Exception {
        var file = testCase.caseDir().resolve("response.json");
        if (!Files.exists(file)) return UiResult.failed(STUB + ": no response.json", null);
        Object document = JsonPath.parse(Files.readString(file)).json();
        Map<String, String> outputs = new LinkedHashMap<>();
        testCase.outputPaths().forEach((key, path) -> {
            try {
                Object value = JsonPath.read(document, path);
                if (value != null && !(value instanceof Map) && !(value instanceof java.util.List)) outputs.put(key, value.toString());
            } catch (PathNotFoundException notInResponse) {
                // not returned by the API: not "shown" either
            }
        });
        return UiResult.of(outputs);
    }
}
