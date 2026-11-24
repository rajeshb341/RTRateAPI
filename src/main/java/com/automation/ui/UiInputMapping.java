package com.automation.ui;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * UI field <- API request value (config/ui-input-mapping.properties): fills the UI with the same data as the API request.
 * {@code <UI field>=<JsonPath>} picks the value; {@code value.<UI field>.<API value>=<UI value>} translates it.
 */
public final class UiInputMapping {
    public static final String RESOURCE = "config/ui-input-mapping.properties";

    private final Map<String, String> paths = new LinkedHashMap<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> defaults = new LinkedHashMap<>();

    /** default.&lt;UI field&gt;=&lt;value&gt;: for a UI field neither the API request nor the TestData UI: columns give. */
    public Map<String, String> defaults() {
        return defaults;
    }

    private UiInputMapping() {}

    /** From src/main/resources/config (file on disk first, then the classpath). */
    public static UiInputMapping load() throws IOException {
        Properties p = new Properties();
        Path file = Path.of("src/main/resources", RESOURCE);
        try (InputStream in = Files.exists(file) ? Files.newInputStream(file)
            : UiInputMapping.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException(RESOURCE + " not found");
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return of(p);
    }

    static UiInputMapping of(Properties p) {
        UiInputMapping m = new UiInputMapping();
        // keep the file order as far as Properties allows (sorted is fine: the order does not matter)
        new java.util.TreeMap<>(p).forEach((k, v) -> {
            String key = k.toString().trim();
            String value = v.toString().trim();
            if (key.startsWith("value.")) m.values.put(key.substring("value.".length()), value);
            else if (key.startsWith("default.")) m.defaults.put(key.substring("default.".length()), value);
            else if (!value.isEmpty()) m.paths.put(key, value);
        });
        return m;
    }

    /**
     * The UI data of an iteration: default.&lt;field&gt; values &lt;- its TestData UI: columns (non-empty) &lt;- the values of its
     * API request (mapped fields: the API value always wins, so the UI gets the same data as the API).
     */
    public Map<String, String> uiData(String requestJson, Map<String, String> uiColumns) {
        return uiData(requestJson, uiColumns, Map.of());
    }

    /** As above; {@code rowValues} = the test data row ("row.12" -> "RCV") for fields mapped to row.&lt;N&gt;. */
    public Map<String, String> uiData(String requestJson, Map<String, String> uiColumns, Map<String, String> rowValues) {
        Map<String, String> data = new LinkedHashMap<>(defaults);
        uiColumns.forEach((k, v) -> { if (v != null && !v.isBlank()) data.put(k, v); });
        data.putAll(apply(requestJson, rowValues));
        return data;
    }

    /** The UI fields with their value from the request body (fields whose value is missing or empty are left out). */
    public Map<String, String> apply(String requestJson) {
        return apply(requestJson, Map.of());
    }

    /**
     * As above; a field mapped to "row.&lt;N&gt;" takes the test data value of Rater input row N (e.g. row.12 = the Dwelling
     * Valuation as in the test data, where the request sends the Rater's "ALL" for HO6 / DP Vacant / Builders Risk).
     */
    public Map<String, String> apply(String requestJson, Map<String, String> rowValues) {
        Object document = JsonPath.parse(requestJson).json();
        Map<String, String> result = new LinkedHashMap<>();
        paths.forEach((field, path) -> {
            Object value;
            if (path.startsWith("row.")) {
                value = rowValues.get(path);
            } else {
                try {
                    value = JsonPath.read(document, path);
                } catch (PathNotFoundException notInRequest) {
                    return;
                }
            }
            if (value == null || value instanceof Map || value instanceof java.util.List) return;
            String text = value.toString().trim();
            if (text.isEmpty()) return;
            // value.<field>.<API value>, else value.<field>.* (any non-empty value), else the API value as it is
            result.put(field, values.getOrDefault(field + "." + text, values.getOrDefault(field + ".*", text)));
        });
        return result;
    }
}
