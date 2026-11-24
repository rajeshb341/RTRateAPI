package com.automation.rater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Settings and attribute mappings for the Rater validation, read from config/rater*.properties. */
public class RaterSettings {
    private static final String CONFIG = "config/rater.properties";

    private static final java.util.regex.Pattern REQUEST_KEY =
        java.util.regex.Pattern.compile("(?:([A-Za-z0-9_]+)\\.)?row\\.(\\d+)(\\.[A-Za-z0-9_]+)?");

    private final Properties props;
    private final Map<Integer, String> requestMapping;
    private final Map<Integer, String> responseMapping;
    /** Every request mapping entry as written: "row.40", "row.40.status", "HO6.row.60" ... -> JSON path (blank kept). */
    private final Map<String, String> requestEntries;
    /** "wind.&lt;Name&gt;" / "wind.default.&lt;Name&gt;" / "address.&lt;Name&gt;" entries of the request mapping, as written. */
    private final Map<String, String> windEntries;

    private RaterSettings(Properties props, Map<Integer, String> requestMapping, Map<Integer, String> responseMapping,
                          Map<String, String> requestEntries) {
        this(props, requestMapping, responseMapping, requestEntries, Map.of());
    }

    private RaterSettings(Properties props, Map<Integer, String> requestMapping, Map<Integer, String> responseMapping,
                          Map<String, String> requestEntries, Map<String, String> windEntries) {
        this.props = props;
        this.requestMapping = requestMapping;
        this.responseMapping = responseMapping;
        this.requestEntries = requestEntries;
        this.windEntries = windEntries;
    }

    /** Loads config/rater.properties and the two mapping files. Any rater.* key can be overridden with -D. */
    public static RaterSettings load() throws IOException {
        Properties props = loadResource(CONFIG);
        RaterSettings base = new RaterSettings(props, Map.of(), Map.of(), Map.of());
        Properties requestProps = loadResource(base.get("rater.requestMapping"));
        Map<Integer, String> response = rowMapping(loadResource(base.get("rater.responseMapping")));
        return new RaterSettings(props, rowMapping(requestProps), response, entries(requestProps), windEntries(requestProps));
    }

    /** Settings with request mapping entries and wind entries ("wind.RoofType", "wind.default.RoofUpdated"), used by tests. */
    public static RaterSettings withWind(Properties props, Map<String, String> requestEntries, Map<String, String> windEntries) {
        RaterSettings s = of(props, requestEntries);
        return new RaterSettings(props, s.requestMapping, Map.of(), s.requestEntries, new LinkedHashMap<>(windEntries));
    }

