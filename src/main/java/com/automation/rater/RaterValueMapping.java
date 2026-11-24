package com.automation.rater;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Translates a Rater (test data) value into the value the API expects, per Rater row.
 * The test data keeps the Rater's dropdown wording (it is typed into the Rater as-is); the API takes different wording
 * for some attributes (e.g. "Full Policy Limits" -> "", distance band "5 - 10 Mi." -> 25000).
 *
 * File format, one entry per line (blank API value allowed, '#' starts a comment):
 * <pre>  target | Rater value | API value [| configurations] </pre>
 * <ul>
 *   <li>target: the Rater row ("12") or a row's extra request attribute ("40.status", see rater-request-mapping)</li>
 *   <li>Rater value "*": any value without an exact entry</li>
 *   <li>configurations: comma-separated configuration ids the entry is limited to (blank = all)</li>
 * </ul>
 * Lookup order: configuration exact, configuration "*", general exact, general "*". A value without an entry is sent unchanged.
 */
@Slf4j
public final class RaterValueMapping {
    private static final RaterValueMapping NONE = new RaterValueMapping(List.of());
    private static final String ANY = "*";

    private record Entry(String target, String raterValue, String apiValue, List<String> configurations) {}

    private final Map<String, List<Entry>> byTarget = new HashMap<>();

    private RaterValueMapping(List<Entry> entries) {
        entries.forEach(e -> byTarget.computeIfAbsent(e.target(), t -> new ArrayList<>()).add(e));
    }

    /** Loads the mapping from a file on disk, or from the classpath when no such file exists. A missing file means no translation. */
    public static RaterValueMapping load(String name) {
        if (name == null || name.isBlank()) return NONE;
        try (InputStream in = open(name)) {
            if (in == null) {
                log.warn("Value mapping file not found (file or classpath): {} - values are sent unchanged", name);
                return NONE;
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read value mapping " + name + ": " + e.getMessage(), e);
        }
    }

    static RaterValueMapping parse(BufferedReader reader) throws IOException {
        List<Entry> entries = new ArrayList<>();
        String line;
        int lineNo = 0;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            String text = line.strip();
            if (text.startsWith("﻿")) text = text.substring(1);
            if (text.isEmpty() || text.startsWith("#")) continue;
            String[] parts = text.split("\\|", -1);
            if ((parts.length != 3 && parts.length != 4) || !parts[0].strip().matches("\\d+(\\.[A-Za-z0-9_]+)?")) {
                log.warn("Ignoring malformed value mapping line {}: {}", lineNo, text);
                continue;
            }
            List<String> configurations = parts.length == 4
                ? Arrays.stream(parts[3].split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList() : List.of();
            entries.add(new Entry(parts[0].strip(), parts[1].strip(), parts[2].strip(), configurations));
        }
        return new RaterValueMapping(entries);
    }

    private static InputStream open(String name) throws IOException {
        Path file = Paths.get(name);
        if (Files.isRegularFile(file)) return Files.newInputStream(file);
        return RaterValueMapping.class.getClassLoader().getResourceAsStream(name);
    }

    /** The API value for the Rater value of this row; the value itself when there is no entry. */
    public String toApi(int row, String raterValue) {
        return toApi(null, String.valueOf(row), raterValue);
    }

    /** The API value for the Rater value of this target in this configuration; the value itself when there is no entry. */
    public String toApi(String configuration, String target, String raterValue) {
        return lookup(configuration, target, raterValue).orElse(raterValue);
    }

    /** The mapped API value, or empty when no entry applies (the caller then decides, e.g. range resolution). */
    public Optional<String> lookup(String configuration, String target, String raterValue) {
        List<Entry> entries = byTarget.get(target);
        if (entries == null || raterValue == null) return Optional.empty();
        String value = raterValue.strip();
        for (boolean specific : new boolean[]{true, false}) {
            for (boolean wildcard : new boolean[]{false, true}) {
                for (Entry e : entries) {
                    boolean applies = specific
                        ? configuration != null && e.configurations().stream().anyMatch(c -> c.equalsIgnoreCase(configuration))
                        : e.configurations().isEmpty();
                    boolean matches = wildcard ? ANY.equals(e.raterValue()) : e.raterValue().equals(value);
                    if (applies && matches) return Optional.of(e.apiValue());
                }
            }
        }
        return Optional.empty();
    }
}
