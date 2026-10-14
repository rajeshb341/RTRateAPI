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
    public List<RaterAttribute> inspect(boolean resolveDependentLists) throws IOException, InterruptedException {
        Files.createDirectories(workDir);
        Path out = workDir.resolve("rater-attributes.json");
        PowerShellRunner.run(script("Rater-Inspect.ps1"), Map.of(
            "Workbook", workbook(),
            "Sheet", settings.get("rater.sheet", "Rater"),
            "ValueCol", settings.get("rater.valueColumn", "C"),
            "InputRows", settings.get("rater.input.rows"),
            "OutputRows", settings.get("rater.output.rows"),
            "Dependent", resolveDependentLists ? settings.get("rater.dependent") : "",
            "ProgId", settings.get("rater.comProgId", "KET.Application"),
            "OutFile", out.toAbsolutePath().toString()));

        JsonNode attributes = MAPPER.readTree(out.toFile()).path("attributes");
        return MAPPER.readerForListOf(RaterAttribute.class).readValue(attributes);
    }

    /** Writes each case's inputs into the Rater, recalculates and returns the output cells (the Expected values). */
    public Map<String, RaterOutputs> calculateExpected(List<RaterTestCase> cases, List<Integer> outputRows)
        throws IOException, InterruptedException {
        Files.createDirectories(workDir);
        Path casesFile = workDir.resolve("rater-cases.json");
        Path resultFile = workDir.resolve("rater-expected.json");

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
        PowerShellRunner.run(script("Rater-Recalc.ps1"), params);

        Map<String, RaterOutputs> result = new LinkedHashMap<>();
        for (JsonNode r : MAPPER.readTree(resultFile.toFile()).path("results")) {
            Map<Integer, String> values = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = r.path("outputs").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                values.put(Integer.parseInt(e.getKey()), JsonText.of(e.getValue()));
            }
            String error = r.path("error").isNull() ? null : r.path("error").asText();
            result.put(r.path("id").asText(), new RaterOutputs(error, values));
        }
        log.info("Expected values calculated for {} test case(s)", result.size());
        return result;
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
