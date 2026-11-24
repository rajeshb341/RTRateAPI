package com.automation.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * URL, username and password of the RT application UI. Read from a local ".env" file in the project folder (git-ignored,
 * never zipped - copy ".env.example") or, when the file does not have a key, from the OS environment variable of the same
 * name: RT_URL, RT_USERNAME, RT_PASSWORD (RT_ prefix as in NonConfigUpdatedInspectionUpdate - Windows already defines
 * USERNAME). The password is never printed: {@link #toString()} masks it.
 */
public record UiCredentials(String url, String username, String password) {

    public static UiCredentials load() {
        return load(Path.of(".env"));
    }

    public static UiCredentials load(Path envFile) {
        Map<String, String> file = read(envFile);
        // -Dui.url=<url> (or ui.url in rater.properties) runs against another environment with the same login
        String url = System.getProperty("ui.url", "").trim();
        return new UiCredentials(url.isEmpty() ? value("RT_URL", file, envFile) : url, value("RT_USERNAME", file, envFile),
            value("RT_PASSWORD", file, envFile));
    }

    private static String value(String key, Map<String, String> file, Path envFile) {
        String v = file.get(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(key + " is not set: add it to " + envFile.toAbsolutePath().normalize()
                + " (see .env.example) or set the environment variable " + key);
        }
        return v.trim();
    }

    private static Map<String, String> read(Path envFile) {
        Map<String, String> map = new HashMap<>();
        if (!Files.exists(envFile)) return map;
        try {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String value = t.substring(eq + 1).trim();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                map.put(t.substring(0, eq).trim(), value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + envFile + ": " + e.getMessage(), e);
        }
        return map;
    }

    @Override
    public String toString() {
        return "UiCredentials[url=" + url + ", username=" + username + ", password=****]";
    }
}
