package com.automation.rater;

import java.util.LinkedHashMap;
import java.util.Map;

/** One test case: the value for every Rater input row (row number -> value). */
public record RaterTestCase(String id, Map<Integer, String> inputs) {
    public RaterTestCase(String id) {
        this(id, new LinkedHashMap<>());
    }
}
