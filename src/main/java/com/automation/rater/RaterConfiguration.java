package com.automation.rater;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One supported rating configuration: a Subline (Rater row 9), optionally combined with an Occupancy Type (Rater row 26).
 * Defined in rater.properties (rater.configurations + configuration.&lt;ID&gt;.*), for example
 * <pre>
 *   configuration.DP3_VACANT.subline=DP3
 *   configuration.DP3_VACANT.occupancies=Vacant
 *   configuration.DP3_VACANT.template=config/templates/DP3_VACANT.json
 *   configuration.DP3.windTemplate=config/templates/wind/DP_WIND.json
 * </pre>
 * A test case belongs to the first configuration whose subline matches and whose occupancy list contains its occupancy.
 *
 * @param valuations allowed "Dwelling/Contents" valuation pairs (rows 12/13); empty = any (the API then gets the mapped value)
 * @param windTemplate request template used instead of template when the case includes wind (blank = none)
 */
public record RaterConfiguration(String id, String subline, List<String> occupancies, String template, List<String> valuations,
                                 String windTemplate) {

    /**
     * The configuration of a test case; problem is set when the case cannot be run (unsupported Subline/Occupancy
     * combination - configuration is then null - or invalid test data for its configuration).
     */
    public record Resolution(RaterConfiguration configuration, String problem) {
        public boolean supported() {
            return configuration != null && problem == null;
        }
    }

    public String label() {
        return id + " (" + subline + (occupancies.size() == 1 ? " + " + occupancies.get(0) : "") + ")";
    }

    /** All configurations listed in rater.configurations, in that order. */
    public static List<RaterConfiguration> all(RaterSettings settings) {
        List<RaterConfiguration> result = new ArrayList<>();
        for (String id : settings.list("rater.configurations", ",")) {
            String prefix = "configuration." + id + ".";
            result.add(new RaterConfiguration(id, settings.get(prefix + "subline"), settings.list(prefix + "occupancies", "|"),
                settings.get(prefix + "template"), settings.list(prefix + "valuations", "|"), settings.get(prefix + "windTemplate")));
        }
        return result;
    }

    /** Finds the configuration selected by a test case's Subline and Occupancy Type inputs. */
    public static Resolution resolve(RaterSettings settings, List<RaterConfiguration> configurations, Map<Integer, String> inputs) {
        int sublineRow = Integer.parseInt(settings.get("rater.configuration.sublineRow", "9"));
        int occupancyRow = Integer.parseInt(settings.get("rater.configuration.occupancyRow", "26"));
        String subline = inputs.getOrDefault(sublineRow, "").trim();
        String occupancy = inputs.getOrDefault(occupancyRow, "").trim();
        if (subline.isEmpty()) {
            return new Resolution(null, "No Subline (row " + sublineRow + ") in the test data");
        }
        Optional<RaterConfiguration> match = configurations.stream()
            .filter(c -> c.subline().equalsIgnoreCase(subline))
            .filter(c -> c.occupancies().stream().anyMatch(o -> o.equalsIgnoreCase(occupancy)))
            .findFirst();
        if (match.isEmpty()) {
            return new Resolution(null, "Unsupported configuration: Subline " + subline + " with Occupancy Type '" + occupancy
                + "' (supported: " + configurations.stream().map(RaterConfiguration::label).toList() + ")");
        }
        RaterConfiguration c = match.get();
        List<Integer> valuationRows = RaterSettings.parseRows(settings.get("rater.configuration.valuationRows", "12,13"));
        String valuation = valuationRows.stream().map(r -> inputs.getOrDefault(r, "").trim())
            .reduce((a, b) -> a + "/" + b).orElse("");
        if (!c.valuations().isEmpty() && c.valuations().stream().noneMatch(v -> v.equalsIgnoreCase(valuation))) {
            return new Resolution(null, "Invalid test data for " + c.id() + ": Dwelling/Contents valuation " + valuation
                + " is not rated for this subline (allowed: " + c.valuations() + ")");
        }
        return new Resolution(c, null);
    }
}