    private static Map<String, String> windEntries(Properties p) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.trim().startsWith("wind.") || key.trim().startsWith("address.")) map.put(key.trim(), p.getProperty(key).trim());
        }
        return map;
    }

    /** Settings built directly from values (used by tests). */
    public static RaterSettings of(Properties props, Map<Integer, String> requestMapping, Map<Integer, String> responseMapping) {
        Map<String, String> entries = new LinkedHashMap<>();
        requestMapping.forEach((row, path) -> entries.put("row." + row, path));
        return new RaterSettings(props, requestMapping, responseMapping, entries);
    }

    /** Settings with extra request mapping entries ("row.40.status", "HO6.row.60" ...), used by tests. */
    public static RaterSettings of(Properties props, Map<String, String> requestEntries) {
        Map<Integer, String> rows = new LinkedHashMap<>();
        requestEntries.forEach((k, v) -> {
            if (k.matches("row\\.\\d+")) rows.put(Integer.parseInt(k.substring(4)), v.trim());
        });
        return new RaterSettings(props, rows, Map.of(), new LinkedHashMap<>(requestEntries));
    }

    private static Map<String, String> entries(Properties p) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (REQUEST_KEY.matcher(key.trim()).matches()) map.put(key.trim(), p.getProperty(key).trim());
        }
        return map;
    }

    /** A file on disk if the name is an existing path, otherwise a classpath resource. */
    private static Properties loadResource(String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IOException("Missing configuration entry for a Rater mapping file in " + CONFIG);
        }
        Properties p = new Properties();
        Path file = Paths.get(name);
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
            return p;
        }
        try (InputStream in = RaterSettings.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Configuration file not found (file or classpath): " + name);
            }
            p.load(in);
            return p;
        }
    }

    /** "row.104=$.x" entries -> {104 -> "$.x"}; blank paths are kept as blank so "not mapped" can be reported. */
    private static Map<Integer, String> rowMapping(Properties p) {
        Map<Integer, String> map = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.trim().matches("row\\.\\d+")) {
                map.put(Integer.parseInt(key.substring(4).trim()), p.getProperty(key).trim());
            }
        }
        return map;
    }

    public String get(String key) {
        String v = System.getProperty(key, props.getProperty(key));
        return v == null ? "" : v.trim();
    }

    public String get(String key, String defaultValue) {
        String v = get(key);
        return v.isEmpty() ? defaultValue : v;
    }

    public Path path(String key) {
        return Paths.get(get(key));
    }

    public List<Integer> inputRows() {
        return parseRows(get("rater.input.rows"));
    }

    public List<Integer> outputRows() {
        return parseRows(get("rater.output.rows"));
    }

    public String requestPath(int row) {
        return requestMapping.getOrDefault(row, "");
    }

    public String responsePath(int row) {
        return responseMapping.getOrDefault(row, "");
    }

    /**
     * Where an input row goes in the request of a configuration: target key ("40", or "40.status" for an extra attribute
     * fed by the same row) -> JSON path. "&lt;CONFIG&gt;.row.N[.suffix]" entries replace the generic "row.N[.suffix]" ones for
     * that configuration; a blank path means "not sent" and is left out.
     */
    public Map<String, String> requestTargets(String configuration, int row) {
        Map<String, String> generic = new LinkedHashMap<>();
        Map<String, String> specific = new LinkedHashMap<>();
        requestEntries.forEach((key, path) -> {
            java.util.regex.Matcher m = REQUEST_KEY.matcher(key);
            if (!m.matches() || Integer.parseInt(m.group(2)) != row) return;
            String target = row + (m.group(3) == null ? "" : m.group(3));
            if (m.group(1) == null) {
                generic.put(target, path);
            } else if (m.group(1).equalsIgnoreCase(configuration)) {
                specific.put(target, path);
            }
        });
        generic.putAll(specific);
        generic.values().removeIf(String::isBlank);
        return generic;
    }

    /** Wind attribute name -> JSON path ("wind.&lt;Name&gt;" entries of the request mapping). */
    public Map<String, String> windPaths() {
        Map<String, String> map = new LinkedHashMap<>();
        windEntries.forEach((key, path) -> {
            if (key.startsWith("wind.") && !key.startsWith("wind.default.") && !path.isBlank()) {
                map.put(key.substring("wind.".length()), path);
            }
        });
        return map;
    }

    /** Wind attribute name -> value sent when the test data has no "Request:&lt;Name&gt;" column ("wind.default.&lt;Name&gt;"). */
    public Map<String, String> windDefaults() {
        Map<String, String> map = new LinkedHashMap<>();
        windEntries.forEach((key, value) -> {
            if (key.startsWith("wind.default.")) map.put(key.substring("wind.default.".length()), value);
        });
        return map;
    }

    /** Location attribute name -> JSON path ("address.&lt;Name&gt;"): the fetched address sent in every request. */
    public Map<String, String> addressPaths() {
        Map<String, String> map = new LinkedHashMap<>();
        windEntries.forEach((key, path) -> {
            if (key.startsWith("address.") && !path.isBlank()) map.put(key.substring("address.".length()), path);
        });
        return map;
    }

    /** The Rater row whose "Yes" requests wind coverage (rater.wind.row), or -1 when not configured. */
    public int windRow() {
        String v = get("rater.wind.row");
        return v.isEmpty() ? -1 : Integer.parseInt(v);
    }

    /** Values of a list setting ("a, b" or "a|b"), trimmed, blanks dropped. */
    public List<String> list(String key, String separator) {
        return Arrays.stream(get(key).split(java.util.regex.Pattern.quote(separator)))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Rows whose value the Rater derives (formula) and that are sent in the request after recalculation, e.g. the state code. */
    public List<Integer> derivedRequestRows() {
        return parseRows(get("rater.request.derivedRows"));
    }

    /** Values listed in config for an input that has no (usable) dropdown; empty when none are configured. */
    public List<String> overrideValues(int row) {
        String v = get("rater.input." + row + ".values");
        if (v.isEmpty()) return List.of();
        return Arrays.stream(v.split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public double tolerance(int row) {
        String v = get("rater.compare.tolerance." + row, get("rater.compare.tolerance", "0.01"));
        return Double.parseDouble(v);
    }

    /** "11-23,25,27-30" -> [11..23, 25, 27..30] */
    public static List<Integer> parseRows(String spec) {
        List<Integer> rows = new ArrayList<>();
        for (String part : spec.split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            if (part.contains("-")) {
                String[] r = part.split("-");
                for (int i = Integer.parseInt(r[0]); i <= Integer.parseInt(r[1]); i++) rows.add(i);
            } else {
                rows.add(Integer.parseInt(part));
            }
        }
        return rows;
    }
}
