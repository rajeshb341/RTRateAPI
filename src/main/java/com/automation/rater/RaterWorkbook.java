package com.automation.rater;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * All access to the Rater workbook. The work is done by the PowerShell scripts in scripts/rater, which drive
 * WPS Office / Excel through COM, because only a real spreadsheet engine recalculates this Rater correctly.
 */
@Slf4j
public class RaterWorkbook {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    /** Expected values for one test case; error is set when the Rater could not be calculated for it. */
    public record RaterOutputs(String error, Map<Integer, String> values) {}

    private final RaterSettings settings;
    private final Path workDir;

    public RaterWorkbook(RaterSettings settings, Path workDir) {
        this.settings = settings;
        this.workDir = workDir;
    }

    /** Reads D/E/F, current value and dropdown values of all input and output rows. */
    /** The output rows plus the renewal rows they are compared with for Renewal cases and the renewal-only rows. */
    private String outputRowsToInspect() {
        java.util.LinkedHashSet<Integer> rows = new java.util.LinkedHashSet<>(settings.outputRows());
        rows.addAll(RaterValidationRunner.renewalRows(settings, settings.outputRows()).values());
        rows.addAll(RaterSettings.parseRows(settings.get("rater.output.renewalOnly.rows")));
        return rows.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    public List<RaterAttribute> inspect(boolean resolveDependentLists) throws IOException, InterruptedException {
        Files.createDirectories(workDir);
        Path out = workDir.resolve("rater-attributes.json");
        PowerShellRunner.run(script("Rater-Inspect.ps1"), Map.of(
            "Workbook", workbook(),
            "Sheet", settings.get("rater.sheet", "Rater"),
            "ValueCol", settings.get("rater.valueColumn", "C"),
            "InputRows", settings.get("rater.input.rows"),
            "OutputRows", outputRowsToInspect(),
            "Dependent", resolveDependentLists ? settings.get("rater.dependent") : "",
            "ProgId", settings.get("rater.comProgId", "KET.Application"),
            "CountyRange", settings.get("rater.address.countyTable"),
            "OutFile", out.toAbsolutePath().toString()));

        JsonNode attributes = MAPPER.readTree(out.toFile()).path("attributes");
        return MAPPER.readerForListOf(RaterAttribute.class).readValue(attributes);
    }

    /**
     * The county table (columns named by rater.address.countyColumns, e.g. "county,state,subline"): "STATE|SUBLINE" ->
     * counties as spelled there (a county may be listed in two spellings). Read from rater.address.countyFile (rows
     * "County|State|Subline", "#" comments) when set, else from the Rater sheet range rater.address.countyTable as read by
     * the last {@link #inspect}. Without a subline column the key is "STATE|*". Empty when neither is configured.
     */
    public Map<String, List<String>> countyTable() throws IOException {
        List<List<String>> rows = new java.util.ArrayList<>();
        String countyFile = settings.get("rater.address.countyFile");
        if (!countyFile.isEmpty()) {
            for (String line : readText(countyFile).split("\\r?\\n")) {
                if (line.isBlank() || line.trim().startsWith("#")) continue;
                rows.add(java.util.Arrays.stream(line.split("\\|", -1)).map(String::trim).toList());
            }
        } else {
            Path file = workDir.resolve("rater-attributes.json");
            if (Files.isRegularFile(file)) {
                for (JsonNode row : MAPPER.readTree(file.toFile()).path("countyTable")) {
                    List<String> cells = new java.util.ArrayList<>();
                    row.forEach(c -> cells.add(c.asText().trim()));
                    rows.add(cells);
                }
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        List<String> columns = settings.list("rater.address.countyColumns", ",");
        int county = columns.indexOf("county");
        int state = columns.indexOf("state");
        int subline = columns.indexOf("subline");
        if (county < 0 || state < 0) return result;
        for (List<String> row : rows) {
            String c = county < row.size() ? row.get(county) : "";
            String s = state < row.size() ? row.get(state) : "";
            if (c.isEmpty() || s.isEmpty()) continue;
            String key = RaterGeocoder.tableKey(s, subline < 0 || subline >= row.size() ? "*" : row.get(subline));
            List<String> counties = result.computeIfAbsent(key, k -> new java.util.ArrayList<>());
            if (!counties.contains(c)) counties.add(c);
        }
        return result;
    }

    /** A text file on disk if the name is an existing path, otherwise a classpath resource. */
    private static String readText(String name) throws IOException {
        Path file = Path.of(name);
        if (Files.isRegularFile(file)) return Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        try (java.io.InputStream in = RaterWorkbook.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IOException("County table not found (file or classpath): " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Writes each case's inputs into the Rater, recalculates and returns the output cells (the Expected values). */
    public Map<String, RaterOutputs> calculateExpected(List<RaterTestCase> cases, List<Integer> outputRows)
        throws IOException, InterruptedException {
        return calculateExpected(cases, outputRows, "");
    }

    /** As above; the working files get the suffix ("rater-cases&lt;suffix&gt;.json") so a second pass keeps the first. */
    public Map<String, RaterOutputs> calculateExpected(List<RaterTestCase> cases, List<Integer> outputRows, String suffix)
        throws IOException, InterruptedException {
        return calculateExpected(cases, outputRows, suffix, null);
    }

    /**
     * As above; when onCase is given it receives each case's outputs (id, outputs) as soon as the Rater has calculated it,
     * so the API call of that case can start while the Rater goes on with the next cases. The returned map is read from
     * the result file at the end and stays the reference for the comparison.
     */
    public Map<String, RaterOutputs> calculateExpected(List<RaterTestCase> cases, List<Integer> outputRows, String suffix,
                                                       java.util.function.BiConsumer<String, RaterOutputs> onCase)
        throws IOException, InterruptedException {
        Files.createDirectories(workDir);
        Path casesFile = workDir.resolve("rater-cases" + suffix + ".json");
        Path resultFile = workDir.resolve("rater-expected" + suffix + ".json");

        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode rows = root.putArray("outputRows");
        outputRows.forEach(rows::add);
        ArrayNode caseArray = root.putArray("cases");
        for (RaterTestCase tc : cases) {
            ObjectNode node = caseArray.addObject();
            node.put("id", tc.id());
            ObjectNode inputs = node.putObject("inputs");
            tc.inputs().forEach((row, value) -> inputs.put(String.valueOf(row), value));
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(casesFile.toFile(), root);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("Workbook", workbook());
        params.put("Sheet", settings.get("rater.sheet", "Rater"));
        params.put("ValueCol", settings.get("rater.valueColumn", "C"));
        params.put("CasesFile", casesFile.toAbsolutePath().toString());
        params.put("OutFile", resultFile.toAbsolutePath().toString());
        params.put("ProgId", settings.get("rater.comProgId", "KET.Application"));
        if (Boolean.parseBoolean(settings.get("rater.saveFilledRater", "false"))) {
            params.put("SaveDir", workDir.resolve("filled-raters").toAbsolutePath().toString());
        }
        if (onCase == null) {
            PowerShellRunner.run(script("Rater-Recalc.ps1"), params);
        } else {
            PowerShellRunner.run(script("Rater-Recalc.ps1"), params, Map.of("Stream", true), CASE_PREFIX, line -> {
                try {
                    JsonNode r = MAPPER.readTree(line);
                    onCase.accept(r.path("id").asText(), outputsOf(r));
                } catch (Exception e) {
                    log.warn("Could not read a streamed Rater result ({}); it is taken from the result file", e.getMessage());
                }
            });
        }

        Map<String, RaterOutputs> result = new LinkedHashMap<>();
        for (JsonNode r : MAPPER.readTree(resultFile.toFile()).path("results")) {
            result.put(r.path("id").asText(), outputsOf(r));
        }
        log.info("Expected values calculated for {} test case(s)", result.size());
        return result;
    }

    /** Line prefix of a case result the recalculation script prints as soon as the case is calculated (-Stream). */
    private static final String CASE_PREFIX = "@@CASE ";

    /** One case of the script's result: { id, error, outputs: { "&lt;row&gt;": value } }. */
    private static RaterOutputs outputsOf(JsonNode r) {
        Map<Integer, String> values = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = r.path("outputs").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            values.put(Integer.parseInt(e.getKey()), JsonText.of(e.getValue()));
        }
        String error = r.path("error").isNull() || r.path("error").isMissingNode() ? null : r.path("error").asText();
        return new RaterOutputs(error, values);
    }

    private String workbook() {
        return settings.path("rater.workbook").toAbsolutePath().toString();
    }

    private Path script(String name) {
        return settings.path("rater.scriptsDir").resolve(name);
    }

    public static String describe(List<RaterAttribute> attributes) {
        return attributes.stream().map(RaterAttribute::label).collect(Collectors.joining(", "));
    }
}
