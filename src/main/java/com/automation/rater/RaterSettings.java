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

    private final Properties props;
    private final Map<Integer, String> requestMapping;
    private final Map<Integer, String> responseMapping;

    private RaterSettings(Properties props, Map<Integer, String> requestMapping, Map<Integer, String> responseMapping) {
        this.props = props;
        this.requestMapping = requestMapping;
        this.responseMapping = responseMapping;
    }

    /** Loads config/rater.properties and the two mapping files. Any rater.* key can be overridden with -D. */
    public static RaterSettings load() throws IOException {
        Properties props = loadResource(CONFIG);
        RaterSettings base = new RaterSettings(props, Map.of(), Map.of());
        Map<Integer, String> request = rowMapping(loadResource(base.get("rater.requestMapping")));
        Map<Integer, String> response = rowMapping(loadResource(base.get("rater.responseMapping")));
        return new RaterSettings(props, request, response);
    }

    /** Settings built directly from values (used by tests). */
    public static RaterSettings of(Properties props, Map<Integer, String> requestMapping, Map<Integer, String> responseMapping) {
        return new RaterSettings(props, requestMapping, responseMapping);
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
            if (key.startsWith("row.")) {
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
