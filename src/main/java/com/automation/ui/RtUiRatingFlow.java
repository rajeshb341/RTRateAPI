package com.automation.ui;

import com.automation.rater.RaterSettings;
import com.automation.ui.pages.LoginPage;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The RT application UI for the UI vs API validation (ui.flow.class=com.automation.ui.RtUiRatingFlow). Logs in once per
 * worker (.env), then for each API test case:
 * <ol>
 *   <li>builds the UI data from the iteration's own TestData row: the values of its API request
 *       (config/ui-input-mapping.properties) + its UI:&lt;field&gt; columns (UI-only fields, e.g. UI:OfficeName);</li>
 *   <li>runs the ConfiguredNew flow with it, recording the UI's own API calls (iterations/&lt;id&gt;/ui-network/);</li>
 *   <li>Actual values: the response of the UI's rating call (last call whose URL matches ui.response.urlPattern), read with
 *       the same response paths as the API response. Until ui.response.urlPattern is set, no output is returned (N/A).</li>
 * </ol>
 * Files per case: ui-input.json (the data given to the UI), ui-network/*.json, ui-response.json, ui-result.png / ui-error.png.
 */
@Slf4j
public class RtUiRatingFlow implements UiRatingFlow {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final Playwright playwright;
    private final Browser browser;
    private final Page page;
    private final UiTestData data;
    private final UiInputMapping mapping;
    private final UiNetworkCapture capture;
    private final String responsePattern;
    private final String responseEvent;
    private final String appUrl;
    /** ui.flow.stopAt=markets: each case ends once Get Available Market is clicked (no quote yet on the environment). */
    private final boolean stopAtMarkets;
    private boolean firstCase = true;

