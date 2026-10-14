package com.automation.rater;

import com.automation.api.client.ApiClient;
import com.automation.api.model.ApiRequest;
import com.automation.api.model.ApiResponse;
import com.automation.config.Config;
import com.automation.rater.RaterReportWriter.Line;
import com.automation.rater.RaterResponseMapper.Actual;
import com.automation.rater.RaterResponseMapper.Status;
import com.automation.rater.ValueComparator.Outcome;
import com.automation.rt.RtApiRunner;
import com.automation.rt.RtRequestLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates the RT rating API against the Rater workbook.
 *
 * <pre>
 *   --generate [N]   create the TestData workbook (N cases) from the Rater dropdowns, then stop
 *   --run            (default) for each test case: calculate Expected in the Rater, call the API, compare, write report
 *   --cases TC001,TC002   run only these test cases
 *   --url &lt;endpoint&gt;    override the endpoint (default: rt.endpoint in the properties file)
 * </pre>
 * Run: mvn compile exec:java -Dexec.mainClass=com.automation.rater.RaterValidationRunner -Dexec.args="--generate 10"
 */
@Slf4j
public class RaterValidationRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN_FIELD ="(\"Token\"\\s*:\\s*\")[^\"]*(\")";

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        boolean generate = false;
        Integer generateCount = null;
        Set<String> onlyCases = Set.of();
        String url = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--generate" -> {
                    generate = true;
                    if (i + 1 < args.length && args[i + 1].matches("\\d+")) generateCount = Integer.parseInt(args[++i]);
                }
                case "--run" -> generate = false;
                case "--cases" -> onlyCases = Arrays.stream(args[++i].split(",")).map(String::trim).collect(Collectors.toSet());
                case "--url" -> url = args[++i];
                default -> log.warn("Unknown argument ignored: {}", args[i]);
            }
        }

        try {
            RaterSettings settings = RaterSettings.load();
            Path reportDir = settings.path("rater.report.dir");

            if (generate) {
                return generateTestData(settings, new RaterWorkbook(settings, reportDir), generateCount);
            }

            // Everything from one execution goes into its own date/time folder
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path runDir = reportDir.resolve("Run_" + stamp);
            Files.createDirectories(runDir);
            try (RaterRunLog ignored = RaterRunLog.start(runDir.resolve("console-log.txt"))) {
                try {
                    log.info("Run folder: {}", runDir.toAbsolutePath());
                    return validate(settings, new RaterWorkbook(settings, runDir.resolve("working")), runDir, stamp, onlyCases, url);
                } catch (Exception e) {
                    log.error("Rater validation failed: {}", e.getMessage(), e);   // still written to console-log.txt
                    return 2;
                }
            }
        } catch (Exception e) {
            log.error("Rater validation failed: {}", e.getMessage(), e);
            return 2;
        }
    }

    /**
     * Keeps the test cases named in the spec: comma separated numbers, ranges or TestCaseIds, e.g. "1,2,3", "1-3,7", "TC001,TC004".
     * A number matches the digits of the TestCaseId (1 = TC001). Blank = all cases (the caller does not call this).
     */
    static List<RaterTestCase> selectIterations(List<RaterTestCase> cases, String spec) {
        Set<Integer> numbers = new java.util.HashSet<>();
        Set<String> ids = new java.util.HashSet<>();
        for (String raw : spec.split("[,;\\s]+")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            if (token.matches("\\d+")) {
                numbers.add(Integer.parseInt(token));
            } else if (token.matches("\\d+\\s*-\\s*\\d+")) {
                String[] range = token.split("\\s*-\\s*");
                for (int n = Integer.parseInt(range[0]); n <= Integer.parseInt(range[1]); n++) numbers.add(n);
            } else {
                ids.add(token.toUpperCase());
            }
        }
        return cases.stream().filter(c -> {
            if (ids.contains(c.id().toUpperCase())) return true;
            String digits = c.id().replaceAll("\\D", "");
            return !digits.isEmpty() && numbers.contains(Integer.parseInt(digits));
        }).toList();
    }

    private static int generateTestData(RaterSettings settings, RaterWorkbook rater, Integer countArg) throws Exception {
        List<RaterAttribute> attributes = rater.inspect(true);
        int count = countArg != null ? countArg : Integer.parseInt(settings.get("rater.testdata.count", "10"));
        String seedText = settings.get("rater.testdata.seed");
        Long seed = seedText.isEmpty() ? null : Long.parseLong(seedText);

        List<RaterTestCase> cases = RaterTestData.generate(settings, attributes, count, seed);
        RaterTestData.write(settings.path("rater.testdata.file"), settings.get("rater.testdata.sheet", "TestData"), attributes, cases);
        return 0;
    }

    private static int validate(RaterSettings settings, RaterWorkbook rater, Path runDir, String stamp, Set<String> onlyCases,
                                String urlArg) throws Exception {
        List<RaterTestCase> cases = RaterTestData.read(settings.path("rater.testdata.file"), settings.get("rater.testdata.sheet", "TestData"));
        if (!onlyCases.isEmpty()) {
            cases = cases.stream().filter(c -> onlyCases.contains(c.id())).toList();     // --cases / -DraterCases wins
        } else {
            String iterations = settings.get("rater.iterations", "").trim();
            if (!iterations.isEmpty()) {
                cases = selectIterations(cases, iterations);
                log.info("rater.iterations={} -> running {} test case(s): {}", iterations, cases.size(),
                    cases.stream().map(RaterTestCase::id).toList());
            }
        }
        if (cases.isEmpty()) {
            log.error("No test cases to run (check rater.iterations / --cases against the TestCaseId column)");
            return 2;
        }

        // Labels for the report (Parent/Entity/Attribute + cell row)
        List<RaterAttribute> attributes = rater.inspect(false);
        List<Integer> outputRows = settings.outputRows();
        Map<Integer, RaterAttribute> byRow = attributes.stream().collect(Collectors.toMap(RaterAttribute::getRow, a -> a, (a, b) -> a));

        // Expected values: write inputs into the Rater, recalculate, read the outputs
        Map<String, RaterWorkbook.RaterOutputs> expected = rater.calculateExpected(cases, outputRows);

        RtRequestLoader loader = RtApiRunner.defaultLoader();
        String template = loader.readRequestTemplate();
        RaterRequestMapper requestMapper = new RaterRequestMapper(settings, template);
        List<Integer> unmapped = requestMapper.unmappedRows(settings.inputRows().stream().filter(byRow::containsKey).toList());
        if (!unmapped.isEmpty()) {
            log.warn("Input rows written to the Rater but NOT sent in the request (no mapping): {}", unmapped);
        }

        String url = urlArg != null ? urlArg : RtApiRunner.resolveEndpoint();
        Path iterationsDir = runDir.resolve("iterations");

        List<Line> lines = new ArrayList<>();
        try (ApiClient client = url == null || url.isBlank() ? null : new ApiClient(Config.getInstance())) {
            for (RaterTestCase tc : cases) {
                RaterWorkbook.RaterOutputs exp = expected.get(tc.id());
                String apiProblem = null;
                String responseBody = "";
                Path caseDir = iterationsDir.resolve(tc.id());      // request.json + response.json for this iteration
                Files.createDirectories(caseDir);

                if (exp == null || exp.error() != null) {
                    apiProblem = "Rater could not be calculated: " + (exp == null ? "no result" : exp.error());
                } else if (client == null) {
                    apiProblem = "API not called: no endpoint configured (rt.endpoint)";
                } else {
                    try {
                        ApiRequest request = loader.load(url, requestMapper.build(tc.inputs()));
                        saveRequest(caseDir.resolve("request.json"), request);
                        ApiResponse response = client.execute(request);
                        responseBody = response.getBody() == null ? "" : response.getBody();
                        saveResponse(caseDir, response);
                        if (!response.isSuccess()) {
                            apiProblem = "API failure: HTTP " + response.getStatusCode() + " " + response.getStatusText();
                        } else {
                            log.info("{}: HTTP {} in {} ms", tc.id(), response.getStatusCode(), response.getResponseTimeMs());
                        }
                    } catch (Exception e) {
                        apiProblem = "API call failed: " + e.getMessage();
                    }
                }
                if (apiProblem != null) log.warn("{}: {}", tc.id(), apiProblem);

                RaterResponseMapper responseMapper = apiProblem == null ? new RaterResponseMapper(settings, responseBody) : null;
                for (int row : outputRows) {
                    RaterAttribute attr = byRow.get(row);
                    if (attr == null) continue;     // separator row with no attribute name
                    String expectedValue = exp != null && exp.values() != null ? exp.values().getOrDefault(row, "") : "";
                    lines.add(compareRow(settings, tc, attr, expectedValue, responseMapper, apiProblem));
                }
            }
        }

        Path report = runDir.resolve("RaterValidationReport_" + stamp + ".xlsx");
        RaterReportWriter.write(report, lines);

        long pass = lines.stream().filter(l -> RaterReportWriter.PASS.equals(l.result())).count();
        log.info("=== Rater validation: {} attribute checks, {} Pass, {} Fail ===", lines.size(), pass, lines.size() - pass);
        log.info("Results are in {}", runDir.toAbsolutePath());
        return pass == lines.size() ? 0 : 1;
    }

    /** Saves the request exactly as sent, with the token masked (header and body). */
    private static void saveRequest(Path file, ApiRequest request) throws java.io.IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("method", request.getMethod());
        node.put("url", request.getUrl());
        ObjectNode headers = node.putObject("headers");
        request.getHeaders().forEach((k, v) -> headers.put(k, RtRequestLoader.mask(k, v)));
        JsonNode body = MAPPER.readTree((String) request.getBody());
        if (body.path("ServiceRequestDetail").isObject() && body.path("ServiceRequestDetail").has("Token")) {
            ((ObjectNode) body.path("ServiceRequestDetail")).put("Token", "****");
        }
        node.set("body", body);
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node), StandardCharsets.UTF_8);
    }

    /** Saves the response body (token masked) as response.json, or response.txt when it is not JSON, plus the HTTP status. */
    private static void saveResponse(Path caseDir, ApiResponse response) throws java.io.IOException {
        String body = response.getBody() == null ? "" : response.getBody().replaceAll(TOKEN_FIELD, "$1****$2");
        String text;
        String name;
        try {
            text = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(body));
            name = "response.json";
        } catch (Exception notJson) {
            text = body;
            name = "response.txt";
        }
        Files.writeString(caseDir.resolve(name), text, StandardCharsets.UTF_8);
        Files.writeString(caseDir.resolve("response-status.txt"),
            "HTTP " + response.getStatusCode() + " " + response.getStatusText() + " (" + response.getResponseTimeMs() + " ms)"
                + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static Line compareRow(RaterSettings settings, RaterTestCase tc, RaterAttribute attr, String expected,
                                   RaterResponseMapper responseMapper, String apiProblem) {
        String label = attr.label();
        if (apiProblem != null) {
            return new Line(tc.id(), label, expected, "", RaterReportWriter.FAIL, apiProblem);
        }
        Actual actual = responseMapper.read(attr.getRow());
        if (actual.status() != Status.FOUND) {
            return new Line(tc.id(), label, expected, "", RaterReportWriter.FAIL, actual.detail());
        }
        Outcome outcome = ValueComparator.compare(expected, actual.value(), settings.tolerance(attr.getRow()));
        return new Line(tc.id(), label, expected, actual.value(),
            outcome.pass() ? RaterReportWriter.PASS : RaterReportWriter.FAIL, outcome.remark());
    }
}
