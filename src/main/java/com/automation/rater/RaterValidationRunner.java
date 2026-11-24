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
    /** Replaces every Token value in the text with **** (plain JSON and the escaped copy inside RequestJSON strings). */
    static String maskToken(String text) {
        return ApiClient.maskTokens(text);
    }

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
        checkEntityTypes(settings, byRow);

        // Configuration of every test case (Subline + Occupancy Type); unsupported / invalid cases are reported, not run
        List<RaterConfiguration> configurations = RaterConfiguration.all(settings);
        Map<String, RaterConfiguration.Resolution> resolved = new java.util.LinkedHashMap<>();
        for (RaterTestCase tc : cases) {
            resolved.put(tc.id(), configurations.isEmpty()
                ? new RaterConfiguration.Resolution(null, null) : RaterConfiguration.resolve(settings, configurations, tc.inputs()));
            String blank = blankDropdownInputs(tc, byRow, RaterSettings.parseRows(settings.get("rater.input.optionalRows")));
            if (blank != null && resolved.get(tc.id()).problem() == null) {
                resolved.put(tc.id(), new RaterConfiguration.Resolution(resolved.get(tc.id()).configuration(), blank));
            }
        }
        // County: a test data county that is not in the Rater's county table (e.g. a Connecticut planning region) is replaced,
        // for the Rater and the request alike, by the table county of a real address in the case's ZIP
        RaterGeocoder geocoder = Boolean.parseBoolean(settings.get("rater.geocode.enabled", "true"))
            ? new RaterGeocoder(settings).withCountyTable(rater.countyTable()) : null;
        List<RaterTestCase> runnable = withoutTigerCqWind(settings, resolved, withTableCounties(geocoder, resolved,
            withValuesFromRows(settings, cases.stream().filter(c -> resolved.get(c.id()).problem() == null).toList())));
        Map<String, Long> perConfiguration = runnable.stream().collect(Collectors.groupingBy(
            c -> configurationId(resolved.get(c.id())), java.util.TreeMap::new, Collectors.counting()));
        log.info("Test cases per configuration: {}", perConfiguration);

        // Expected values: write inputs into the Rater, recalculate, read the outputs (+ the derived rows the request needs)
        List<Integer> derivedRows = settings.derivedRequestRows();
        // Renewal: the API answers a Renewal case with the renewal calculation (Rater rows 232-348) in the same attributes
        // (rater.output.renewal.<row>=<renewal row>) plus renewal-only attributes (rater.output.renewalOnly.rows)
        Map<Integer, Integer> renewalRows = renewalRows(settings, outputRows);
        List<Integer> renewalOnly = RaterSettings.parseRows(settings.get("rater.output.renewalOnly.rows"));
        List<Integer> compareRows = new ArrayList<>(outputRows);
        renewalOnly.stream().filter(r -> !compareRows.contains(r)).forEach(compareRows::add);
        List<Integer> readRows = new ArrayList<>(compareRows);
        renewalRows.values().stream().filter(r -> !readRows.contains(r)).forEach(readRows::add);
        derivedRows.stream().filter(r -> !readRows.contains(r)).forEach(readRows::add);
        // The Rater runs in the background and hands over each case as soon as it is calculated, so the API calls run at
        // the same time as the Rater instead of after it
        Map<String, java.util.concurrent.CompletableFuture<RaterWorkbook.RaterOutputs>> ready = new java.util.concurrent.ConcurrentHashMap<>();
        runnable.forEach(tc -> ready.put(tc.id(), new java.util.concurrent.CompletableFuture<>()));
        Progress raterProgress = new Progress("Rater", runnable.size());
        java.util.concurrent.CompletableFuture<Map<String, RaterWorkbook.RaterOutputs>> raterDone = runnable.isEmpty()
            ? java.util.concurrent.CompletableFuture.completedFuture(Map.of())
            : java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return rater.calculateExpected(runnable, readRows, "", (id, outputs) -> {
                        java.util.concurrent.CompletableFuture<RaterWorkbook.RaterOutputs> f = ready.get(id);
                        if (f != null && f.complete(outputs)) raterProgress.step();
                    });
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, daemonThread("rater"));
        // Cases the stream did not deliver get the result file's value (or the Rater's failure) once the Rater is done
        raterDone.whenComplete((all, error) -> ready.forEach((id, f) -> {
            if (error != null) f.completeExceptionally(error);
            else f.complete(all.get(id));
        }));

        RtRequestLoader loader = RtApiRunner.defaultLoader();
        String template = defaultTemplate(loader, configurations);
        RaterRequestMapper requestMapper = new RaterRequestMapper(settings, template);
        List<Integer> unmapped = configurations.isEmpty()
            ? requestMapper.unmappedRows(settings.inputRows().stream().filter(byRow::containsKey).toList())
            : settings.inputRows().stream().filter(byRow::containsKey)
                .filter(r -> configurations.stream().allMatch(c -> settings.requestTargets(c.id(), r).isEmpty())).toList();
        if (!unmapped.isEmpty()) {
            log.warn("Input rows written to the Rater but NOT sent in any configuration's request (no mapping): {}", unmapped);
        }

        int retries = Math.max(0, Integer.parseInt(settings.get("rater.api.retries", "2").trim()));
        String url = urlArg != null ? urlArg : RtApiRunner.resolveEndpoint();
        log.info("API endpoint: {}", url == null || url.isBlank() ? "<not configured>" : url);
        Path iterationsDir = runDir.resolve("iterations");

        boolean noEndpoint = url == null || url.isBlank();
        // Each API worker has its own client (a Playwright client must stay on the thread that created it) and takes the
        // next case from the queue; the cases are queued in Rater order, so the workers follow the Rater
        int threads = Math.max(1, Math.min(runnable.size(), Integer.parseInt(settings.get("rater.api.threads", "4").trim())));
        // UI (ui.enabled): every iteration is its API call followed at once by its UI flow, one iteration after the other
        boolean uiEnabled = Boolean.parseBoolean(settings.get("ui.enabled", "false"));
        UiApiValidationRunner.UiSetup uiSetup = uiEnabled ? UiApiValidationRunner.UiSetup.of(settings, runDir) : null;
        Map<String, List<Line>> uiLines = new java.util.concurrent.ConcurrentHashMap<>();
        if (uiEnabled) {
            threads = 1;
            log.info("ui.enabled: each iteration = API call, then its UI flow ({}), one iteration at a time", uiSetup.flowClass());
            if (uiSetup.stub()) log.warn("ui.flow.class is the stub: NO UI is opened, the 'UI' values are the API's own");
        }
        TokenWait tokenWait = new TokenWait(loader.tokenFile(),
            Math.max(0, Long.parseLong(settings.get("rater.api.tokenWaitMinutes", "15").trim())));
        ApiCall apiCall = new ApiCall(settings, loader, requestMapper, geocoder, derivedRows, url, iterationsDir, retries, tokenWait);
        Map<String, Called> called = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Queue<RaterTestCase> queue = new java.util.concurrent.ConcurrentLinkedQueue<>(runnable);
        Progress progress = new Progress("API", runnable.size());
        java.util.concurrent.atomic.AtomicReference<Exception> workerFailure = new java.util.concurrent.atomic.AtomicReference<>();
        log.info("Calling the API with {} parallel worker(s) (rater.api.threads)", threads);
        List<Thread> workers = new ArrayList<>();
        for (int w = 1; w <= threads; w++) {
            Thread worker = new Thread(() -> {
                com.automation.ui.UiRatingFlow uiFlow = null;
                String uiProblem = null;
                try (ApiClient client = noEndpoint ? null : new ApiClient(Config.getInstance())) {
                    RaterTestCase tc;
                    while (workerFailure.get() == null && (tc = queue.poll()) != null) {
                        RaterWorkbook.RaterOutputs exp;
                        try {
                            exp = ready.get(tc.id()).get();
                        } catch (java.util.concurrent.ExecutionException raterFailed) {
                            return;     // the Rater itself failed: reported after the workers stop
                        }
                        RaterConfiguration configuration = resolved.get(tc.id()).configuration();
                        Called call = exp == null ? new Called("Rater could not be calculated: no result", null)
                            : apiCall.call(tc, configuration, exp, client);
                        called.put(tc.id(), call);
                        if (uiEnabled) {
                            // the UI flow of this iteration, with the same test data and the API response just received
                            String configId = configuration == null ? "" : configuration.id();
                            if (uiFlow == null && uiProblem == null) {
                                try {
                                    uiFlow = uiSetup.newFlow();      // opens the browser and logs in, once
                                } catch (Exception e) {
                                    uiProblem = "UI flow could not start: " + e.getMessage();
                                    log.error(uiProblem, e);
                                }
                            }
                            log.info("{}: UI flow", tc.id());
                            uiLines.put(tc.id(), uiProblem != null
                                ? List.of(new Line(tc.id(), "(UI flow)", "", "", RaterReportWriter.FAIL, uiProblem, configId))
                                : uiSetup.compare(settings, tc, configId, call, uiFlow, iterationsDir.resolve(tc.id())));
                        }
                        progress.step();
                    }
                } catch (Exception e) {
                    workerFailure.compareAndSet(null, e);
                } finally {
                    if (uiFlow != null) {
                        try {
                            uiFlow.close();
                        } catch (Exception e) {
                            log.warn("Could not close the UI flow: {}", e.getMessage());
                        }
                    }
                }
            }, "api-" + w);
            worker.start();
            workers.add(worker);
        }
        for (Thread worker : workers) worker.join();
        if (workerFailure.get() != null) throw workerFailure.get();

        Map<String, RaterWorkbook.RaterOutputs> expected;
        try {
            expected = new java.util.LinkedHashMap<>(raterDone.join());
        } catch (java.util.concurrent.CompletionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }

        // Wind: the API takes the Gross AAL from its own wind model (TigerCQ), not from RMSModeledPremium. The Rater's Gross
        // AAL is its RMSModeledPremium input (C156 = C44), so wind cases are recalculated with the API's Gross AAL as that
        // input; the wind premium and the totals then compare the rating, not the model.
        expected.putAll(recalculateWithApiGrossAal(settings, rater, runnable, called, readRows));

        List<Line> lines = new ArrayList<>();
        // Compare with the inputs the case was rated with (county / wind adjusted above), not the test data row as read
        Map<String, RaterTestCase> rated = runnable.stream().collect(Collectors.toMap(RaterTestCase::id, c -> c, (a, b) -> a));
        for (RaterTestCase original : cases) {
            RaterTestCase tc = rated.getOrDefault(original.id(), original);
            RaterConfiguration.Resolution resolution = resolved.get(tc.id());
            String configId = configurationId(resolution);
            if (resolution.problem() != null) {
                log.warn("{}: skipped - {}", tc.id(), resolution.problem());
                lines.add(new Line(tc.id(), "(test case)", "", "", RaterReportWriter.SKIPPED, resolution.problem(), configId));
                continue;
            }
            RaterWorkbook.RaterOutputs exp = expected.get(tc.id());
            Called call = called.get(tc.id());
            // Attributes the API does not return for this configuration (configuration.<ID>.notApplicableRows)
            List<Integer> notApplicable = RaterSettings.parseRows(settings.get("configuration." + configId + ".notApplicableRows"));
            boolean renewal = isRenewal(settings, tc);
            for (int row : compareRows) {
                RaterAttribute attr = byRow.get(row);
                if (attr == null) continue;     // separator row with no attribute name
                if (renewalOnly.contains(row) && !renewal) {
                    lines.add(new Line(tc.id(), attr.label(), "", "", RaterReportWriter.NOT_APPLICABLE,
                        "Renewal only (New Business case)", configId));
                    continue;
                }
                Integer renewalRow = renewal ? renewalRows.get(row) : null;
                // Renewal case: the response attribute of this row is compared with the Rater's renewal row
                String label = renewalRow != null && byRow.containsKey(renewalRow)
                    ? byRow.get(renewalRow).label() + " [renewal; New Business C" + row + "]" : attr.label();
                int raterRow = renewalRow != null ? renewalRow : row;
                String expectedValue = exp != null && exp.values() != null ? exp.values().getOrDefault(raterRow, "") : "";
                if (notApplicable.contains(row) && call.apiProblem() == null) {
                    lines.add(new Line(tc.id(), label, expectedValue, call.responseMapper().read(row).value(),
                        RaterReportWriter.NOT_APPLICABLE, "Not applicable to " + configId, configId));
                    continue;
                }
                Line line = compareRow(settings, tc, row, label, expectedValue, call.responseMapper(), call.apiProblem());
                lines.add(new Line(line.testCaseId(), line.attributePath(), line.expected(), line.actual(), line.result(),
                    line.remarks(), configId));
            }
        }

        Path report = runDir.resolve("RaterValidationReport_" + stamp + ".xlsx");
        RaterReportWriter.write(report, lines);

        Map<String, Long> counts = lines.stream().collect(Collectors.groupingBy(Line::result, Collectors.counting()));
        long fail = counts.getOrDefault(RaterReportWriter.FAIL, 0L);
        log.info("=== Rater validation: {} attribute checks, {} Pass, {} Fail, {} N/A; {} test case(s) skipped ===", lines.size(),
            counts.getOrDefault(RaterReportWriter.PASS, 0L), fail, counts.getOrDefault(RaterReportWriter.NOT_APPLICABLE, 0L),
            counts.getOrDefault(RaterReportWriter.SKIPPED, 0L));

        if (uiEnabled) {
            // API vs UI of the same iterations, in test data order (skipped cases as in the Rater report)
            List<Line> ui = new ArrayList<>();
            for (RaterTestCase tc : cases) {
                RaterConfiguration.Resolution resolution = resolved.get(tc.id());
                String configId = configurationId(resolution);
                if (resolution.problem() != null) {
                    ui.add(new Line(tc.id(), "(test case)", "", "", RaterReportWriter.SKIPPED, resolution.problem(), configId));
                } else {
                    ui.addAll(uiLines.getOrDefault(tc.id(), List.of()));
                }
            }
            RaterReportWriter.write(runDir.resolve("UiValidationReport_" + stamp + ".xlsx"), ui,
                "API Attribute (response path)", "Expected (API)", "Actual (UI)");
            Map<String, Long> uiCounts = ui.stream().collect(Collectors.groupingBy(Line::result, Collectors.counting()));
            log.info("=== UI vs API validation{}: {} attribute checks, {} Pass, {} Fail, {} N/A ===", uiSetup.stub() ? " (STUB UI)" : "",
                ui.size(), uiCounts.getOrDefault(RaterReportWriter.PASS, 0L), uiCounts.getOrDefault(RaterReportWriter.FAIL, 0L),
                uiCounts.getOrDefault(RaterReportWriter.NOT_APPLICABLE, 0L));
            fail += uiCounts.getOrDefault(RaterReportWriter.FAIL, 0L);
        }
        log.info("Results are in {}", runDir.toAbsolutePath());
        return fail == 0 ? 0 : 1;
    }

    /**
     * The request-only values of a case plus its location, fetched at run time from its County, State and ZIP: a real
     * street address (LocationFullAddress), its City, Latitude, Longitude and County (as spelled in the Rater's county
     * table; the State is the test data's). Every case gets one; test data that already
     * gives Latitude and Longitude (Request: columns) is used as it is.
     */
    static Map<String, String> withLocation(RaterSettings settings, RaterGeocoder geocoder, RaterTestCase tc,
                                            Map<Integer, String> requestInputs) {
        return withLocation(settings, geocoder, tc, requestInputs, null);
    }

    /** As above for a configuration: the County comes from that subline's rows of the county table. */
    static Map<String, String> withLocation(RaterSettings settings, RaterGeocoder geocoder, RaterTestCase tc,
                                            Map<Integer, String> requestInputs, String configurationId) {
        Map<String, String> values = tc.requestValues();
        if (geocoder == null) return values;
        if (!values.getOrDefault("Latitude", "").isBlank() && !values.getOrDefault("Longitude", "").isBlank()) {
            // The address is in the test data (Request:LocationFullAddress / City / Latitude / Longitude): nothing is looked
            // up; only the County is spelled as in the county table for the subline
            String county = geocoder.knownCounty(requestInputs.get(16), requestInputs.get(15), configurationId);
            if (county == null || county.isBlank() || !values.getOrDefault("County", "").isBlank()) return values;
            Map<String, String> result = new java.util.LinkedHashMap<>(values);
            result.put("County", county);
            return result;
        }
        RaterGeocoder.Location location = geocoder.locate(values.get("LocationFullAddress"), requestInputs.get(16),
            requestInputs.get(15), requestInputs.get(7), requestInputs.get(17), configurationId);
        Map<String, String> result = new java.util.LinkedHashMap<>(values);
        result.put("LocationFullAddress", location.address());
        result.put("Latitude", location.latitude());
        result.put("Longitude", location.longitude());
        if (!location.city().isBlank() && values.getOrDefault("City", "").isBlank()) result.put("City", location.city());
        // The County sent is spelled as in the Rater's county table for the subline (rater.address.countyTable)
        if (!location.county().isBlank() && values.getOrDefault("County", "").isBlank()) result.put("County", location.county());
        return result;
    }

    /**
     * The request's state code (StateRegionCode, rater.request.stateCodeRow) is the code of the case's state
     * (rater.request.stateRow): the Rater's own lookup leaves it blank for some locations (e.g. Connecticut planning
     * regions). The Rater's value is kept only when the state name is not a known US state.
     */
    static void withStateCode(RaterSettings settings, Map<Integer, String> requestInputs) {
        String codeRow = settings.get("rater.request.stateCodeRow");
        String stateRow = settings.get("rater.request.stateRow");
        if (codeRow.isEmpty() || stateRow.isEmpty()) return;
        String code = StateCodes.of(requestInputs.get(Integer.parseInt(stateRow)));
        if (code != null) requestInputs.put(Integer.parseInt(codeRow), code);
    }

    /**
     * The cases with their County (row 16) replaced where the test data county is not in the Rater's county table for the
     * case's State and subline: the table county of a real address in the case's ZIP is used for the Rater and the
     * request alike. Counties that are in the table (in any spelling) are kept as they are. The test data file is not changed.
     */
    static List<RaterTestCase> withTableCounties(RaterGeocoder geocoder, Map<String, RaterConfiguration.Resolution> resolved,
                                                 List<RaterTestCase> cases) {
        if (geocoder == null) return cases;
        List<RaterTestCase> result = new ArrayList<>();
        for (RaterTestCase tc : cases) {
            Map<Integer, String> in = tc.inputs();
            String configId = configurationId(resolved.get(tc.id()));
            String county = in.get(16);
            if (county == null || geocoder.knownCounty(county, in.get(15), configId) != null) {
                result.add(tc);
                continue;
            }
            String tableCounty = geocoder.locate("", county, in.get(15), StateCodes.of(in.get(15)), in.get(17), configId).county();
            if (tableCounty.isBlank()) {
                result.add(tc);
                continue;
            }
            log.info("{}: County '{}' is not in the Rater's county table for {} {} - '{}' (county of ZIP {}) is used for the "
                + "Rater and the request", tc.id(), county, in.get(15), configId, tableCounty, in.get(17));
            Map<Integer, String> inputs = new java.util.LinkedHashMap<>(in);
            inputs.put(16, tableCounty);
            result.add(tc.withInputs(inputs));
        }
        return result;
    }

    /**
     * Inputs that stand for another row's value (rater.input.valueFrom.&lt;row&gt;=&lt;source row&gt;|&lt;text&gt;) take that
     * value: e.g. Water Damage Deductible (38) "Equal to AOP" -> the AOP Deductible (37) amount, for the Rater and the request.
     * "*" as text = always (the row gets the source row's value, e.g. the Rater's renewal Insurance Score Range = row 40).
     * Renewal cases (rater.request.renewal.row = rater.request.renewal.value) also get the inputs rater.input.renewal.&lt;row&gt;
     * that the test data does not have (e.g. Book Of Business Adjustment "No"), for the Rater and the request.
     * Test data values with a Rater wording (rater.input.alias.&lt;row&gt;=value=Rater value;...) take that wording, e.g. Wind
     * Deductible 0.01 -> "Greater of $2,500 or 1%". Cases of a state rule's states (rater.request.stateRule.&lt;rule&gt;.states)
     * get the rule's Rater inputs (rater.request.stateRule.&lt;rule&gt;.raterInputs=row=value;...), e.g. Insurance Score Range
     * "Not Applicable" in HI / KY / MA / MD.
     */
    static List<RaterTestCase> withValuesFromRows(RaterSettings settings, List<RaterTestCase> cases) {
        return withPolicyDates(settings,
            withRenewalInputs(settings, withSourceValues(settings, withStateInputs(settings, withAliases(settings, cases)))));
    }

    /**
     * rater.dates.effective (today / MM/dd/yyyy; blank = the Rater's and the templates' own dates): the Rater's Applicable
     * start date (row 2) and Effective date (row 3) = that date, Expiration date (row 4) = one year later. The request
     * builder sends the same dates (RaterRequestMapper.applyPolicyDates), the UI takes them from the request.
     */
    static List<RaterTestCase> withPolicyDates(RaterSettings settings, List<RaterTestCase> cases) {
        String[] dates = policyDates(settings);
        if (dates == null) return cases;
        List<RaterTestCase> result = new ArrayList<>();
        for (RaterTestCase tc : cases) {
            Map<Integer, String> inputs = new java.util.LinkedHashMap<>(tc.inputs());
            inputs.put(2, dates[0]);
            inputs.put(3, dates[0]);
            inputs.put(4, dates[1]);
            result.add(tc.withInputs(inputs));
        }
        return result;
    }

    /** [effective, expiration] as MM/dd/yyyy from rater.dates.effective, or null when it is blank. */
    static String[] policyDates(RaterSettings settings) {
        String setting = settings.get("rater.dates.effective").trim();
        if (setting.isEmpty()) return null;
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy");
        java.time.LocalDate effective = setting.equalsIgnoreCase("today") ? java.time.LocalDate.now()
            : java.time.LocalDate.parse(setting, f);
        return new String[]{effective.format(f), effective.plusYears(1).format(f)};
    }

    private static List<RaterTestCase> withAliases(RaterSettings settings, List<RaterTestCase> cases) {
        Map<Integer, Map<String, String>> aliases = new java.util.LinkedHashMap<>();
        for (int row : settings.inputRows()) {
            Map<String, String> map = pairs(settings.get("rater.input.alias." + row));
            if (!map.isEmpty()) aliases.put(row, map);
        }
        if (aliases.isEmpty()) return cases;
        List<RaterTestCase> result = new ArrayList<>();
        for (RaterTestCase tc : cases) {
            Map<Integer, String> inputs = new java.util.LinkedHashMap<>(tc.inputs());
            aliases.forEach((row, map) -> {
                String value = inputs.get(row);
                if (value == null) return;
                String number = normalNumber(value.trim());
                String rater = map.getOrDefault(value.trim(), map.get(number));
                if (rater != null) inputs.put(row, rater);
            });
            result.add(inputs.equals(tc.inputs()) ? tc : tc.withInputs(inputs));
        }
        return result;
    }

    /** "a=b;c=d" -> {a=b, c=d} (keys as numbers without trailing zeros when they are numbers). */
    private static Map<String, String> pairs(String text) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String entry : text.split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0) map.put(normalNumber(entry.substring(0, eq).trim()), entry.substring(eq + 1).trim());
        }
        return map;
    }

    private static String normalNumber(String value) {
        try {
            return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static List<RaterTestCase> withStateInputs(RaterSettings settings, List<RaterTestCase> cases) {
        Map<String, Map<String, String>> rules = new java.util.LinkedHashMap<>();
        for (String rule : settings.list("rater.request.stateRules", ",")) {
            Map<String, String> values = pairs(settings.get("rater.request.stateRule." + rule + ".raterInputs"));
            if (!values.isEmpty()) rules.put(rule, values);
        }
        if (rules.isEmpty()) return cases;
        int stateRow = Integer.parseInt(settings.get("rater.request.stateRow", "15"));
        List<RaterTestCase> result = new ArrayList<>();
        for (RaterTestCase tc : cases) {
            String code = StateCodes.of(tc.inputs().get(stateRow));
            Map<Integer, String> inputs = new java.util.LinkedHashMap<>(tc.inputs());
            rules.forEach((rule, values) -> {
                if (RaterRequestMapper.stateRuleApplies(settings, rule, null, code)) {
                    values.forEach((row, value) -> inputs.put(Integer.parseInt(row), value));
                }
            });
            result.add(inputs.equals(tc.inputs()) ? tc : tc.withInputs(inputs));
        }
        return result;
    }

    private static List<RaterTestCase> withRenewalInputs(RaterSettings settings, List<RaterTestCase> cases) {
        String row = settings.get("rater.request.renewal.row");
        if (row.isEmpty()) return cases;
        Map<Integer, String> renewalInputs = new java.util.LinkedHashMap<>();
        for (int r : settings.inputRows()) {
            String value = settings.get("rater.input.renewal." + r);
            if (!value.isEmpty()) renewalInputs.put(r, value);
        }
        if (renewalInputs.isEmpty()) return cases;
        String renewal = settings.get("rater.request.renewal.value", "Renewal");
        List<RaterTestCase> result = new ArrayList<>();
        for (RaterTestCase tc : cases) {
            if (!renewal.equalsIgnoreCase(tc.inputs().getOrDefault(Integer.parseInt(row), "").trim())) {
                // New Business: an empty renewal column (e.g. 89 BookOfBusinessAdjustment) is neither rated nor sent
                if (renewalInputs.keySet().stream().noneMatch(r -> tc.inputs().containsKey(r) && tc.inputs().get(r).isBlank())) {
                    result.add(tc);
                    continue;
                }
                Map<Integer, String> inputs = new java.util.LinkedHashMap<>(tc.inputs());
                renewalInputs.keySet().forEach(r -> { if (inputs.getOrDefault(r, "x").isBlank()) inputs.remove(r); });
                result.add(tc.withInputs(inputs));
                continue;
            }
            Map<Integer, String> inputs = new java.util.LinkedHashMap<>(tc.inputs());
            renewalInputs.forEach((r, v) -> {
                if (inputs.getOrDefault(r, "").isBlank()) inputs.put(r, v);
            });
            result.add(tc.withInputs(inputs));
        }
        return result;
    }

    private static List<RaterTestCase> withSourceValues(RaterSettings settings, List<RaterTestCase> cases) {
        Map<Integer, String[]> rules = new java.util.LinkedHashMap<>();
        for (int row : settings.inputRows()) {
            String rule = settings.get("rater.input.valueFrom." + row);
            int bar = rule.indexOf('|');
            if (bar > 0) rules.put(row, new String[]{rule.substring(0, bar).trim(), rule.substring(bar + 1).trim()});
        }
        if (rules.isEmpty()) return cases;
        List<RaterTestCase> result = new ArrayList<>();
        for (RaterTestCase tc : cases) {
            Map<Integer, String> inputs = null;
            for (Map.Entry<Integer, String[]> r : rules.entrySet()) {
                String value = tc.inputs().get(r.getKey());
                String source = tc.inputs().get(Integer.parseInt(r.getValue()[0]));
                boolean always = "*".equals(r.getValue()[1]);
                if (source == null || source.isBlank()) continue;
                if (!always && (value == null || !value.trim().equalsIgnoreCase(r.getValue()[1]))) continue;
                if (inputs == null) inputs = new java.util.LinkedHashMap<>(tc.inputs());
                inputs.put(r.getKey(), source.trim());
            }
            result.add(inputs == null ? tc : tc.withInputs(inputs));
        }
        return result;
    }

    /**
     * Wind cases of a state where TigerCQ does not apply (rule rater.wind.offRule, e.g. tigerCQNA) are rated without wind:
     * the Rater gets PLWindCoverage (rater.wind.row) "No" and the request keeps the wind request's shape with
     * PLWindCoverage "No" and TigerCQApplicable "N". The test data file is not changed.
     */
    static List<RaterTestCase> withoutTigerCqWind(RaterSettings settings, Map<String, RaterConfiguration.Resolution> resolved,
                                                  List<RaterTestCase> cases) {
        String rule = settings.get("rater.wind.offRule");
        if (rule.isEmpty()) return cases;
        List<RaterTestCase> result = new ArrayList<>();
        for (RaterTestCase tc : cases) {
            String configId = resolved.get(tc.id()) == null ? "" : configurationId(resolved.get(tc.id()));
            String code = StateCodes.of(tc.inputs().get(Integer.parseInt(settings.get("rater.request.stateRow", "15"))));
            if (!RaterRequestMapper.windRequested(settings, tc.inputs())
                || !RaterRequestMapper.stateRuleApplies(settings, rule, configId, code)) {
                result.add(tc);
                continue;
            }
            log.info("{}: TigerCQ does not apply in {} ({}) - rated without wind (PLWindCoverage No, TigerCQApplicable N)",
                tc.id(), code, configId);
            Map<Integer, String> inputs = new java.util.LinkedHashMap<>(tc.inputs());
            inputs.put(settings.windRow(), "No");
            Map<String, String> requestValues = new java.util.LinkedHashMap<>(tc.requestValues());
            requestValues.put(RaterRequestMapper.WIND_REQUEST_SHAPE, "true");
            result.add(new RaterTestCase(tc.id(), inputs, requestValues));
        }
        return result;
    }

    /** Outcome of one API call: the problem (null when the call succeeded) and the response to read the actual values from. */
    record Called(String apiProblem, RaterResponseMapper responseMapper) {}

    /**
     * Builds, sends and saves one case's request. Shared by the API workers; it keeps no per-call state. {@code exp} = the
     * Rater's values of the case (derived request rows), or null when the run has no Rater (UI vs API validation).
     */
    record ApiCall(RaterSettings settings, RtRequestLoader loader, RaterRequestMapper requestMapper, RaterGeocoder geocoder,
                           List<Integer> derivedRows, String url, Path iterationsDir, int retries, TokenWait tokenWait) {

        Called call(RaterTestCase tc, RaterConfiguration configuration, RaterWorkbook.RaterOutputs exp, ApiClient client)
            throws java.io.IOException {
            String apiProblem = null;
            String responseBody = "";
            Path caseDir = iterationsDir.resolve(tc.id());      // request.json + response.json for this iteration
            Files.createDirectories(caseDir);

            if (exp != null && exp.error() != null) {
                apiProblem = "Rater could not be calculated: " + exp.error();
            } else if (client == null) {
                apiProblem = "API not called: no endpoint configured (rt.endpoint)";
            } else {
                try {
                    Map<Integer, String> requestInputs = new java.util.LinkedHashMap<>(tc.inputs());
                    for (int row : exp == null ? List.<Integer>of() : derivedRows) {   // Rater values for the request, e.g. the state code
                        requestInputs.put(row, exp.values() == null ? "" : exp.values().getOrDefault(row, ""));
                    }
                    withStateCode(settings, requestInputs);
                    Map<String, String> requestValues = withLocation(settings, geocoder, tc, requestInputs,
                        configuration == null ? null : configuration.id());
                    String body = requestMapper.build(configuration, requestInputs, requestValues);
                    ApiRequest request = loader.load(url, body);
                    saveRequest(caseDir.resolve("request.json"), request);
                    ApiResponse response = client.execute(request);
                    // The UAT service is sometimes intermittent (HTTP 5xx / no connection): retry before giving up
                    for (int attempt = 1; attempt <= retries && (response.getStatusCode() == 0 || response.getStatusCode() >= 500); attempt++) {
                        log.warn("{}: HTTP {} - retry {}/{}", tc.id(), response.getStatusCode(), attempt, retries);
                        Thread.sleep(1500);
                        response = client.execute(request);
                    }
                    // An expired token fails every remaining case: wait for APIToken.txt to be updated, then send again
                    while (response.getStatusCode() == 401 && tokenWait.newTokenAfter(request.getHeaders().get("Token"))) {
                        request = loader.load(url, body);
                        saveRequest(caseDir.resolve("request.json"), request);
                        log.info("{}: sending again with the new token", tc.id());
                        response = client.execute(request);
                    }
                    responseBody = response.getBody() == null ? "" : response.getBody();
                    saveResponse(caseDir, response);
                    if (!response.isSuccess()) {
                        apiProblem = "API failure: HTTP " + response.getStatusCode() + " " + response.getStatusText()
                            + errorMessage(responseBody);
                    } else {
                        log.info("{}: HTTP {} in {} ms", tc.id(), response.getStatusCode(), response.getResponseTimeMs());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    apiProblem = "API call failed: interrupted";
                } catch (Exception e) {
                    apiProblem = "API call failed: " + e.getMessage();
                }
            }
            if (apiProblem != null) log.warn("{}: {}", tc.id(), apiProblem);
            return new Called(apiProblem, apiProblem == null ? new RaterResponseMapper(settings, responseBody) : null);
        }
    }

    /**
     * After an HTTP 401 the workers wait (up to rater.api.tokenWaitMinutes, 0 = do not wait) for APIToken.txt to hold a new
     * token instead of failing every remaining case. Once a token has been given up on, later 401s for it fail at once.
     */
    static final class TokenWait {
        private final Path tokenFile;
        private final long maxMillis;
        private String givenUp;

        TokenWait(Path tokenFile, long minutes) {
            this.tokenFile = tokenFile;
            this.maxMillis = minutes * 60_000L;
        }

        /** True when the token file holds a token other than the rejected one (waits for it up to the limit). */
        synchronized boolean newTokenAfter(String rejected) throws InterruptedException {
            long end = System.currentTimeMillis() + maxMillis;
            boolean told = false;
            while (true) {
                String current = RtRequestLoader.readToken(tokenFile);
                if (current != null && !current.isBlank() && !current.equals(rejected)) return true;
                if (maxMillis == 0 || rejected == null || rejected.equals(givenUp) || System.currentTimeMillis() >= end) {
                    if (told) log.warn("No new token in {} after {} minute(s); the remaining calls with the old token fail",
                        tokenFile, maxMillis / 60_000L);
                    givenUp = rejected;
                    return false;
                }
                if (!told) {
                    log.warn("The API rejected the token (HTTP 401). Update {} - waiting up to {} minute(s) for a new token",
                        tokenFile.toAbsolutePath().normalize(), maxMillis / 60_000L);
                    told = true;
                }
                Thread.sleep(5000);
            }
        }
    }

    /** Logs "<name>: n/total done" every 5% (at least every 100 cases) with the time left, for long runs. */
    static final class Progress {
        private final String name;
        private final int total;
        private final int every;
        private final long start = System.currentTimeMillis();
        private final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();

        Progress(String name, int total) {
            this.name = name;
            this.total = total;
            this.every = Math.max(1, Math.min(100, total / 20));
        }

        void step() {
            int n = done.incrementAndGet();
            if (total < 20 || (n % every != 0 && n != total)) return;
            long elapsed = System.currentTimeMillis() - start;
            long left = elapsed / n * (total - n);
            log.info("{}: {}/{} done ({} min elapsed, about {} min left)", name, n, total, elapsed / 60_000, (left + 59_999) / 60_000);
        }
    }

    private static java.util.concurrent.Executor daemonThread(String name) {
        return task -> {
            Thread t = new Thread(task, name);
            t.setDaemon(true);
            t.start();
        };
    }

    /**
     * Expected values of the wind cases recalculated with the Gross AAL the API returned (rater.wind.aalOutputRow) as the
     * Rater's Gross AAL input (rater.wind.aalInputRow). Cases without a usable API Gross AAL keep their first values.
     */
    private static Map<String, RaterWorkbook.RaterOutputs> recalculateWithApiGrossAal(RaterSettings settings, RaterWorkbook rater,
            List<RaterTestCase> runnable, Map<String, Called> called, List<Integer> readRows) throws Exception {
        String inputRow = settings.get("rater.wind.aalInputRow");
        String outputRow = settings.get("rater.wind.aalOutputRow");
        if (inputRow.isEmpty() || outputRow.isEmpty()) return Map.of();
        List<RaterTestCase> wind = new ArrayList<>();
        for (RaterTestCase tc : runnable) {
            Called call = called.get(tc.id());
            if (call == null || call.apiProblem() != null || !RaterRequestMapper.windRequested(settings, tc.inputs())) continue;
            Actual aal = call.responseMapper().read(Integer.parseInt(outputRow));
            if (aal.status() != Status.FOUND || !aal.value().trim().matches("-?\\d+(\\.\\d+)?([eE]-?\\d+)?")) {
                log.warn("{}: wind requested but the API returned no Gross AAL ({}); Rater keeps RMSModeledPremium", tc.id(), aal.value());
                continue;
            }
            Map<Integer, String> inputs = new java.util.LinkedHashMap<>(tc.inputs());
            inputs.put(Integer.parseInt(inputRow), aal.value().trim());
            wind.add(tc.withInputs(inputs));
        }
        if (wind.isEmpty()) return Map.of();
        log.info("Wind: recalculating {} test case(s) with the API's Gross AAL as Rater row {}", wind.size(), inputRow);
        Map<String, RaterWorkbook.RaterOutputs> result = new java.util.LinkedHashMap<>(rater.calculateExpected(wind, readRows, "-wind"));
        result.values().removeIf(o -> o.error() != null);
        return result;
    }

    /**
     * "Invalid test data: ..." when an input that has a dropdown in the Rater is empty (e.g. Alarm instead of "None"),
     * otherwise null. Such a case cannot be rated as intended, so it is reported instead of run. optionalRows may be empty.
     */
    static String blankDropdownInputs(RaterTestCase tc, Map<Integer, RaterAttribute> byRow, List<Integer> optionalRows) {
        List<String> blank = tc.inputs().entrySet().stream()
            .filter(e -> e.getValue() == null || e.getValue().isBlank())
            .filter(e -> !optionalRows.contains(e.getKey()))
            .filter(e -> byRow.containsKey(e.getKey()) && !byRow.get(e.getKey()).getValues().isEmpty())
            .map(e -> byRow.get(e.getKey()).getName() + " (row " + e.getKey() + ")").toList();
        return blank.isEmpty() ? null
            : "Invalid test data: empty value for " + String.join(", ", blank) + " - pick one of the Rater dropdown values";
    }

    private static String configurationId(RaterConfiguration.Resolution resolution) {
        return resolution.configuration() == null ? "" : resolution.configuration().id();
    }

    /** APIRequest.txt is only needed when no configurations (and so no per-configuration templates) are defined. */
    static String defaultTemplate(RtRequestLoader loader, List<RaterConfiguration> configurations) throws java.io.IOException {
        try {
            return loader.readRequestTemplate();
        } catch (java.io.IOException e) {
            if (configurations.isEmpty()) throw e;
            log.debug("No default request template ({}); the configuration templates are used", e.getMessage());
            return "{}";
        }
    }

    /**
     * ENTITY TYPE (column I) decides what an attribute is: Req/Resp = request input, Resp = response output. An input row
     * classified Resp, or an output row classified Req/Resp, is a configuration error and is logged.
     */
    static void checkEntityTypes(RaterSettings settings, Map<Integer, RaterAttribute> byRow) {
        List<Integer> respInputs = settings.inputRows().stream()
            .filter(r -> byRow.containsKey(r) && "Resp".equalsIgnoreCase(String.valueOf(byRow.get(r).getEntityType()).trim())).toList();
        List<Integer> reqOutputs = settings.outputRows().stream()
            .filter(r -> byRow.containsKey(r) && "Req/Resp".equalsIgnoreCase(String.valueOf(byRow.get(r).getEntityType()).trim())).toList();
        if (!respInputs.isEmpty()) log.warn("Input rows whose ENTITY TYPE is Resp (response-only, should not be inputs): {}", respInputs);
        if (!reqOutputs.isEmpty()) log.warn("Output rows whose ENTITY TYPE is Req/Resp (request attributes): {}", reqOutputs);
    }

    /** " - <Message from the API error body>" when the body is a JSON error with a Message, otherwise nothing. */
    static String errorMessage(String body) {
        try {
            String message = MAPPER.readTree(body).path("Message").asText("");
            return message.isBlank() ? "" : " - " + (message.length() > 200 ? message.substring(0, 200) : message);
        } catch (Exception notJson) {
            return "";
        }
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
        String body = response.getBody() == null ? "" : maskToken(response.getBody());
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

    static Line compareRow(RaterSettings settings, RaterTestCase tc, RaterAttribute attr, String expected,
                                   RaterResponseMapper responseMapper, String apiProblem) {
        return compareRow(settings, tc, attr.getRow(), attr.label(), expected, responseMapper, apiProblem);
    }

    /** True when the case is a Renewal (rater.request.renewal.row = rater.request.renewal.value). */
    static boolean isRenewal(RaterSettings settings, RaterTestCase tc) {
        String row = settings.get("rater.request.renewal.row");
        return !row.isEmpty() && settings.get("rater.request.renewal.value", "Renewal")
            .equalsIgnoreCase(tc.inputs().getOrDefault(Integer.parseInt(row), "").trim());
    }

    /** rater.output.renewal.&lt;output row&gt;=&lt;renewal row&gt; for the output rows. */
    static Map<Integer, Integer> renewalRows(RaterSettings settings, List<Integer> outputRows) {
        Map<Integer, Integer> map = new java.util.LinkedHashMap<>();
        for (int row : outputRows) {
            String value = settings.get("rater.output.renewal." + row);
            if (!value.isEmpty()) map.put(row, Integer.parseInt(value));
        }
        return map;
    }

    /** Compares the Rater value with the response attribute of output row {@code row} (its mapping, tolerance, rules). */
    static Line compareRow(RaterSettings settings, RaterTestCase tc, int row, String label, String expected,
                           RaterResponseMapper responseMapper, String apiProblem) {
        if (apiProblem != null) {
            return new Line(tc.id(), label, expected, "", RaterReportWriter.FAIL, apiProblem);
        }
        Actual actual = responseMapper.read(row);
        // Rows whose Rater text explains the answer (rater.compare.firstWord, e.g. C244 "No – Carry prior ISF (4 months)")
        // are compared by their first word ("No"), which is what the API returns
        if (RaterSettings.parseRows(settings.get("rater.compare.firstWord")).contains(row) && actual.status() == Status.FOUND) {
            String word = expected.trim().split("[\\s\\u2013\\u2014-]+", 2)[0];
            boolean pass = word.equalsIgnoreCase(actual.value().trim());
            return new Line(tc.id(), label, expected, actual.value(), pass ? RaterReportWriter.PASS : RaterReportWriter.FAIL,
                pass ? "First word of the Rater text matches" : "First word \"" + word + "\" differs");
        }
        // The Rater marks attributes that its formulas do not calculate for this configuration (e.g. "N/A", "additive"):
        // nothing to compare against, so the row is reported as N/A instead of Pass/Fail
        if (settings.list("rater.compare.notApplicable", "|").stream().anyMatch(t -> t.equalsIgnoreCase(expected.trim()))) {
            return new Line(tc.id(), label, expected, actual.value(), RaterReportWriter.NOT_APPLICABLE,
                "Not applicable to this configuration in the Rater");
        }
        // Resp attributes of the Rater that the API never returns (rater.compare.notInResponse): nothing to compare against
        if (RaterSettings.parseRows(settings.get("rater.compare.notInResponse")).contains(row)) {
            return new Line(tc.id(), label, expected, "", RaterReportWriter.NOT_APPLICABLE, "Not returned by the API");
        }
        // Wind attributes (rater.compare.windOnly) the API returns only when wind coverage is selected: nothing to compare
        // against in a case without wind
        if (RaterSettings.parseRows(settings.get("rater.compare.windOnly")).contains(row)
            && !RaterRequestMapper.windRequested(settings, tc.inputs())) {
            return new Line(tc.id(), label, expected, actual.value(), RaterReportWriter.NOT_APPLICABLE,
                "Returned by the API only with wind coverage");
        }
        // An attribute the API does not calculate here (optional coverage not requested, other subline's attribute) has no
        // node or an empty value in the response; that matches a Rater value of 0 or blank
        boolean noActual = actual.status() == Status.NOT_FOUND || (actual.status() == Status.FOUND && actual.value().isBlank());
        if (noActual && (expected.isBlank() || ValueComparator.compare(expected, "0", 0).pass())) {
            return new Line(tc.id(), label, expected, actual.value(), RaterReportWriter.PASS,
                "Not calculated by the API; Rater value is " + (expected.isBlank() ? "blank" : "0"));
        }
        if (actual.status() != Status.FOUND) {
            return new Line(tc.id(), label, expected, "", RaterReportWriter.FAIL, actual.detail());
        }
        Outcome outcome = ValueComparator.compare(expected, actual.value(), settings.tolerance(row));
        return new Line(tc.id(), label, expected, actual.value(),
            outcome.pass() ? RaterReportWriter.PASS : RaterReportWriter.FAIL, outcome.remark());
    }
}
