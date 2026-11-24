package com.automation.rater;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One test case: the value for every Rater input row (row number -> value), plus request-only attributes the Rater has no
 * row for (test data columns "Request:&lt;Name&gt;", e.g. the wind attributes; name -> value).
 */
public record RaterTestCase(String id, Map<Integer, String> inputs, Map<String, String> requestValues) {
    public RaterTestCase(String id) {
        this(id, new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /** The same case with other Rater inputs (request-only values kept). */
    public RaterTestCase withInputs(Map<Integer, String> newInputs) {
        return new RaterTestCase(id, new LinkedHashMap<>(newInputs), requestValues);
    }
}
