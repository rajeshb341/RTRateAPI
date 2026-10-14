package com.automation.rt;

import com.automation.api.client.ApiClient;
import com.automation.api.model.ApiRequest;
import com.automation.api.model.ApiResponse;
import com.automation.config.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Runs the RT rating API call from APIRequest.txt / RequestSpecifications.txt / APIToken.txt.
 *
 * If no endpoint URL is supplied the runner does a DRY RUN: it validates the inputs, builds the request
 * and writes a preview to the output directory without sending anything.
 *
 * Endpoint resolution order: --url arg, -DrtEndpoint, env RT_ENDPOINT.
 * Run: mvn compile exec:java -Dexec.mainClass=com.automation.rt.RtApiRunner [-Dexec.args="--url https://host/path"]
 */
@Slf4j
public class RtApiRunner {
    /** Folder holding APIRequest.txt / RequestSpecifications.txt / APIToken.txt: the project folder (the working directory). */
    public static final String DEFAULT_DIR = ".";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** -DrtEndpoint, then RT_ENDPOINT, then rt.endpoint from the properties file; null when none is set. */
    public static String resolveEndpoint() {
        return firstNonBlank(System.getProperty("rtEndpoint"), System.getenv("RT_ENDPOINT"),
            Config.getInstance().getProperty("rt.endpoint"));
    }

    /** Request, specification and token files, honouring -DrtRequestFile / -DrtSpecFile / -DrtTokenFile. */
    public static RtRequestLoader defaultLoader() {
        return new RtRequestLoader(
            Paths.get(System.getProperty("rtRequestFile", DEFAULT_DIR + "/APIRequest.txt")),
            Paths.get(System.getProperty("rtSpecFile", DEFAULT_DIR + "/RequestSpecifications.txt")),
            Paths.get(System.getProperty("rtTokenFile", DEFAULT_DIR + "/APIToken.txt")));
    }

    static int run(String[] args) {
        String url = resolveEndpoint();
        Path request = Paths.get(System.getProperty("rtRequestFile", DEFAULT_DIR + "/APIRequest.txt"));
        Path spec = Paths.get(System.getProperty("rtSpecFile", DEFAULT_DIR + "/RequestSpecifications.txt"));
        Path token = Paths.get(System.getProperty("rtTokenFile", DEFAULT_DIR + "/APIToken.txt"));
        Path outDir = Paths.get(System.getProperty("rtOutputDir", "target/rt-output"));
        boolean dryRun = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> url = i + 1 < args.length ? args[++i] : url;
                case "--request" -> request = Paths.get(args[++i]);
                case "--spec" -> spec = Paths.get(args[++i]);
                case "--token" -> token = Paths.get(args[++i]);
                case "--out" -> outDir = Paths.get(args[++i]);
                case "--dry-run" -> dryRun = true;
                default -> log.warn("Unknown argument ignored: {}", args[i]);
            }
        }

        try {
            Files.createDirectories(outDir);
            ApiRequest apiRequest = new RtRequestLoader(request, spec, token).load(url);
            logRequest(apiRequest);

            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            writePreview(outDir.resolve("request-preview-" + stamp + ".json"), apiRequest);

            if (dryRun || apiRequest.getUrl().isEmpty()) {
                log.warn("DRY RUN - {}. Request validated and preview saved in {}; nothing was sent.",
                    dryRun ? "--dry-run set" : "no endpoint URL configured (use --url, -DrtEndpoint or RT_ENDPOINT)", outDir);
                return 0;
            }

            return send(apiRequest, outDir, stamp);
        } catch (Exception e) {
            log.error("RT run failed: {}", e.getMessage(), e);
            return 2;
        }
    }

    private static int send(ApiRequest apiRequest, Path outDir, String stamp) throws Exception {
        Config config = Config.getInstance();
        try (ApiClient client = new ApiClient(config)) {
            ApiResponse response = client.execute(apiRequest);

            log.info("Response: {} {} ({} ms)", response.getStatusCode(), response.getStatusText(), response.getResponseTimeMs());
            Path responseFile = outDir.resolve("response-" + stamp + ".json");
            Files.writeString(responseFile, prettyOrRaw(response.getBody()), StandardCharsets.UTF_8);
            log.info("Response body saved to {}", responseFile);

            return response.isSuccess() ? 0 : 1;
        }
    }

    private static void logRequest(ApiRequest r) throws Exception {
        log.info("=== RT Request ===");
        log.info("{} {}", r.getMethod(), r.getUrl().isEmpty() ? "<endpoint not set>" : r.getUrl());
        for (Map.Entry<String, String> h : r.getHeaders().entrySet()) {
            log.info("Header {}: {}", h.getKey(), RtRequestLoader.mask(h.getKey(), h.getValue()));
        }
        JsonNode body = MAPPER.readTree((String) r.getBody());
        log.info("Body: EventName={}, LOB={}, TransactionType={}, {} chars",
            body.path("EventName").asText("?"), body.path("LOB").asText("?"),
            body.path("TransactionType").asText("?"), ((String) r.getBody()).length());
    }

    private static void writePreview(Path file, ApiRequest r) throws Exception {
        var preview = MAPPER.createObjectNode();
        preview.put("method", r.getMethod());
        preview.put("url", r.getUrl());
        var headers = preview.putObject("headers");
        r.getHeaders().forEach((k, v) -> headers.put(k, RtRequestLoader.mask(k, v)));
        preview.set("body", MAPPER.readTree((String) r.getBody()));
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(preview), StandardCharsets.UTF_8);
    }

    private static String prettyOrRaw(String body) {
        if (body == null) return "";
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(body));
        } catch (Exception e) {
            return body;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
