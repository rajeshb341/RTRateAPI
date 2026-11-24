package com.automation.rater;

import com.automation.api.client.ApiClient;
import com.automation.config.Config;
import com.automation.rater.RaterReportWriter.Line;
import com.automation.rater.RaterResponseMapper.Actual;
import com.automation.rater.RaterResponseMapper.Status;
import com.automation.rater.RaterValidationRunner.ApiCall;
import com.automation.rater.RaterValidationRunner.Called;
import com.automation.rt.RtApiRunner;
import com.automation.rt.RtRequestLoader;
import com.automation.ui.ApiEchoUiRatingFlow;
import com.automation.ui.UiCase;
import com.automation.ui.UiRatingFlow;
import com.automation.ui.UiResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UI vs API validation: every test case is sent to the API (Expected = the API response) and entered in the UI by the
 * configured {@link UiRatingFlow} (Actual = the values the UI shows). Both get the same inputs: the request that was sent.
 *
 * <p>Uses the test data, iteration selection, request building and API call of the Rater validation; the Rater workbook is
 * not used. Settings (rater.properties): ui.flow.class, ui.threads,
 * ui.report.dir. Output: &lt;ui.report.dir&gt;/Run_&lt;stamp&gt;/ with iterations/&lt;id&gt;/ (request.json, response.json,
 * ui-outputs.json), ui-attributes.txt (the input / output keys) and UiValidationReport_&lt;stamp&gt;.xlsx.
 */
