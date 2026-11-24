package com.automation.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the UI flow returns for one test case.
 *
 * @param outputs    the output values shown on screen, keyed by API attribute name as in {@link UiCase#outputKeys()},
 *                   formatted as the API returns them (e.g. "1536", "0.85"); a key that is not returned is reported N/A
 * @param error      null when the flow completed; otherwise why it failed (every attribute of the case is reported Fail)
 * @param screenshot optional screenshot (e.g. of the failure or of the result page), shown in the report remarks
 */
public record UiResult(Map<String, String> outputs, String error, Path screenshot) {

    public UiResult {
        outputs = outputs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(outputs));
    }

    public static UiResult of(Map<String, String> outputs) {
        return new UiResult(outputs, null, null);
    }

    public static UiResult of(Map<String, String> outputs, Path screenshot) {
        return new UiResult(outputs, null, screenshot);
    }

    public static UiResult failed(String error, Path screenshot) {
        return new UiResult(Map.of(), error == null || error.isBlank() ? "UI flow failed" : error, screenshot);
    }
}
