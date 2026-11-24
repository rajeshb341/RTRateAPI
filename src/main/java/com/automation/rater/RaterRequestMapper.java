package com.automation.rater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts the test case inputs into the request body template, using the row -> JSON path mapping from config.
 * With a {@link RaterConfiguration} the body starts from that configuration's template (config/templates/&lt;ID&gt;.json) and
 * the configuration's own mapping / value entries apply; without one the default template (APIRequest.txt) is used.
 */
@Slf4j
public class RaterRequestMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SEGMENT = Pattern.compile("([^\\[\\].]+)(?:\\[(\\d+)])?");

    private final RaterSettings settings;
    private final String template;
    private final RaterValueMapping values;
    private final Map<String, String> templates = new HashMap<>();
    /**
     * Request value marking a case that is rated without wind (row 43 = No) but sent in the shape of a wind request:
     * a wind case of a state where TigerCQ does not apply ({@link RaterValidationRunner#withoutTigerCqWind}).
     */
    static final String WIND_REQUEST_SHAPE = "#windRequestShape";

    public RaterRequestMapper(RaterSettings settings, String templateJson) {
        this.settings = settings;
        this.template = templateJson;
        this.values = RaterValueMapping.load(settings.get("rater.valueMapping", "config/rater-value-mapping.txt"));
    }

    /** Returns the request body for the given inputs. Rows without a mapping are skipped (logged once per call). */
    public String build(Map<Integer, String> inputs) throws IOException {
        return build(null, inputs);
    }

    /** Returns the request body of the configuration for the given inputs (Rater row -> Rater value). */
    public String build(RaterConfiguration configuration, Map<Integer, String> inputs) throws IOException {
        return build(configuration, inputs, Map.of());
    }

    /**
     * Returns the request body of the configuration for the given inputs (Rater row -> Rater value) and request-only
     * values ("Request:&lt;Name&gt;" test data columns). The wind attributes are added only when wind is requested.
     */
    public String build(RaterConfiguration configuration, Map<Integer, String> inputs, Map<String, String> requestValues)
        throws IOException {
        String id = configuration == null ? null : configuration.id();
        // A wind case of a TigerCQ-not-applicable state is rated without wind but keeps the wind request (wind template,
        // wind attributes): it goes with PLWindCoverage No (row 43) and TigerCQApplicable N (rater.request.stateRules)
        boolean wind = windRequested(settings, inputs) || requestValues.containsKey(WIND_REQUEST_SHAPE);
        // With wind the body starts from the configuration's wind template (the API's wind sample request) and keeps
        // exactly its keys: a mapped attribute the wind template does not have is not added
        boolean windTemplate = wind && configuration != null && configuration.windTemplate() != null
            && !configuration.windTemplate().isBlank();
        JsonNode root = MAPPER.readTree(configuration == null ? template
            : templateOf(windTemplate ? configuration.windTemplate() : configuration.template(), configuration.id()));
        for (Map.Entry<Integer, String> e : inputs.entrySet()) {
            Map<String, String> targets = settings.requestTargets(id, e.getKey());
            if (targets.isEmpty()) {
                log.debug("Input row {} has no request mapping{}; not sent", e.getKey(), id == null ? "" : " for " + id);
                continue;
            }
            for (Map.Entry<String, String> t : targets.entrySet()) {
                if (windTemplate && !exists(root, t.getValue())) {
                    log.debug("Row {} -> {} is not in the wind template of {}; not sent", t.getKey(), t.getValue(), id);
                    continue;
                }
                set((ObjectNode) root, t.getValue(), apiValue(id, t.getKey(), e.getKey(), e.getValue(), wind));
            }
        }
        if (wind) addWindAttributes((ObjectNode) root, requestValues, windTemplate);
        addAddress((ObjectNode) root, requestValues, windTemplate);
        applyStateRules((ObjectNode) root, id, inputs);
        applyTivBand((ObjectNode) root, inputs);
        applyRenewal((ObjectNode) root, inputs);
        applyPolicyDates(root, inputs);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * Renewal requests (rater.request.renewal.row = rater.request.renewal.value, e.g. Transaction Type "Renewal") get the
     * values of rater.request.renewal.values ("path=value;path=value"): QuoteType "Renewal" and the renewal attributes
     * (BookOfBusinessAdjustment, RenewalAdjustmentType, Maximum / MinimumRenewalReserveFactor), added where the template
     * has none. New Business requests are not changed.
     */
    private void applyRenewal(ObjectNode root, Map<Integer, String> inputs) {
        String row = settings.get("rater.request.renewal.row");
        if (row.isEmpty()) return;
        String value = inputs.getOrDefault(Integer.parseInt(row), "").trim();
        if (!value.equalsIgnoreCase(settings.get("rater.request.renewal.value", "Renewal"))) return;
        for (String entry : settings.list("rater.request.renewal.values", ";")) {
            int eq = entry.indexOf('=');
            if (eq > 0) set(root, entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
        }
        // The renewal Rater inputs (rater.input.renewal.<row>) are sent also when the wind template has no such key
        String id = null;
        for (Map.Entry<Integer, String> e : inputs.entrySet()) {
            if (settings.get("rater.input.renewal." + e.getKey()).isEmpty()) continue;
            for (Map.Entry<String, String> t : settings.requestTargets(id, e.getKey()).entrySet()) {
                set(root, t.getValue(), e.getValue().trim());
            }
        }
    }

    /**
     * Policy dates of the case (Rater rows 3 Effective / 4 Expiration, set from rater.dates.effective): every
     * ApplicableDate / EffectiveDate / PolicyEffectiveDate and ExpirationDate / PolicyExpirationDate of the body.
     */
    private static void applyPolicyDates(JsonNode node, Map<Integer, String> inputs) {
        String effective = inputs.get(3);
        String expiration = inputs.get(4);
        if (effective == null || expiration == null || effective.isBlank()) return;
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            for (String k : List.of("ApplicableDate", "EffectiveDate", "PolicyEffectiveDate")) if (o.has(k)) o.put(k, effective);
            for (String k : List.of("ExpirationDate", "PolicyExpirationDate")) if (o.has(k)) o.put(k, expiration);
            o.elements().forEachRemaining(child -> applyPolicyDates(child, inputs));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> applyPolicyDates(child, inputs));
        }
    }

    /**
     * The case's fetched location (LocationFullAddress, City, Latitude, Longitude: "address.&lt;Name&gt;" paths) goes into every
     * request; a wind template keeps exactly its keys. An attribute the location does not have (e.g. City when no street
     * address was found) keeps the template's value; coordinates that were not found are sent empty.
     */
    private void addAddress(ObjectNode root, Map<String, String> requestValues, boolean onlyExistingKeys) {
        settings.addressPaths().forEach((name, path) -> {
            String value = requestValues.get(name);
            if (value == null) return;
            if (onlyExistingKeys && !exists(root, path)) return;
            set(root, path, value);
        });
    }

    /**
     * Subline + state combinations with fixed request values (rater.request.stateRules = rule names). A rule applies when
     * the case's state code (rater.request.stateCodeRow) is in rater.request.stateRule.&lt;rule&gt;.states.&lt;CONFIG&gt;
     * (else rater.request.stateRule.&lt;rule&gt;.states) and sets rater.request.stateRule.&lt;rule&gt;.values
     * ("path=value;path=value"), e.g. InsuranceScoreStatus "Not Applicable" / InsuranceScoreRange "-1" where the insurance
     * score does not apply, TigerCQApplicable "N" where TigerCQ does not apply. A path the body does not have is not added.
     */
    /** True when the state code is in rater.request.stateRule.&lt;rule&gt;.states.&lt;CONFIG&gt; (else .states). */
    static boolean stateRuleApplies(RaterSettings settings, String rule, String configurationId, String stateCode) {
        if (stateCode == null || stateCode.isBlank()) return false;
        String prefix = "rater.request.stateRule." + rule;
        String states = configurationId == null || configurationId.isEmpty() ? "" : settings.get(prefix + ".states." + configurationId);
        if (states.isEmpty()) states = settings.get(prefix + ".states");
        return java.util.Arrays.asList(states.toUpperCase(java.util.Locale.ROOT).split("\\s*\\|\\s*"))
            .contains(stateCode.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private void applyStateRules(ObjectNode root, String configurationId, Map<Integer, String> inputs) {
        String codeRow = settings.get("rater.request.stateCodeRow");
        if (codeRow.isEmpty()) return;
        String code = inputs.getOrDefault(Integer.parseInt(codeRow), "").trim().toUpperCase(java.util.Locale.ROOT);
        if (code.isEmpty()) return;
        for (String rule : settings.list("rater.request.stateRules", ",")) {
            if (!stateRuleApplies(settings, rule, configurationId, code)) continue;
            String prefix = "rater.request.stateRule." + rule;
            for (String entry : settings.list(prefix + ".values", ";")) {
                int eq = entry.indexOf('=');
                if (eq <= 0) continue;
                String path = entry.substring(0, eq).trim();
                if (exists(root, path)) set(root, path, entry.substring(eq + 1).trim());
            }
        }
    }

    /**
     * MinimumTiv / MaximumTiv are the limits of the TIV band (rater.request.tiv.bands, "min-max;min-max...") the case's TIV
     * falls in: TIV = sum of the coverage limits of rater.request.tiv.rows (Coverage A + B + C + D). A band includes its
     * minimum and excludes its maximum (5,000 is in 5,000 - 10,000); the last band also takes anything above it. Set only
     * where the body has the attribute.
     */
    private void applyTivBand(ObjectNode root, Map<Integer, String> inputs) {
        String minPath = settings.get("rater.request.tiv.minimumPath");
        String maxPath = settings.get("rater.request.tiv.maximumPath");
        if (minPath.isEmpty() || maxPath.isEmpty()) return;
        long[] band = tivBand(settings, inputs);
        if (band == null) return;
        if (exists(root, minPath)) set(root, minPath, String.valueOf(band[0]));
        if (exists(root, maxPath)) set(root, maxPath, String.valueOf(band[1]));
    }

    /** {minimum, maximum} of the TIV band of the inputs, or null when the TIV or the bands are not known. */
    static long[] tivBand(RaterSettings settings, Map<Integer, String> inputs) {
        List<Integer> rows = RaterSettings.parseRows(settings.get("rater.request.tiv.rows"));
        if (rows.isEmpty()) return null;
        java.math.BigDecimal tiv = java.math.BigDecimal.ZERO;
        for (int row : rows) {
            String v = inputs.getOrDefault(row, "").replace(",", "").replace("$", "").trim();
            if (v.isEmpty()) continue;
            try {
                tiv = tiv.add(new java.math.BigDecimal(v));
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        long[] last = null;
        for (String band : settings.list("rater.request.tiv.bands", ";")) {
            String[] mm = band.replace(",", "").split("\\s*-\\s*");
            if (mm.length != 2) continue;
            long min = Long.parseLong(mm[0].trim());
            long max = Long.parseLong(mm[1].trim());
            last = new long[]{min, max};
            if (tiv.compareTo(java.math.BigDecimal.valueOf(min)) >= 0 && tiv.compareTo(java.math.BigDecimal.valueOf(max)) < 0) {
                return last;
            }
        }
        return last != null && tiv.compareTo(java.math.BigDecimal.valueOf(last[0])) >= 0 ? last : null;
    }

    /** True when the body already has the attribute at the mapping path. */
    static boolean exists(JsonNode root, String path) {
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            Matcher m = SEGMENT.matcher(part);
            if (!m.matches()) return false;
            current = current.path(m.group(1));
            if (m.group(2) != null) current = current.path(Integer.parseInt(m.group(2)));
            if (current.isMissingNode()) return false;
        }
        return true;
    }

    /** True when the test case asks for wind coverage (rater.wind.row = Yes). */
    static boolean windRequested(RaterSettings settings, Map<Integer, String> inputs) {
        int row = settings.windRow();
        return row > 0 && "Yes".equalsIgnoreCase(inputs.getOrDefault(row, "").trim());
    }

    /**
     * Wind coverage needs the building's wind attributes (roof, openings ...). Each configured wind.&lt;Name&gt; takes the
     * test data's "Request:&lt;Name&gt;" value (a blank cell is sent as "", as the API's samples do), else its wind.default
     * value; an attribute with neither is not sent.
     */
    private void addWindAttributes(ObjectNode root, Map<String, String> requestValues, boolean onlyExistingKeys) {
        Map<String, String> defaults = settings.windDefaults();
        settings.windPaths().forEach((name, path) -> {
            if (onlyExistingKeys && !exists(root, path)) return;
            if (requestValues.containsKey(name)) {
                set(root, path, requestValues.get(name) == null ? "" : requestValues.get(name));
            } else if (defaults.containsKey(name)) {
                set(root, path, defaults.get(name));
            }
        });
    }

    /**
     * Rater wording -> API value: an explicit value mapping entry wins; otherwise a numeric range on a range row becomes
     * one number inside it (see {@link RangeValue}); otherwise the value is sent unchanged.
     */
    String apiValue(String configuration, String target, int row, String raterValue) {
        return apiValue(configuration, target, row, raterValue, false);
    }

    /** As above; with wind a "&lt;target&gt;.wind" value mapping entry wins (e.g. the wind deductible as a fraction). */
    String apiValue(String configuration, String target, int row, String raterValue, boolean wind) {
        if (wind) {
            Optional<String> windValue = values.lookup(configuration, target + ".wind", raterValue);
            if (windValue.isPresent()) return windValue.get();
        }
        Optional<String> mapped = values.lookup(configuration, target, raterValue);
        if (mapped.isPresent()) return mapped.get();
        if (target.equals(String.valueOf(row)) && RangeValue.isRangeRow(settings, row)) {
            Optional<String> single = RangeValue.resolve(raterValue, RangeValue.pick(settings, row));
            if (single.isPresent()) return single.get();
        }
        return raterValue;
    }

    /** Input rows that are written to the Rater but cannot be sent because no JSON path is configured. */
    public java.util.List<Integer> unmappedRows(java.util.Collection<Integer> rows) {
        return rows.stream().filter(r -> settings.requestPath(r).isEmpty()).toList();
    }

    /**
     * A template's text (file on disk, else classpath), read once. Comments ("// ..." lines and "/* ... *&#47;" blocks, as in
     * the API's sample requests) and trailing commas are removed, so a sample request can be used as it is.
     */
    private synchronized String templateOf(String name, String configurationId) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IOException("No request template configured for " + configurationId
                + " (configuration." + configurationId + ".template)");
        }
        String cached = templates.get(name);
        if (cached != null) return cached;
        String text;
        Path file = Paths.get(name);
        if (Files.isRegularFile(file)) {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } else {
            try (InputStream in = RaterRequestMapper.class.getClassLoader().getResourceAsStream(name)) {
                if (in == null) throw new IOException("Request template not found (file or classpath): " + name);
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        text = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "").replaceAll(",(\\s*[}\\]])", "$1");
        templates.put(name, text);
        return text;
    }

    private static void set(ObjectNode root, String path, String value) {
        ObjectNode current = root;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            Matcher m = SEGMENT.matcher(parts[i]);
            if (!m.matches()) throw new IllegalArgumentException("Bad request mapping path: " + path);
            String name = m.group(1);
            boolean last = i == parts.length - 1;

            if (m.group(2) == null) {
                if (last) {
                    current.put(name, value);
                } else {
                    JsonNode child = current.get(name);
                    current = child instanceof ObjectNode o ? o : current.putObject(name);
                }
            } else {
                int index = Integer.parseInt(m.group(2));
                ArrayNode array = current.get(name) instanceof ArrayNode a ? a : current.putArray(name);
                while (array.size() <= index) array.addObject();
                if (last) {
                    array.set(index, MAPPER.getNodeFactory().textNode(value));
                } else {
                    current = (ObjectNode) array.get(index);
                }
            }
        }
    }
}