@Slf4j
public final class UiApiValidationRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UiApiValidationRunner() {}

    public static void main(String[] args) {
        System.exit(run());
    }

    static int run() {
        try {
            RaterSettings settings = RaterSettings.load();
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path runDir = Path.of(settings.get("ui.report.dir", "target/ui-output")).resolve("Run_" + stamp);
            Files.createDirectories(runDir);
            try (RaterRunLog ignored = RaterRunLog.start(runDir.resolve("console-log.txt"))) {
                try {
                    log.info("UI vs API run folder: {}", runDir.toAbsolutePath());
                    return validate(settings, runDir, stamp);
                } catch (Exception e) {
                    log.error("UI vs API validation failed: {}", e.getMessage(), e);
                    return 2;
                }
            }
        } catch (Exception e) {
            log.error("UI vs API validation failed: {}", e.getMessage(), e);
            return 2;
        }
    }

    private static int validate(RaterSettings settings, Path runDir, String stamp) throws Exception {
        List<RaterTestCase> cases = RaterTestData.read(settings.path("rater.testdata.file"), settings.get("rater.testdata.sheet", "TestData"));
        String flowClass = settings.get("ui.flow.class", ApiEchoUiRatingFlow.class.getName());
        boolean stub = ApiEchoUiRatingFlow.class.getName().equals(flowClass);
        // One iteration = the API call and its UI flow: the cases are rater.iterations (blank = all), as for the API run
        String spec = settings.get("rater.iterations");
        if (!spec.isEmpty()) cases = RaterValidationRunner.selectIterations(cases, spec);
        if (cases.isEmpty()) {
            log.error("No test cases to run (check rater.iterations)");
            return 2;
        }
        log.info("{} test case(s): {}", cases.size(), spec.isEmpty() ? "all" : spec.length() > 200 ? spec.substring(0, 200) + "..." : spec);

        // Same preparation of the cases as the Rater validation (configuration, renewal inputs, counties, wind rules)
        List<RaterConfiguration> configurations = RaterConfiguration.all(settings);
        Map<String, RaterConfiguration.Resolution> resolved = new LinkedHashMap<>();
        for (RaterTestCase tc : cases) {
            resolved.put(tc.id(), configurations.isEmpty() ? new RaterConfiguration.Resolution(null, null)
                : RaterConfiguration.resolve(settings, configurations, tc.inputs()));
        }
        RaterGeocoder geocoder = Boolean.parseBoolean(settings.get("rater.geocode.enabled", "true"))
            ? new RaterGeocoder(settings).withCountyTable(new RaterWorkbook(settings, runDir.resolve("working")).countyTable()) : null;
        List<RaterTestCase> runnable = RaterValidationRunner.withoutTigerCqWind(settings, resolved,
            RaterValidationRunner.withTableCounties(geocoder, resolved, RaterValidationRunner.withValuesFromRows(settings,
                cases.stream().filter(c -> resolved.get(c.id()).problem() == null).toList())));

        UiSetup ui = UiSetup.of(settings, runDir);
        Map<String, Integer> rowOfPath = ui.rowOfPath();
        Map<String, String> outputPaths = ui.outputPaths();

        RtRequestLoader loader = RtApiRunner.defaultLoader();
        RaterRequestMapper requestMapper = new RaterRequestMapper(settings, RaterValidationRunner.defaultTemplate(loader, configurations));
        String url = RtApiRunner.resolveEndpoint();
        boolean noEndpoint = url == null || url.isBlank();
        Path iterationsDir = runDir.resolve("iterations");
        int retries = Math.max(0, Integer.parseInt(settings.get("rater.api.retries", "2").trim()));
        var tokenWait = new RaterValidationRunner.TokenWait(loader.tokenFile(),
            Math.max(0, Long.parseLong(settings.get("rater.api.tokenWaitMinutes", "15").trim())));
        ApiCall apiCall = new ApiCall(settings, loader, requestMapper, geocoder, List.of(), url, iterationsDir, retries, tokenWait);

        if (stub) log.warn("ui.flow.class is the stub {}: NO UI is opened, the 'UI' values are the API's own", flowClass);
        int threads = Math.max(1, Math.min(runnable.size(), Integer.parseInt(settings.get("ui.threads", "1").trim())));
        log.info("API + UI with {} worker(s) (ui.threads); UI flow: {}", threads, flowClass);

        Map<String, List<Line>> linesOf = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Queue<RaterTestCase> queue = new java.util.concurrent.ConcurrentLinkedQueue<>(runnable);
        RaterValidationRunner.Progress progress = new RaterValidationRunner.Progress("UI+API", runnable.size());
        java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int w = 1; w <= threads; w++) {
            Thread worker = new Thread(() -> {
                try (ApiClient client = noEndpoint ? null : new ApiClient(Config.getInstance());
                     UiRatingFlow flow = newFlow(flowClass)) {
                    RaterTestCase tc;
                    while (failure.get() == null && (tc = queue.poll()) != null) {
                        RaterConfiguration configuration = resolved.get(tc.id()).configuration();
                        String configId = configuration == null ? "" : configuration.id();
                        linesOf.put(tc.id(), oneCase(settings, tc, configId, apiCall.call(tc, configuration, null, client),
                            flow, outputPaths, rowOfPath, stub, iterationsDir.resolve(tc.id())));
                        progress.step();
                    }
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                }
            }, "ui-" + w);
            worker.start();
            workers.add(worker);
        }
        for (Thread worker : workers) worker.join();
        if (failure.get() != null) throw failure.get();

        List<Line> lines = new ArrayList<>();
        for (RaterTestCase tc : cases) {
            RaterConfiguration.Resolution resolution = resolved.get(tc.id());
            String configId = resolution.configuration() == null ? "" : resolution.configuration().id();
            if (resolution.problem() != null) {
                lines.add(new Line(tc.id(), "(test case)", "", "", RaterReportWriter.SKIPPED, resolution.problem(), configId));
            } else {
                lines.addAll(linesOf.getOrDefault(tc.id(), List.of()));
            }
        }
        Path report = runDir.resolve("UiValidationReport_" + stamp + ".xlsx");
        RaterReportWriter.write(report, lines, "API Attribute (response path)", "Expected (API)", "Actual (UI)");
        Map<String, Long> counts = lines.stream().collect(Collectors.groupingBy(Line::result, Collectors.counting()));
        long fail = counts.getOrDefault(RaterReportWriter.FAIL, 0L);
        log.info("=== UI vs API validation{}: {} attribute checks, {} Pass, {} Fail, {} N/A; {} test case(s) skipped ===",
            stub ? " (STUB UI)" : "", lines.size(), counts.getOrDefault(RaterReportWriter.PASS, 0L), fail,
            counts.getOrDefault(RaterReportWriter.NOT_APPLICABLE, 0L), counts.getOrDefault(RaterReportWriter.SKIPPED, 0L));
        log.info("Results are in {}", runDir.toAbsolutePath());
        return fail == 0 ? 0 : 1;
    }

    /**
     * What the UI comparison needs, shared with the Rater validation (ui.enabled): the UI flow class and the output
     * attributes (key -> API response path, path -> output row for the tolerance). Writes ui-attributes.txt to the run.
     */
    record UiSetup(String flowClass, boolean stub, Map<String, String> outputPaths, Map<String, Integer> rowOfPath) {
        static UiSetup of(RaterSettings settings, Path runDir) throws java.io.IOException {
            String flowClass = settings.get("ui.flow.class", ApiEchoUiRatingFlow.class.getName());
            Map<String, Integer> rowOfPath = new LinkedHashMap<>();
            List<Integer> rows = new ArrayList<>(settings.outputRows());
            RaterSettings.parseRows(settings.get("rater.output.renewalOnly.rows")).stream().filter(r -> !rows.contains(r)).forEach(rows::add);
            for (int row : rows) {
                String path = settings.responsePath(row);
                if (!path.isEmpty()) rowOfPath.putIfAbsent(path, row);
            }
            Map<String, String> outputPaths = new LinkedHashMap<>();
            keys(rowOfPath.keySet()).forEach((path, key) -> outputPaths.put(key, path));
            Files.createDirectories(runDir);
            writeAttributeList(runDir.resolve("ui-attributes.txt"), outputPaths);
            return new UiSetup(flowClass, ApiEchoUiRatingFlow.class.getName().equals(flowClass), outputPaths, rowOfPath);
        }

        UiRatingFlow newFlow() throws Exception {
            return UiApiValidationRunner.newFlow(flowClass);
        }

        List<Line> compare(RaterSettings settings, RaterTestCase tc, String configId, Called api, UiRatingFlow flow, Path caseDir) {
            return oneCase(settings, tc, configId, api, flow, outputPaths, rowOfPath, stub, caseDir);
        }
    }

    /** API result + UI result of one case -> report lines. */
    static List<Line> oneCase(RaterSettings settings, RaterTestCase tc, String configId, Called api, UiRatingFlow flow,
                              Map<String, String> outputPaths, Map<String, Integer> rowOfPath, boolean stub, Path caseDir) {
        List<Line> lines = new ArrayList<>();
        if (api.apiProblem() != null) {
            lines.add(new Line(tc.id(), "(API call)", "", "", RaterReportWriter.FAIL, api.apiProblem(), configId));
            // the UI is filled from the request built for the API, not its response: the UI flow still runs when the
            // request exists (e.g. the API answered HTTP 500), only the UI vs API output comparison is not possible
            if (!Files.exists(caseDir.resolve("request.json"))) return lines;
        }
        UiResult ui;
        try {
            String requestJson = MAPPER.readTree(caseDir.resolve("request.json").toFile()).path("body").toString();
            // the UI inputs: the request values by attribute name + the test data row values as "row.<N>"
            Map<String, String> uiInputs = new LinkedHashMap<>(inputs(requestJson));
            tc.inputs().forEach((row, value) -> uiInputs.put("row." + row, value == null ? "" : value));
            UiCase uiCase = new UiCase(tc.id(), configId, tc.inputs().getOrDefault(14, ""), uiInputs,
                outputPaths, requestJson, caseDir);
            ui = flow.rate(uiCase);
            if (ui == null) ui = UiResult.failed("UI flow returned no result", null);
        } catch (Exception e) {
            ui = UiResult.failed("UI flow failed: " + e, null);
        }
        saveUiOutputs(caseDir.resolve("ui-outputs.json"), ui);
        String shot = ui.screenshot() == null ? "" : " (screenshot: " + ui.screenshot() + ")";
        if (ui.error() != null) {
            log.warn("{}: UI flow failed - {}", tc.id(), ui.error());
            lines.add(new Line(tc.id(), "(UI flow)", "", "", RaterReportWriter.FAIL, ui.error() + shot, configId));
            return lines;
        }
        if (api.apiProblem() != null) return lines;     // no API outputs to compare the UI with
        String prefix = stub ? "STUB - " : "";
        for (Map.Entry<String, String> e : outputPaths.entrySet()) {
            String key = e.getKey();
            String label = key + " (" + e.getValue() + ")";
            int row = rowOfPath.get(e.getValue());
            Actual expected = api.responseMapper().read(row);
            String uiValue = ui.outputs().get(key);
            boolean inApi = expected.status() == Status.FOUND && !expected.value().isBlank();
            if (uiValue == null) {
                lines.add(new Line(tc.id(), label, expected.value(), "", RaterReportWriter.NOT_APPLICABLE,
                    prefix + "Not shown in the UI", configId));
            } else if (!inApi && uiValue.isBlank()) {
                lines.add(new Line(tc.id(), label, "", "", RaterReportWriter.NOT_APPLICABLE, prefix + "Not in the API response", configId));
            } else if (!inApi) {
                lines.add(new Line(tc.id(), label, "", uiValue, RaterReportWriter.FAIL,
                    prefix + "Shown in the UI but not in the API response" + shot, configId));
            } else {
                ValueComparator.Outcome outcome = ValueComparator.compare(expected.value(), uiValue, settings.tolerance(row));
                lines.add(new Line(tc.id(), label, expected.value(), uiValue,
                    outcome.pass() ? RaterReportWriter.PASS : RaterReportWriter.FAIL,
                    prefix + outcome.remark() + (outcome.pass() ? "" : shot), configId));
            }
        }
        return lines;
    }

    /** The leaf values of the request body, keyed by attribute name (see {@link #keys}); the token is left out. */
    static Map<String, String> inputs(String requestJson) throws java.io.IOException {
        Map<String, String> byPath = new LinkedHashMap<>();
        flatten(MAPPER.readTree(requestJson), "$", byPath);
        byPath.keySet().removeIf(p -> p.endsWith(".Token"));
        Map<String, String> keyOfPath = keys(byPath.keySet());
        Map<String, String> inputs = new LinkedHashMap<>();
        byPath.forEach((path, value) -> inputs.put(keyOfPath.get(path), value));
        return inputs;
    }

    private static void flatten(JsonNode node, String path, Map<String, String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> flatten(e.getValue(), path + "." + e.getKey(), out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) flatten(node.get(i), path + "[" + i + "]", out);
        } else {
            out.put(path, node.isNull() ? "" : node.asText());
        }
    }

    /**
     * Key of each JsonPath: the attribute name ("BasePremium"); a name found at several paths gets its parent
     * ("PLNonWind.Premium"), and still repeated names the whole path without "$." and array indexes.
     */
    static Map<String, String> keys(Collection<String> paths) {
        Map<String, List<String>> segments = new LinkedHashMap<>();
        for (String p : paths) {
            List<String> parts = new ArrayList<>();
            for (String s : p.replaceFirst("^\\$\\.?", "").split("\\.")) {
                String name = s.replaceAll("\\[\\d+]", "");
                if (!name.isEmpty()) parts.add(name);
            }
            segments.put(p, parts);
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (int depth = 1; depth <= 2; depth++) {
            Map<String, Long> count = new LinkedHashMap<>();
            for (String p : paths) if (!keys.containsKey(p)) count.merge(tail(segments.get(p), depth), 1L, Long::sum);
            for (String p : paths) {
                if (keys.containsKey(p)) continue;
                String key = tail(segments.get(p), depth);
                if (count.get(key) == 1 && keys.values().stream().noneMatch(key::equals)) keys.put(p, key);
            }
        }
        for (String p : paths) keys.putIfAbsent(p, String.join(".", segments.get(p)));
        return keys;
    }

    private static String tail(List<String> parts, int n) {
        return String.join(".", parts.subList(Math.max(0, parts.size() - n), parts.size()));
    }

    static UiRatingFlow newFlow(String className) throws Exception {
        Class<?> type = Class.forName(className);
        if (!UiRatingFlow.class.isAssignableFrom(type)) throw new IllegalArgumentException(className + " does not implement UiRatingFlow");
        return (UiRatingFlow) type.getDeclaredConstructor().newInstance();
    }

    private static void writeAttributeList(Path file, Map<String, String> outputPaths) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Output attributes the UI flow returns (UiResult.outputs keys) -> where the API response has them.\n");
        sb.append("# Inputs (UiCase.inputs) use the same key rule on the request body: attribute name, \"Parent.Name\" when the\n");
        sb.append("# name is not unique; each iteration's request.json shows the values.\n");
        outputPaths.forEach((key, path) -> sb.append(key).append(" = ").append(path).append('\n'));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void saveUiOutputs(Path file, UiResult ui) {
        try {
            var node = MAPPER.createObjectNode();
            node.put("error", ui.error());
            node.put("screenshot", ui.screenshot() == null ? null : ui.screenshot().toString());
            var outputs = node.putObject("outputs");
            new java.util.TreeMap<>(ui.outputs()).forEach(outputs::put);
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Cannot save {}: {}", file, e.getMessage());
        }
    }
}