    public RtUiRatingFlow() throws Exception {
        RaterSettings settings = RaterSettings.load();
        data = UiTestData.read(settings.path("rater.testdata.file"));
        mapping = UiInputMapping.load();
        responsePattern = settings.get("ui.response.urlPattern");
        responseEvent = settings.get("ui.response.event");
        stopAtMarkets = "markets".equalsIgnoreCase(System.getProperty("ui.flow.stopAt", settings.get("ui.flow.stopAt", "")));
        if (stopAtMarkets) {
            log.info("ui.flow.stopAt=markets: each UI case ends at Carrier Selection once Get Available Market is clicked; "
                + "UiReachSummary.txt of the run says how far each case got");
        }

        playwright = Playwright.create();
        boolean headless = Boolean.parseBoolean(System.getProperty("ui.headless", settings.get("ui.headless", "false")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
        page = browser.newPage();
        capture = new UiNetworkCapture(page, settings.get("ui.capture.urlPattern"));
        LoginPage login = new LoginPage(page);
        UiCredentials credentials = UiCredentials.load();
        appUrl = credentials.url();
        login.login(credentials);
        if (!login.isLoggedIn(60_000)) {
            throw new IllegalStateException("UI login failed" + (login.loginError().isEmpty() ? "" : ": " + login.loginError()));
        }
        if (responsePattern.isEmpty() && responseEvent.isEmpty()) {
            log.warn("ui.response.urlPattern is not set: the UI's API calls are recorded (iterations/<id>/ui-network/) but no "
                + "UI output is compared yet (N/A)");
        }
    }

    @Override
    public UiResult rate(UiCase testCase) {
        Map<String, String> input = uiInput(testCase);
        save(testCase.caseDir().resolve("ui-input.json"), input);
        capture.start();
        com.automation.ui.support.FormFiller.resetSubstitutions();
        String error = null;
        ConfiguredNewFlow flow = new ConfiguredNewFlow(page, stopAtMarkets);
        try {
            if (!firstCase) startFresh();
            firstCase = false;
            String quote = flow.run(input);
            log.info("{}: ConfiguredNew flow completed{}", testCase.testCaseId(), quote.isEmpty() ? "" : " (quote " + quote + ")");
        } catch (Exception e) {
            error = "ConfiguredNew flow failed: " + e.getMessage();
            logFormFields(testCase.testCaseId());
        }
        // every UI field read back against the API request's value (page | field | expected | shown | OK / DIFF)
        List<String> checks = com.automation.ui.support.FormFiller.inputChecks();
        if (!checks.isEmpty()) {
            List<String> lines = new java.util.ArrayList<>();
            lines.add("page | field | expected (API request) | shown in the UI | result");
            lines.addAll(checks);
            try {
                Files.write(testCase.caseDir().resolve("ui-input-check.txt"), lines, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("{}: could not write ui-input-check.txt: {}", testCase.testCaseId(), e.getMessage());
            }
            long diffs = checks.stream().filter(c -> c.endsWith("| DIFF")).count();
            log.info("{}: UI input check - {} field(s) compared with the API request, {} differ", testCase.testCaseId(),
                checks.size(), diffs);
        }
        // only with -Dui.fallback.nearestOption=true: API values the UI does not offer, replaced by the nearest UI option
        List<String> substitutions = com.automation.ui.support.FormFiller.substitutions();
        if (!substitutions.isEmpty()) {
            try {
                Files.write(testCase.caseDir().resolve("ui-substitutions.txt"), substitutions, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("{}: could not write ui-substitutions.txt: {}", testCase.testCaseId(), e.getMessage());
            }
            log.warn("{}: UI input differs from the API request ({} value(s) not offered by the UI): {}", testCase.testCaseId(),
                substitutions.size(), substitutions);
        }
        Path shot = screenshot(testCase, error == null ? "ui-result.png" : "ui-error.png");
        List<UiNetworkCapture.Call> calls = capture.stop();
        log.info("{}: {} UI API call(s) recorded in ui-network/", testCase.testCaseId(),
            UiNetworkCapture.save(calls, testCase.caseDir().resolve("ui-network")));
        if (stopAtMarkets) {
            boolean reachedMarkets = error == null;
            writeReachSummary(testCase, input, flow, error, checks);
            if (!reachedMarkets) return UiResult.failed(error, shot);
            log.info("{}: reached Carrier Selection and clicked Get Available Market{}", testCase.testCaseId(),
                flow.marketsMessage().isEmpty() ? "" : " (the UI says: " + flow.marketsMessage() + ")");
            // no quote yet: nothing to compare (the UI outputs are N/A)
            return UiResult.of(new LinkedHashMap<>(), shot);
        }
        if (error != null) return UiResult.failed(error, shot);
        if (!responseEvent.isEmpty()) {
            // the UI's rating call must have been made once the quote was created
            List<UiNetworkCapture.Call> rating = UiNetworkCapture.withEvent(calls, responseEvent);
            if (rating.isEmpty()) {
                return UiResult.failed("UI rating call " + responseEvent + " not received after the quote (ui-network/ has the "
                    + calls.size() + " calls made)", shot);
            }
            log.info("{}: UI rating call {} received {} time(s) - the last one is compared", testCase.testCaseId(),
                responseEvent, rating.size());
            return UiResult.of(outputs(testCase, rating.get(rating.size() - 1)), shot);
        }
        Optional<UiNetworkCapture.Call> byUrl = UiNetworkCapture.last(calls, responsePattern);
        if (byUrl.isEmpty() && !responsePattern.isEmpty()) {
            log.warn("{}: no UI call matched ui.response.urlPattern {}", testCase.testCaseId(), responsePattern);
        }
        return UiResult.of(byUrl.map(c -> outputs(testCase, c)).orElse(new LinkedHashMap<>()), shot);
    }

    /**
     * One line per case in the run folder's UiReachSummary.txt: the case, its form / state / occupancy / transaction / wind,
     * the last page completed, REACHED (Get Available Market clicked) or STOPPED, the reason, and its field check result.
     */
    private static synchronized void writeReachSummary(UiCase testCase, Map<String, String> input, ConfiguredNewFlow flow,
                                                       String error, List<String> checks) {
        Path file = testCase.caseDir().getParent().getParent().resolve("UiReachSummary.txt");
        long diffs = checks.stream().filter(c -> c.endsWith("| DIFF")).count();
        String reason = error != null ? error.replaceAll("\\s+", " ")
            : flow.marketsMessage().isEmpty() ? "markets requested" : "the UI says: " + flow.marketsMessage();
        String line = String.join(" | ", testCase.testCaseId(), input.getOrDefault("Policy_Form", ""),
            // the risk state (the request's first "State" is CW = countrywide)
            !testCase.input("StateRegionCode").isEmpty() ? testCase.input("StateRegionCode") : testCase.input("State"),
            input.getOrDefault("Occupancy_Type", ""), testCase.transactionType(),
            "wind " + input.getOrDefault("includeWind", ""), error == null ? "REACHED" : "STOPPED", flow.reached(),
            checks.size() + " field(s) checked, " + diffs + " differ", reason);
        try {
            if (!Files.exists(file)) {
                Files.writeString(file, "case | form | state | occupancy | transaction | wind | result | last step done | "
                    + "UI fields vs API request | reason / UI message" + System.lineSeparator(), StandardCharsets.UTF_8);
            }
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("{}: could not write UiReachSummary.txt: {}", testCase.testCaseId(), e.getMessage());
        }
    }

    /**
     * The UI data of the iteration, all from its TestData row: its API request values (ui-input-mapping.properties - they
     * always win), its UI: columns for the UI-only fields, and default.<field> values for what neither gives.
     */
    Map<String, String> uiInput(UiCase testCase) {
        Map<String, String> rows = new LinkedHashMap<>();
        testCase.inputs().forEach((k, v) -> { if (k.startsWith("row.")) rows.put(k, v); });
        Map<String, String> input = new LinkedHashMap<>(mapping.uiData(testCase.requestJson(), data.row(testCase.testCaseId()), rows));
        input.put("TestCaseId", testCase.testCaseId());
        return input;
    }

    /** Logs the dropdowns / inputs / radios of the current page (id or name, label, disabled) - to fix a locator. */
    private void logFormFields(String testCaseId) {
        try {
            Object fields = page.evaluate("() => Array.from(document.querySelectorAll("
                + "'div[id][role=button], div[id][aria-haspopup], div[role=combobox][id], input[name], textarea[name]'))"
                + ".filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; })"
                + ".map(e => { const lab = e.closest('.MuiFormControl-root')?.querySelector('label')?.innerText || '';"
                + " return (e.tagName === 'DIV' ? 'div#' + e.id : e.tagName.toLowerCase() + '[name=' + e.name"
                + " + (e.type === 'radio' ? ' value=' + e.value : '') + ']') + ' | ' + lab.replace(/\s+/g, ' ').trim().slice(0, 60)"
                + " + (e.disabled || e.getAttribute('aria-disabled') === 'true' ? ' | disabled' : ''); })");
            for (Object f : (List<?>) fields) log.info("{}: UI FIELD {}", testCaseId, f);
        } catch (Exception e) {
            log.warn("Could not list the form fields: {}", e.getMessage());
        }
    }

    /**
     * Every case after the first starts from a clean page: an open dropdown is closed and the application is opened again
     * (the session stays logged in) - so a case that failed half-way does not break the next one.
     */
    private void startFresh() {
        try {
            page.keyboard().press("Escape");
        } catch (Exception ignore) {
            // nothing open
        }
        page.navigate(appUrl);
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
            new Page.WaitForLoadStateOptions().setTimeout(120_000));
        LoginPage login = new LoginPage(page);
        if (!login.isLoggedIn(60_000)) {
            // the session ended: log in again
            try {
                login.login(UiCredentials.load());
            } catch (Exception e) {
                throw new IllegalStateException("UI login again failed: " + e.getMessage(), e);
            }
        }
    }

    /** The output attributes from the UI's rating response (same paths as the API response); saved as ui-response.json. */
    private Map<String, String> outputs(UiCase testCase, UiNetworkCapture.Call rating) {
        Map<String, String> outputs = new LinkedHashMap<>();
        String body = rating.responseBody();
        try {
            Files.writeString(testCase.caseDir().resolve("ui-response.json"), body, StandardCharsets.UTF_8);
            Object document = JsonPath.parse(body).json();
            testCase.outputPaths().forEach((key, path) -> {
                try {
                    Object value = JsonPath.read(document, path);
                    if (value != null && !(value instanceof Map) && !(value instanceof List)) outputs.put(key, value.toString());
                } catch (PathNotFoundException notInUiResponse) {
                    // not in the UI response: reported N/A
                }
            });
        } catch (Exception e) {
            log.warn("{}: the UI response {} is not JSON: {}", testCase.testCaseId(), rating.url(), e.getMessage());
        }
        return outputs;
    }

    private Path screenshot(UiCase testCase, String name) {
        try {
            Path file = testCase.caseDir().resolve(name);
            page.screenshot(new Page.ScreenshotOptions().setPath(file).setFullPage(true));
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private static void save(Path file, Map<String, String> values) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(values), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Cannot save {}: {}", file, e.getMessage());
        }
    }

    @Override
    public void close() {
        browser.close();
        playwright.close();
    }
}
