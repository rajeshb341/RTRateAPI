package com.automation.ui;

import com.automation.api.client.ApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Records the UI's own API calls (what the browser's Network tab shows as XHR / fetch) while a test case runs, so the
 * UI's request and response can be compared with the API run. Saved per case as ui-network/NNN_&lt;name&gt;.json
 * (method, url, status, request body, response body; tokens masked, no headers).
 *
 * <p>{@code captureFilter} (ui.capture.urlPattern) limits what is recorded (blank = every XHR / fetch call).
 */
@Slf4j
public final class UiNetworkCapture {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One recorded call; the bodies are read when the case is saved. */
    public record Call(int number, String method, String url, int status, String requestBody, String responseBody) {
        public String name() {
            String path = url.replaceFirst("[?#].*$", "");
            String last = path.substring(path.lastIndexOf('/') + 1);
            return last.isEmpty() ? "call" : last.replaceAll("[^A-Za-z0-9_.-]", "_");
        }
    }

    private final Pattern captureFilter;
    private final List<Response> responses = new ArrayList<>();
    private boolean recording;

    public UiNetworkCapture(Page page, String captureUrlPattern) {
        this.captureFilter = captureUrlPattern == null || captureUrlPattern.isBlank() ? null : Pattern.compile(captureUrlPattern);
        page.onResponse(this::record);
    }

    private synchronized void record(Response response) {
        if (!recording) return;
        String type = response.request().resourceType();
        if (!"xhr".equals(type) && !"fetch".equals(type)) return;
        if (captureFilter != null && !captureFilter.matcher(response.url()).find()) return;
        responses.add(response);
    }

    /** Starts recording a new test case (forgets the previous case's calls). */
    public synchronized void start() {
        responses.clear();
        recording = true;
    }

    /** Stops recording and returns the calls of the case, with their bodies (tokens masked). */
    public synchronized List<Call> stop() {
        recording = false;
        List<Call> calls = new ArrayList<>();
        int n = 0;
        for (Response r : responses) {
            calls.add(new Call(++n, r.request().method(), r.url(), r.status(), mask(r.request().postData()), mask(body(r))));
        }
        responses.clear();
        return calls;
    }

    /** Saves the calls to {@code dir} (NNN_&lt;name&gt;.json) and returns their number. */
    public static int save(List<Call> calls, Path dir) {
        try {
            Files.createDirectories(dir);
            for (Call c : calls) {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("method", c.method());
                node.put("url", c.url());
                node.put("status", c.status());
                node.set("request", json(c.requestBody()));
                node.set("response", json(c.responseBody()));
                Files.writeString(dir.resolve(String.format("%03d_%s.json", c.number(), c.name())),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Cannot save the UI network calls to {}: {}", dir, e.getMessage());
        }
        return calls.size();
    }

    /** The last call whose URL matches {@code urlPattern} (the UI's rating API), if any. */
    public static Optional<Call> last(List<Call> calls, String urlPattern) {
        if (urlPattern == null || urlPattern.isBlank()) return Optional.empty();
        Pattern p = Pattern.compile(urlPattern);
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (p.matcher(calls.get(i).url()).find()) return Optional.of(calls.get(i));
        }
        return Optional.empty();
    }

    /** The calls whose request names this event ("EventName": "&lt;event&gt;" in the request body), in order. */
    public static List<Call> withEvent(List<Call> calls, String event) {
        if (event == null || event.isBlank()) return List.of();
        Pattern p = Pattern.compile("\"EventName\"\\s*:\\s*\"" + Pattern.quote(event) + "\"");
        return calls.stream().filter(c -> p.matcher(c.requestBody()).find()).toList();
    }

    private static String body(Response r) {
        try {
            return r.text();
        } catch (Exception unavailable) {       // e.g. redirects, or a body the browser already discarded
            return "";
        }
    }

    /** JSON string fields whose name contains token / password / secret / authorization / apikey (any case). */
    private static final Pattern SECRET_FIELD = Pattern.compile(
        "(\"[A-Za-z0-9_]*(?i:token|password|passwd|secret|authorization|apikey|api_key)[A-Za-z0-9_]*\"\\s*:\\s*\")[^\"]*(\")");

    public static String mask(String text) {
        return text == null ? "" : SECRET_FIELD.matcher(ApiClient.maskTokens(text)).replaceAll("$1****$2");
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String text) {
        try {
            return text.isBlank() ? MAPPER.nullNode() : MAPPER.readTree(text);
        } catch (Exception notJson) {
            return MAPPER.getNodeFactory().textNode(text);
        }
    }
}
