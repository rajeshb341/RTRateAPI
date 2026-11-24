package com.automation.rater;

import com.automation.rater.RaterResponseMapper.Actual;
import com.automation.rater.RaterResponseMapper.Status;
import com.automation.rater.ValueComparator.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Offline checks of the Rater validation logic (no spreadsheet application and no API needed). */
public class RaterLogicTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RaterSettings settings(Map<Integer, String> request, Map<Integer, String> response) {
        Properties p = new Properties();
        p.setProperty("rater.compare.tolerance", "0.01");
        p.setProperty("rater.compare.tolerance.9", "0.000001");
        p.setProperty("rater.input.5.values", "a| b |c");
        return RaterSettings.of(p, request, response);
    }

    // ---- ValueComparator ------------------------------------------------------------------------

    @DataProvider(name = "comparisons")
    public Object[][] comparisons() {
        return new Object[][]{
            // expected, actual, tolerance, pass?
            {"3629.36", "3629.36", 0.01, true},
            {"3629.36", "3629.3600", 0.01, true},          // trailing zeros
            {"3629.36", "3629.364", 0.01, true},           // inside tolerance
            {"3629.36", "3629.38", 0.01, false},           // outside tolerance
            {"1233", "1233.0", 0.01, true},                // integer vs decimal
            {"1,233.50", "1233.5", 0.01, true},            // thousands separator
            {"$250", "250", 0.01, true},                   // currency symbol
            {"-0.1", "-0.10", 0.01, true},
            {"N/A", "N/A", 0.01, true},                    // text outputs
            {"additive", "additive", 0.01, true},
            {"N/A", "n/a", 0.01, false},                   // text is exact
            {"FL4", "FL5", 0.01, false},
            {"", "", 0.01, true},                          // both blank
            {"", "5", 0.01, false},                        // empty expected
            {"5", "", 0.01, false},                        // empty actual
            {"0.286999", "0.287", 0.000001, true},         // difference equals the tolerance: inclusive
            {"0.286999", "0.28701", 0.000001, false},      // tight tolerance, outside
            {"0.286999", "0.286999", 0.000001, true},
            {"N/A", "0", 0.01, false},                     // text vs number
        };
    }

    @Test(dataProvider = "comparisons")
    public void comparatorRules(String expected, String actual, double tolerance, boolean pass) {
        Outcome outcome = ValueComparator.compare(expected, actual, tolerance);
        assertEquals(outcome.pass(), pass, "expected=" + expected + " actual=" + actual + " -> " + outcome);
        if (!pass) assertFalse(outcome.remark().isEmpty(), "a failure must explain itself");
    }

    // ---- RaterSettings --------------------------------------------------------------------------

    @Test
    public void rowSpecsExpand() {
        assertEquals(RaterSettings.parseRows("11-13,15,17-18"), List.of(11, 12, 13, 15, 17, 18));
    }

    @Test
    public void settingsToleranceOverrideAndValueLists() {
        RaterSettings s = settings(Map.of(), Map.of());
        assertEquals(s.tolerance(100), 0.01);
        assertEquals(s.tolerance(9), 0.000001);
        assertEquals(s.overrideValues(5), List.of("a", "b", "c"));
        assertTrue(s.overrideValues(6).isEmpty());
    }

    @Test
    public void realConfigFilesLoadAndCoverTheGivenRows() throws IOException {
        RaterSettings s = RaterSettings.load();
        // 9 + 11-23 (13) + 25-38 (14) + 40-41 (2) + 43-45 (3) + 47-48 (2) + 50-60 (11) + 62-75 (14) + 77-79 (3) + 81-82 (2)
        // + renewal 89-93 (5) + 95 (1)
        assertEquals(s.inputRows().size(), 71, "input rows are expanded from the given cell ranges");
        assertEquals(s.outputRows().get(0), 104);
        assertEquals(s.requestPath(15), "Account.Insured[0].Location[0].State");
        assertEquals(s.requestPath(60), "", "row 60 is only mapped for HO6");
        assertEquals(s.requestTargets("HO6", 60).get("60"),
            "Account.Insured[0].Location[0].Building[0].Coverage[0].EarthquakeCoverage.CoverageSelected");
        assertEquals(s.responsePath(104), "$.SublineName");
    }

    // ---- RaterRequestMapper ---------------------------------------------------------------------

    @Test
    public void requestMapperWritesInputsIntoTheTemplate() throws IOException {
        String template = "{\"EventName\":\"E\",\"Account\":{\"Insured\":[{\"Location\":[{\"State\":\"Texas\","
            + "\"Building\":[{\"Coverage\":[{\"DwellingCoverage\":{\"Limit\":\"1\"}}]}]}]}]}}";
        RaterSettings s = settings(Map.of(
            1, "Account.Insured[0].Location[0].State",
            2, "Account.Insured[0].Location[0].Building[0].Coverage[0].DwellingCoverage.Limit",
            3, "Account.Insured[0].Location[0].Building[0].Coverage[0].NewThing.Limit",     // created
            4, ""), Map.of());

        Map<Integer, String> inputs = new LinkedHashMap<>();
        inputs.put(1, "Florida");
        inputs.put(2, "274000");
        inputs.put(3, "9");
        inputs.put(4, "not sent");           // unmapped row
        inputs.put(5, "also not sent");      // row without any mapping entry

        JsonNode body = MAPPER.readTree(new RaterRequestMapper(s, template).build(inputs));
        JsonNode loc = body.at("/Account/Insured/0/Location/0");
        assertEquals(loc.path("State").asText(), "Florida");
        assertEquals(loc.at("/Building/0/Coverage/0/DwellingCoverage/Limit").asText(), "274000");
        assertEquals(loc.at("/Building/0/Coverage/0/NewThing/Limit").asText(), "9");
        assertEquals(body.path("EventName").asText(), "E", "untouched template content is preserved");
        assertFalse(body.toString().contains("not sent"));
        assertEquals(new RaterRequestMapper(s, template).unmappedRows(List.of(1, 4, 5)), List.of(4, 5));
    }

    @Test
    public void windAttributesAreSentOnlyWhenWindIsRequested() throws IOException {
        String template = "{\"Account\":{\"Insured\":[{\"Location\":[{\"Building\":[{\"Coverage\":[{\"PLWindCoverage\":"
            + "{\"CoverageSelected\":\"No\"}}]}]}]}]}}";
        Properties props = new Properties();
        props.setProperty("rater.wind.row", "43");
        String building = "Account.Insured[0].Location[0].Building[0].";
        Map<String, String> wind = new LinkedHashMap<>();
        wind.put("wind.RoofType", building + "RoofType");
        wind.put("wind.Fasteners", building + "Fasteners");
        wind.put("wind.TigerCQApplicable", building + "Coverage[0].TigerCQApplicable");
        wind.put("wind.default.TigerCQApplicable", "Y");
        wind.put("wind.Latitude", "Account.Insured[0].Location[0].Latitude");      // no column, no default -> not sent
        RaterSettings s = RaterSettings.withWind(props,
            Map.of("row.43", building + "Coverage[0].PLWindCoverage.CoverageSelected"), wind);
        Map<String, String> requestValues = new LinkedHashMap<>();
        requestValues.put("RoofType", "WoodShake");
        requestValues.put("Fasteners", "");

        JsonNode yes = MAPPER.readTree(new RaterRequestMapper(s, template).build(null, Map.of(43, "Yes"), requestValues));
        JsonNode b = yes.at("/Account/Insured/0/Location/0/Building/0");
        assertEquals(b.at("/Coverage/0/PLWindCoverage/CoverageSelected").asText(), "Yes");
        assertEquals(b.path("RoofType").asText(), "WoodShake");
        assertTrue(b.has("Fasteners"), "a blank test data cell is sent as an empty string");
        assertEquals(b.path("Fasteners").asText(), "");
        assertEquals(b.at("/Coverage/0/TigerCQApplicable").asText(), "Y", "default when the test data has no column");
        assertFalse(yes.at("/Account/Insured/0/Location/0").has("Latitude"));

        JsonNode no = MAPPER.readTree(new RaterRequestMapper(s, template).build(null, Map.of(43, "No"), requestValues));
        assertFalse(no.at("/Account/Insured/0/Location/0/Building/0").has("RoofType"), "no wind -> request unchanged");
    }

    @Test(dataProvider = "windConfigurations")
    public void windRequestHasExactlyTheKeysOfTheWindSample(String id, String sample) throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterConfiguration c = RaterConfiguration.all(s).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        Map<Integer, String> in = inputs(c.subline(), c.occupancies().get(0), "ACV", "ACV");
        in.put(43, "Yes");
        in.put(44, "262.39");
        in.put(52, "No");                      // Identity Fraud: not in the wind samples -> not added
        in.put(77, "Excluded");
        in.put(45, "Greater of $2,500 or 1%");
        Map<String, String> requestValues = Map.of("RoofType", "WoodShake", "Fasteners", "");

        JsonNode body = MAPPER.readTree(new RaterRequestMapper(s, "{}").build(c, in, requestValues));
        String text = Files.readString(Path.of("src/main/resources/config/templates/wind/" + sample))
            .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "").replaceAll(",(\\s*[}\\]])", "$1");
        assertEquals(keys(body, ""), keys(MAPPER.readTree(text), ""), id + ": same keys as " + sample);
        JsonNode b = body.at("/Account/Insured/0/Location/0/Building/0");
        assertEquals(b.at("/Coverage/0/PLWindCoverage/CoverageSelected").asText(), "Yes");
        assertEquals(b.path("RoofType").asText(), "WoodShake");
        assertEquals(b.at("/Coverage/0/WindDeductiblePercentage").asText(), "0.01", "wind model takes the percentage as a fraction");
        assertEquals(body.path("SublineName").asText(), c.subline());
        in.put(43, "No");
        assertEquals(MAPPER.readTree(new RaterRequestMapper(s, "{}").build(c, in, requestValues))
            .at("/Account/Insured/0/Location/0/Building/0/Coverage/0/WindDeductiblePercentage").asText(),
            "0.01", "without wind the fraction is sent too (the API answers HTTP 500 for the wording)");
    }

    @DataProvider
    public Object[][] windConfigurations() {
        return new Object[][]{{"HO3", "HO_WIND.json"}, {"HO5", "HO_WIND.json"}, {"HO8", "HO_WIND.json"}, {"DP1", "DP_WIND.json"},
            {"DP3", "DP_WIND.json"}, {"DP1_BUILDERS_RISK", "DP_BUILDERS_RISK_WIND.json"},
            {"DP3_BUILDERS_RISK", "DP_BUILDERS_RISK_WIND.json"}};
    }

    private static java.util.Set<String> keys(JsonNode node, String path) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> out.addAll(keys(e.getValue(), path + "/" + e.getKey())));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) out.addAll(keys(node.get(i), path + "/" + i));
        } else {
            out.add(path);
        }
        return out;
    }

    @Test
    public void windCasesAreGeocodedFromTheirOwnLocation() throws IOException {
        List<String> queries = new java.util.ArrayList<>();
        Path cache = Files.createTempFile("geocode", ".json");
        Files.delete(cache);
        try {
            RaterGeocoder geocoder = new RaterGeocoder(q -> {
                queries.add(q);
                return java.util.Optional.of(new String[]{"37.043478", "-76.342101"});
            }, cache);
            RaterGeocoder.Location zip = geocoder.locate("", "HAMPTON CITY", "Virginia", "VA", "23669");
            assertEquals(zip.address(), "HAMPTON CITY, VA 23669, United States");
            assertEquals(zip.latitude(), "37.043478");
            assertEquals(geocoder.locate(null, "BARNSTABLE", "Massachusetts", "MA", "2540").address(),
                "BARNSTABLE, MA 02540, United States", "zip padded to 5 digits");
            RaterGeocoder.Location given = geocoder.locate("2198 Harris Avenue, PALM BAY, FL 32905", "BREVARD", "Florida", "FL", "32905");
            assertEquals(given.address(), "2198 Harris Avenue, PALM BAY, FL 32905");
            geocoder.locate("", "HAMPTON CITY", "Virginia", "VA", "23669");          // cached
            assertEquals(queries, List.of("23669, Virginia, USA", "02540, Massachusetts, USA", "2198 Harris Avenue, PALM BAY, FL 32905"));
            // a new geocoder reads the cache file instead of looking up again
            new RaterGeocoder(q -> { throw new AssertionError("not cached: " + q); }, cache)
                .locate("", "HAMPTON CITY", "Virginia", "VA", "23669");
        } finally {
            Files.deleteIfExists(cache);
        }
        assertEquals(RaterGeocoder.round("28.0342940"), "28.034294");
    }

    @Test
    public void everyCaseGetsALocationAndTestDataCoordinatesWin() {
        Properties props = new Properties();
        props.setProperty("rater.wind.row", "43");
        RaterSettings s = RaterSettings.withWind(props, Map.of(), Map.of());
        RaterGeocoder geocoder = new RaterGeocoder(q -> java.util.Optional.of(new String[]{"1", "2"}), null);
        Map<Integer, String> in = new LinkedHashMap<>(Map.of(7, "VA", 15, "Virginia", 16, "HAMPTON CITY", 17, "23669", 43, "Yes"));
        RaterTestCase wind = new RaterTestCase("1", in, new LinkedHashMap<>());
        assertEquals(RaterValidationRunner.withLocation(s, geocoder, wind, in).get("Latitude"), "1");
        RaterTestCase given = new RaterTestCase("2", in, new LinkedHashMap<>(Map.of("Latitude", "9", "Longitude", "8")));
        assertEquals(RaterValidationRunner.withLocation(s, geocoder, given, in).get("Latitude"), "9");
        Map<Integer, String> noWind = new LinkedHashMap<>(in);
        noWind.put(43, "No");
        assertEquals(RaterValidationRunner.withLocation(s, geocoder, new RaterTestCase("3", noWind, new LinkedHashMap<>()), noWind)
            .get("Latitude"), "1", "cases without wind get a location too");
    }

    @Test
    public void aStreetAddressInTheSameZipStateAndCountyIsFetched() throws IOException {
        Map<String, String> noHouse = Map.of("road", "Beach Side Drive", "city", "Ocean City", "county", "Worcester County",
            "state", "Maryland", "postcode", "21842", "lat", "38.39", "lon", "-75.07");
        Map<String, String> otherZip = Map.of("road", "Main Street", "house_number", "1", "town", "Berlin", "county", "Worcester County",
            "state", "Maryland", "postcode", "21811", "lat", "38.3", "lon", "-75.2");
        Map<String, String> good = Map.of("road", "Coastal Highway", "house_number", "9800", "city", "Ocean City",
            "county", "Worcester County", "state", "Maryland", "postcode", "21842-1234", "lat", "38.4012345", "lon", "-75.0612345");
        List<Map<String, String>> answers = new java.util.ArrayList<>(List.of(noHouse, otherZip, good));
        List<double[]> points = new java.util.ArrayList<>();
        Path cache = Files.createTempFile("address", ".json");
        Files.delete(cache);
        try {
            RaterGeocoder geocoder = new RaterGeocoder(q -> java.util.Optional.of(new String[]{"38.39", "-75.07"}), point -> {
                points.add(point);
                return answers.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(answers.remove(0));
            }, cache);
            RaterGeocoder.Location location = geocoder.locate("", "WORCESTER", "Maryland", "MD", "21842");
            // The street-only and the other-ZIP addresses are skipped: the first with a house number in 21842 wins
            assertEquals(location.address(), "9800 Coastal Highway, OCEAN CITY, MD 21842, United States");
            assertEquals(location.city(), "Ocean City");
            assertEquals(location.latitude(), "38.401235");
            assertEquals(points.size(), 3);
            // Cached: a new geocoder reads it from the file without any lookup
            RaterGeocoder cached = new RaterGeocoder(q -> { throw new AssertionError("not cached: " + q); },
                point -> { throw new AssertionError("not cached"); }, cache);
            assertEquals(cached.locate("", "WORCESTER", "Maryland", "MD", "21842").address(), location.address());
        } finally {
            Files.deleteIfExists(cache);
        }
        // No address found at all: the ZIP centre and the county are used, City is left to the template
        RaterGeocoder none = new RaterGeocoder(q -> java.util.Optional.of(new String[]{"1", "2"}), point -> java.util.Optional.empty(), null);
        RaterGeocoder.Location fallback = none.locate("", "WORCESTER", "Maryland", "MD", "21842");
        assertEquals(fallback.address(), "WORCESTER, MD 21842, United States");
        assertEquals(fallback.city(), "");
    }

    @Test
    public void theCountySentComesFromTheRaterStateCountyTable() {
        List<String> va = List.of("ACCOMACK", "RICHMOND", "RICHMOND (CITY)", "VIRGINIA BEACH (CITY)", "JAMES CITY");
        assertEquals(RaterGeocoder.tableCounty("VIRGINIA BEACH CITY", va), "VIRGINIA BEACH (CITY)");
        assertEquals(RaterGeocoder.tableCounty("RICHMOND CITY", va), "RICHMOND (CITY)");
        assertEquals(RaterGeocoder.tableCounty("RICHMOND", va), "RICHMOND", "the county stays apart from the city");
        assertEquals(RaterGeocoder.tableCounty("James City County", va), "JAMES CITY");
        assertEquals(RaterGeocoder.tableCounty("SAINT JOHNS", List.of("ST. JOHNS", "ST. LUCIE")), "ST. JOHNS");
        assertEquals(RaterGeocoder.tableCounty("PRINCE GEORGES", List.of("PRINCE GEORGE'S")), "PRINCE GEORGE'S");
        assertEquals(RaterGeocoder.tableCounty("Miami-Dade County", List.of("MIAMI-DADE")), "MIAMI-DADE");
        assertEquals(RaterGeocoder.tableCounty("SOUTH CENTRAL CONNECTICUT PLANNING REGION", List.of("NEW HAVEN")), null);

        Map<String, String> newHaven = Map.of("road", "Ashmun Street", "house_number", "233", "city", "New Haven",
            "county", "New Haven", "state", "Connecticut", "postcode", "06511", "lat", "41.3189", "lon", "-72.9297");
        Map<String, String> dc = Map.of("road", "K Street NW", "house_number", "1", "city", "Washington",
            "county", "District of Columbia", "state", "District of Columbia", "postcode", "20001", "lat", "38.9", "lon", "-77.0");
        RaterGeocoder geocoder = new RaterGeocoder(q -> java.util.Optional.of(new String[]{"1", "2"}),
            point -> java.util.Optional.of(point[0] == 1 ? newHaven : newHaven), null)
            .withStateCounties(Map.of("Connecticut", List.of("FAIRFIELD", "NEW HAVEN"), "Virginia", va,
                "Washington_DC", List.of("WASHINGTON D.C.")));
        // Not in the table: the county of the address found in the ZIP
        RaterGeocoder.Location ct = geocoder.locate("", "SOUTH CENTRAL CONNECTICUT PLANNING REGION", "Connecticut", "CT", "6511");
        assertEquals(ct.county(), "NEW HAVEN");
        assertEquals(ct.address(), "233 Ashmun Street, NEW HAVEN, CT 06511, United States");
        // In the table in another spelling: the table's spelling; the address must lie in that county
        RaterGeocoder vaGeocoder = new RaterGeocoder(q -> java.util.Optional.of(new String[]{"1", "2"}), point -> java.util.Optional.of(
            Map.of("road", "Cardinal Road", "house_number", "924", "city", "Virginia Beach", "state", "Virginia",
                "postcode", "23451", "lat", "36.86", "lon", "-76.0")), null).withStateCounties(Map.of("Virginia", va));
        assertEquals(vaGeocoder.locate("", "VIRGINIA BEACH CITY", "Virginia", "VA", "23451").county(), "VIRGINIA BEACH (CITY)");
        // A state with one county in the table: that county
        RaterGeocoder dcGeocoder = new RaterGeocoder(q -> java.util.Optional.of(new String[]{"1", "2"}),
            point -> java.util.Optional.of(dc), null).withStateCounties(Map.of("Washington_DC", List.of("WASHINGTON D.C.")));
        RaterGeocoder.Location d = dcGeocoder.locate("", "DISTRICT OF COLUMBIA", "Washington_DC", "DC", "20001");
        assertEquals(d.county(), "WASHINGTON D.C.");
        assertEquals(d.street(), "1 K Street NW");
        // No table loaded: no county change
        assertEquals(new RaterGeocoder(q -> java.util.Optional.of(new String[]{"1", "2"}), null)
            .locate("", "VIRGINIA BEACH CITY", "Virginia", "VA", "23451").county(), "");
    }

    @Test
    public void theCountyIsSpelledAsInTheCountySheetForTheSubline() {
        Map<String, List<String>> table = Map.of(
            RaterGeocoder.tableKey("Virginia", "HO6"), List.of("VIRGINIA BEACH CITY", "NORFOLK CITY"),
            RaterGeocoder.tableKey("Virginia", "DP1 Builders Risk"), List.of("VIRGINIA BEACH CITY", "Virginia Beach (City)", "Norfolk (City)"),
            RaterGeocoder.tableKey("Connecticut", "DP1 Builders Risk"), List.of("Fairfield", "New Haven"));
        assertEquals(RaterGeocoder.tableKey("Virginia", "DP1 Builders Risk"), RaterGeocoder.tableKey("virginia", "DP1_BUILDERS_RISK"));
        RaterGeocoder geocoder = new RaterGeocoder(q -> java.util.Optional.of(new String[]{"1", "2"}), point -> java.util.Optional.of(
            Map.of("road", "Ashmun Street", "house_number", "233", "city", "New Haven", "county", "New Haven",
                "state", "Connecticut", "postcode", "06511", "lat", "41.3", "lon", "-72.9")), null).withCountyTable(table);
        // HO6 has one spelling; other sublines keep the test data spelling when the table lists it, else the table's
        assertEquals(geocoder.knownCounty("VIRGINIA BEACH CITY", "Virginia", "HO6"), "VIRGINIA BEACH CITY");
        assertEquals(geocoder.knownCounty("VIRGINIA BEACH CITY", "Virginia", "DP1_BUILDERS_RISK"), "VIRGINIA BEACH CITY");
        assertEquals(geocoder.knownCounty("NORFOLK CITY", "Virginia", "DP1_BUILDERS_RISK"), "Norfolk (City)");
        assertEquals(geocoder.knownCounty("SOUTH CENTRAL CONNECTICUT PLANNING REGION", "Connecticut", "DP1_BUILDERS_RISK"), null);

        // A county that is not in the table is replaced for the Rater (and so the request) by the ZIP's table county
        RaterTestCase ct = new RaterTestCase("6540", new LinkedHashMap<>(Map.of(15, "Connecticut",
            16, "SOUTH CENTRAL CONNECTICUT PLANNING REGION", 17, "6511")), new LinkedHashMap<>());
        RaterTestCase va = new RaterTestCase("6391", new LinkedHashMap<>(Map.of(15, "Virginia", 16, "NORFOLK CITY", 17, "23503")),
            new LinkedHashMap<>());
        RaterConfiguration br = new RaterConfiguration("DP1_BUILDERS_RISK", "DP1", List.of("Builders Risk"), "t.json", List.of(), null);
        Map<String, RaterConfiguration.Resolution> resolved = Map.of(
            "6540", new RaterConfiguration.Resolution(br, null), "6391", new RaterConfiguration.Resolution(br, null));
        List<RaterTestCase> out = RaterValidationRunner.withTableCounties(geocoder, resolved, List.of(ct, va));
        assertEquals(out.get(0).inputs().get(16), "New Haven");
        assertEquals(out.get(1).inputs().get(16), "NORFOLK CITY", "a county in the table keeps the test data spelling for the Rater");
    }

    @Test
    public void windCasesWhereTigerCqDoesNotApplyAreRatedWithoutWind() throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterConfiguration dp3 = RaterConfiguration.all(s).stream().filter(c -> c.id().equals("DP3")).findFirst().orElseThrow();
        Map<String, RaterConfiguration.Resolution> resolved = Map.of(
            "va", new RaterConfiguration.Resolution(dp3, null), "fl", new RaterConfiguration.Resolution(dp3, null),
            "noWind", new RaterConfiguration.Resolution(dp3, null));
        RaterTestCase va = new RaterTestCase("va", new LinkedHashMap<>(Map.of(15, "Virginia", 43, "Yes")), new LinkedHashMap<>());
        RaterTestCase fl = new RaterTestCase("fl", new LinkedHashMap<>(Map.of(15, "Florida", 43, "Yes")), new LinkedHashMap<>());
        RaterTestCase noWind = new RaterTestCase("noWind", new LinkedHashMap<>(Map.of(15, "Virginia", 43, "No")), new LinkedHashMap<>());
        List<RaterTestCase> out = RaterValidationRunner.withoutTigerCqWind(s, resolved, List.of(va, fl, noWind));
        assertEquals(out.get(0).inputs().get(43), "No", "VA: TigerCQ does not apply - the Rater rates it without wind");
        assertTrue(out.get(0).requestValues().containsKey(RaterRequestMapper.WIND_REQUEST_SHAPE));
        assertEquals(out.get(1).inputs().get(43), "Yes", "FL: TigerCQ applies - wind stays");
        assertEquals(out.get(2), noWind);

        // The request keeps the wind request's shape: PLWindCoverage No, TigerCQApplicable N
        Map<Integer, String> in = new LinkedHashMap<>(out.get(0).inputs());
        in.put(7, "VA");
        JsonNode cov = MAPPER.readTree(new RaterRequestMapper(s, "{}").build(dp3, in, out.get(0).requestValues()))
            .at("/Account/Insured/0/Location/0/Building/0/Coverage/0");
        assertEquals(cov.at("/PLWindCoverage/CoverageSelected").asText(), "No");
        assertEquals(cov.path("TigerCQApplicable").asText(), "N");
    }

    @Test
    public void renewalRequestsCarryTheRenewalAttributes() throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterConfiguration ho3 = RaterConfiguration.all(s).stream().filter(c -> c.id().equals("HO3")).findFirst().orElseThrow();
        RaterRequestMapper mapper = new RaterRequestMapper(s, "{}");
        for (String wind : List.of("No", "Yes")) {
            RaterTestCase tc = new RaterTestCase("1", new LinkedHashMap<>(Map.of(7, "FL", 14, "Renewal", 43, wind)), new LinkedHashMap<>());
            Map<Integer, String> in = RaterValidationRunner.withValuesFromRows(s, List.of(tc)).get(0).inputs();
            JsonNode renewal = MAPPER.readTree(mapper.build(ho3, new LinkedHashMap<>(in), Map.of()));
            assertEquals(renewal.path("TransactionType").asText(), "Renewal", "wind " + wind);
            assertEquals(renewal.path("QuoteType").asText(), "Renewal");
            assertEquals(renewal.path("BookOfBusinessAdjustment").asText(), "No");
            assertEquals(renewal.path("RenewalAdjustmentType").asText(), "No Adjustment");
            assertEquals(renewal.path("MaximumRenewalReserveFactor").asText(), "0.7");
            assertEquals(renewal.path("MinimumRenewalReserveFactor").asText(), "0.5");

            JsonNode newBusiness = MAPPER.readTree(mapper.build(ho3, new LinkedHashMap<>(Map.of(7, "FL", 14, "New_Business", 43, wind)), Map.of()));
            assertEquals(newBusiness.path("TransactionType").asText(), "New_Business");
            assertEquals(newBusiness.path("QuoteType").asText(), "New Business", "New Business keeps the template's QuoteType");
            assertTrue(newBusiness.path("BookOfBusinessAdjustment").isMissingNode());
            assertTrue(newBusiness.path("RenewalAdjustmentType").isMissingNode());
            assertTrue(newBusiness.path("MaximumRenewalReserveFactor").isMissingNode());
        }
    }

    @Test
    public void testDataAddressesAreUsedWithoutAnyLookup() {
        Properties props = new Properties();
        RaterSettings s = RaterSettings.withWind(props, Map.of(), Map.of());
        RaterGeocoder geocoder = new RaterGeocoder(q -> { throw new AssertionError("no lookup: " + q); },
            point -> { throw new AssertionError("no reverse lookup"); }, null)
            .withCountyTable(Map.of(RaterGeocoder.tableKey("Virginia", "HO3"), List.of("NEWPORT NEWS CITY", "Newport News (City)")));
        Map<Integer, String> in = new LinkedHashMap<>(Map.of(15, "Virginia", 16, "NEWPORT NEWS (CITY)", 17, "23607"));
        RaterTestCase tc = new RaterTestCase("1", in, new LinkedHashMap<>(Map.of(
            "LocationFullAddress", "3011 WICKHAM AVE, NEWPORT NEWS, VA, 23607, United States", "City", "NEWPORT NEWS",
            "Latitude", "36.988157", "Longitude", "-76.415456")));
        Map<String, String> out = RaterValidationRunner.withLocation(s, geocoder, tc, in, "HO3");
        assertEquals(out.get("Latitude"), "36.988157");
        assertEquals(out.get("LocationFullAddress"), "3011 WICKHAM AVE, NEWPORT NEWS, VA, 23607, United States");
        assertEquals(out.get("County"), "Newport News (City)", "the county table spelling for the subline");
    }

    @Test
    public void equalToAopIsTheAopDeductibleAmount() throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterTestCase equal = new RaterTestCase("1", new LinkedHashMap<>(Map.of(37, "5000", 38, "Equal to AOP")), new LinkedHashMap<>());
        RaterTestCase amount = new RaterTestCase("2", new LinkedHashMap<>(Map.of(37, "2500", 38, "10000")), new LinkedHashMap<>());
        List<RaterTestCase> out = RaterValidationRunner.withValuesFromRows(s, List.of(equal, amount));
        assertEquals(out.get(0).inputs().get(38), "5000");
        assertEquals(out.get(1).inputs().get(38), "10000", "an amount stays as it is");
        assertEquals(out.get(1).inputs().get(37), "2500");
    }

    @Test
    public void renewalCasesGetTheRenewalRaterInputs() throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterTestCase renewal = new RaterTestCase("1", new LinkedHashMap<>(Map.of(14, "Renewal", 40, "Yes", 41, "700-749", 90, "0.6")), new LinkedHashMap<>());
        RaterTestCase newBusiness = new RaterTestCase("2", new LinkedHashMap<>(Map.of(14, "New Business", 40, "Yes", 41, "700-749")), new LinkedHashMap<>());
        List<RaterTestCase> out = RaterValidationRunner.withValuesFromRows(s, List.of(renewal, newBusiness));
        assertEquals(out.get(0).inputs().get(89), "No");
        assertEquals(out.get(0).inputs().get(90), "0.6", "the test data value wins");
        assertEquals(out.get(0).inputs().get(91), "0.7");
        assertEquals(out.get(0).inputs().get(95), "No Adjustment");
        assertEquals(out.get(0).inputs().get(92), "700-749", "renewal Insurance Score Range = row 41");
        assertEquals(out.get(0).inputs().get(93), "Yes", "renewal Insurance Score Applicable = row 40");
        assertFalse(out.get(1).inputs().containsKey(89), "New Business has no renewal inputs");
    }

    @Test
    public void windDeductibleFractionGetsTheRaterWordingAndWindRequestsSendTheFraction() throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterTestCase tc = new RaterTestCase("1", new LinkedHashMap<>(Map.of(15, "Florida", 43, "Yes", 45, "0.10")), new LinkedHashMap<>());
        RaterTestCase other = new RaterTestCase("2", new LinkedHashMap<>(Map.of(15, "Florida", 45, "0.02")), new LinkedHashMap<>());
        List<RaterTestCase> out = RaterValidationRunner.withValuesFromRows(s, List.of(tc, other));
        assertEquals(out.get(0).inputs().get(45), "Greater of $25,000 or 10%");
        assertEquals(out.get(1).inputs().get(45), "Greater of $5,000 or 2%");
        RaterConfiguration ho3 = RaterConfiguration.all(s).stream().filter(c -> c.id().equals("HO3")).findFirst().orElseThrow();
        Map<Integer, String> in = new LinkedHashMap<>(out.get(0).inputs());
        in.put(7, "FL");
        JsonNode cov = MAPPER.readTree(new RaterRequestMapper(s, "{}").build(ho3, in, Map.of()))
            .at("/Account/Insured/0/Location/0/Building/0/Coverage/0");
        assertEquals(cov.path("WindDeductiblePercentage").asText(), "0.1");
        Map<Integer, String> noWind = new LinkedHashMap<>(out.get(1).inputs());
        noWind.put(7, "FL");
        JsonNode nonWind = MAPPER.readTree(new RaterRequestMapper(s, "{}").build(ho3, noWind, Map.of()));
        assertEquals(nonWind.findValue("WindDeductiblePercentage").asText(), "0.02", "the API answers HTTP 500 for the wording");
    }

    @Test
    public void insuranceScoreNotApplicableStatesRateTheRaterWithNotApplicable() throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterTestCase md = new RaterTestCase("1", new LinkedHashMap<>(Map.of(15, "Maryland", 40, "799-834")), new LinkedHashMap<>());
        RaterTestCase va = new RaterTestCase("2", new LinkedHashMap<>(Map.of(15, "Virginia", 40, "799-834")), new LinkedHashMap<>());
        List<RaterTestCase> out = RaterValidationRunner.withValuesFromRows(s, List.of(md, va));
        assertEquals(out.get(0).inputs().get(40), "Not Applicable");
        assertEquals(out.get(0).inputs().get(93), "Not Applicable", "the renewal range copies row 40");
        assertEquals(out.get(1).inputs().get(40), "799-834");
    }

    @Test
    public void bookOfBusinessAdjustmentComesFromTheTestData() throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterTestCase yes = new RaterTestCase("1", new LinkedHashMap<>(Map.of(14, "Renewal", 89, "Yes")), new LinkedHashMap<>());
        RaterTestCase no = new RaterTestCase("2", new LinkedHashMap<>(Map.of(14, "Renewal", 89, "No")), new LinkedHashMap<>());
        RaterTestCase blank = new RaterTestCase("3", new LinkedHashMap<>(Map.of(14, "Renewal", 89, "")), new LinkedHashMap<>());
        RaterTestCase newBusiness = new RaterTestCase("4", new LinkedHashMap<>(Map.of(14, "New_Business", 89, "")), new LinkedHashMap<>());
        List<RaterTestCase> out = RaterValidationRunner.withValuesFromRows(s, List.of(yes, no, blank, newBusiness));
        assertEquals(out.get(0).inputs().get(89), "Yes");
        assertEquals(out.get(1).inputs().get(89), "No");
        assertEquals(out.get(2).inputs().get(89), "No", "an empty cell takes rater.input.renewal.89");
        assertFalse(out.get(3).inputs().containsKey(89), "New Business: the empty column is not sent");
        RaterConfiguration ho3 = RaterConfiguration.all(s).stream().filter(c -> c.id().equals("HO3")).findFirst().orElseThrow();
        JsonNode body = MAPPER.readTree(new RaterRequestMapper(s, "{}").build(ho3,
            new LinkedHashMap<>(Map.of(7, "FL", 14, "Renewal", 43, "Yes", 89, "Yes")), Map.of()));
        assertEquals(body.path("BookOfBusinessAdjustment").asText(), "Yes", "also in a wind request");
    }

    @Test
    public void theCountyTableIsReadFromTheConfigFile() throws IOException {
        RaterSettings s = RaterSettings.load();
        Map<String, List<String>> table = new RaterWorkbook(s, Files.createTempDirectory("county")).countyTable();
        assertEquals(table.get(RaterGeocoder.tableKey("Virginia", "HO6")).contains("VIRGINIA BEACH CITY"), true);
        assertFalse(table.get(RaterGeocoder.tableKey("Virginia", "HO6")).contains("Virginia Beach (City)"), "HO6 has one spelling");
        assertTrue(table.get(RaterGeocoder.tableKey("Virginia", "DP1_BUILDERS_RISK")).contains("Virginia Beach (City)"));
        assertTrue(table.get(RaterGeocoder.tableKey("Connecticut", "HO3")).contains("New Haven"));
    }

    @Test
    public void fetchedAddressesMustMatchTheCaseLocation() {
        Map<String, String> vaBeach = Map.of("city", "Virginia Beach", "state", "Virginia", "postcode", "23451");
        assertTrue(RaterGeocoder.matches(vaBeach, "VIRGINIA BEACH CITY", "Virginia", "23451"), "independent city");
        assertFalse(RaterGeocoder.matches(vaBeach, "VIRGINIA BEACH CITY", "Virginia", "23452"), "other ZIP");
        assertFalse(RaterGeocoder.matches(vaBeach, "VIRGINIA BEACH CITY", "North_Carolina", "23451"), "other state");
        Map<String, String> newHaven = Map.of("county", "New Haven", "state", "Connecticut", "postcode", "06511");
        assertTrue(RaterGeocoder.matches(newHaven, "SOUTH CENTRAL CONNECTICUT PLANNING REGION", "Connecticut", "06511"),
            "planning regions are not in the lookup: State and ZIP only");
        Map<String, String> miami = Map.of("county", "Miami-Dade County", "state", "Florida", "postcode", "33139");
        assertTrue(RaterGeocoder.matches(miami, "MIAMI-DADE", "Florida", "33139"));
        assertFalse(RaterGeocoder.matches(miami, "BROWARD", "Florida", "33139"), "other county");
        assertTrue(RaterGeocoder.matches(Map.of("county", "District of Columbia", "state", "District of Columbia", "postcode", "20001"),
            "DISTRICT OF COLUMBIA", "Washington_DC", "20001"));
        assertEquals(RaterGeocoder.normaliseCounty("Saint Tammany Parish"), "ST TAMMANY");
        assertEquals(RaterGeocoder.normaliseCounty("ST. TAMMANY"), "ST TAMMANY");
        assertEquals(RaterGeocoder.normaliseCounty("James City County"), RaterGeocoder.normaliseCounty("JAMES CITY"));
    }

    @Test
    public void requestColumnsAreReadAsRequestValuesNotRaterRows() throws IOException {
        Path file = Files.createTempFile("rater-testdata", ".xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("TestData");
            org.apache.poi.ss.usermodel.Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("TestCaseId");
            h.createCell(1).setCellValue("43:Coverage/PLWindCoverage/CoverageSelected");
            h.createCell(2).setCellValue("Request:RoofType");
            org.apache.poi.ss.usermodel.Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("6527");
            r.createCell(1).setCellValue("Yes");
            r.createCell(2).setCellValue("MetalRoof");
            try (java.io.OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
            RaterTestCase tc = RaterTestData.read(file, "TestData").get(0);
            assertEquals(tc.inputs(), Map.of(43, "Yes"));
            assertEquals(tc.requestValues(), Map.of("RoofType", "MetalRoof"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ---- RaterResponseMapper --------------------------------------------------------------------

    @Test
    public void responseMapperDistinguishesFoundMissingAndNotMapped() {
        RaterSettings s = settings(Map.of(), Map.of(
            1, "$.Out.Premium",
            2, "$.Out.Text",
            3, "$.Out.Missing",
            4, "",
            5, "$.Out.NullValue",
            6, "$.Out.List[1].V"));
        String response = "{\"Out\":{\"Premium\":3629.36,\"Text\":\"N/A\",\"NullValue\":null,\"List\":[{\"V\":1},{\"V\":2}]}}";
        RaterResponseMapper m = new RaterResponseMapper(s, response);

        Actual premium = m.read(1);
        assertEquals(premium.status(), Status.FOUND);
        assertEquals(premium.value(), "3629.36");
        assertEquals(m.read(2).value(), "N/A");
        assertEquals(m.read(3).status(), Status.NOT_FOUND);
        assertTrue(m.read(3).detail().contains("not found"));
        assertEquals(m.read(4).status(), Status.NOT_MAPPED);
        assertEquals(m.read(5).status(), Status.FOUND);
        assertEquals(m.read(5).value(), "");
        assertEquals(m.read(6).value(), "2");
    }

    @Test
    public void windAttributesAreComparedOnlyWhenWindIsSelected() {
        Properties p = new Properties();
        p.setProperty("rater.compare.tolerance", "0.01");
        p.setProperty("rater.wind.row", "43");
        p.setProperty("rater.compare.windOnly", "155,156");
        RaterSettings s = RaterSettings.of(p, Map.of(), Map.of(155, "$.WindAalFactor", 156, "$.GrossAal"));
        RaterResponseMapper noWindResponse = new RaterResponseMapper(s, "{\"WindAalFactor\":\"\"}");
        RaterAttribute factor = new RaterAttribute();
        factor.setRow(155);
        RaterAttribute aal = new RaterAttribute();
        aal.setRow(156);

        RaterTestCase noWind = new RaterTestCase("1");
        noWind.inputs().put(43, "No");
        assertEquals(RaterValidationRunner.compareRow(s, noWind, factor, "2.5", noWindResponse, null).result(),
            RaterReportWriter.NOT_APPLICABLE);
        assertEquals(RaterValidationRunner.compareRow(s, noWind, aal, "690.4", noWindResponse, null).result(),
            RaterReportWriter.NOT_APPLICABLE);

        RaterTestCase wind = new RaterTestCase("2");
        wind.inputs().put(43, "Yes");
        RaterResponseMapper windResponse = new RaterResponseMapper(s, "{\"WindAalFactor\":\"2.500\",\"GrossAal\":1091.34}");
        assertEquals(RaterValidationRunner.compareRow(s, wind, factor, "2.5", windResponse, null).result(), RaterReportWriter.PASS);
        assertEquals(RaterValidationRunner.compareRow(s, wind, aal, "1091.34", windResponse, null).result(), RaterReportWriter.PASS);
        assertEquals(RaterValidationRunner.compareRow(s, wind, aal, "1091.34", noWindResponse, null).result(), RaterReportWriter.FAIL);
    }

    @Test
    public void stateRegionCodeComesFromTheStateName() {
        assertEquals(StateCodes.of("Connecticut"), "CT");
        assertEquals(StateCodes.of("New_Jersey"), "NJ");
        assertEquals(StateCodes.of("Washington_DC"), "DC");
        assertEquals(StateCodes.of("va"), "VA");
        assertEquals(StateCodes.of("Atlantis"), null);

        Properties p = new Properties();
        p.setProperty("rater.request.stateRow", "15");
        p.setProperty("rater.request.stateCodeRow", "7");
        RaterSettings s = RaterSettings.of(p, Map.of(), Map.of());
        Map<Integer, String> inputs = new LinkedHashMap<>(Map.of(15, "Connecticut", 7, ""));
        RaterValidationRunner.withStateCode(s, inputs);
        assertEquals(inputs.get(7), "CT");               // the Rater left it blank
        Map<Integer, String> unknown = new LinkedHashMap<>(Map.of(15, "Atlantis", 7, "AT"));
        RaterValidationRunner.withStateCode(s, unknown);
        assertEquals(unknown.get(7), "AT");              // unknown state: the Rater's value stays
    }

    @Test
    public void subLineStateRulesSetTheirFixedRequestValues() throws IOException {
        Properties p = new Properties();
        p.setProperty("rater.request.stateCodeRow", "7");
        p.setProperty("rater.request.stateRules", "insuranceScoreNA,tigerCQNA");
        p.setProperty("rater.request.stateRule.insuranceScoreNA.states", "HI|KY|MA|MD");
        p.setProperty("rater.request.stateRule.insuranceScoreNA.values",
            "Account.Insured[0].InsuranceScoreStatus=Not Applicable;Account.Insured[0].InsuranceScoreRange=-1");
        p.setProperty("rater.request.stateRule.tigerCQNA.states", "MD|VA");
        p.setProperty("rater.request.stateRule.tigerCQNA.states.HO6", "TX");      // a configuration's own list wins
        p.setProperty("rater.request.stateRule.tigerCQNA.values", "Coverage.TigerCQApplicable=N");
        RaterSettings s = RaterSettings.of(p, Map.of(
            "row.7", "Account.Insured[0].StateRegionCode",
            "row.40", "Account.Insured[0].InsuranceScoreRange"));
        String template = "{\"Account\":{\"Insured\":[{\"StateRegionCode\":\"\",\"InsuranceScoreStatus\":\"1\",\"InsuranceScoreRange\":\"\"}]},"
            + "\"Coverage\":{\"TigerCQApplicable\":\"Y\"}}";
        RaterRequestMapper mapper = new RaterRequestMapper(s, template);

        JsonNode md = MAPPER.readTree(mapper.build(Map.of(7, "MD", 40, "741")));
        assertEquals(md.at("/Account/Insured/0/InsuranceScoreStatus").asText(), "Not Applicable");
        assertEquals(md.at("/Account/Insured/0/InsuranceScoreRange").asText(), "-1");
        assertEquals(md.at("/Coverage/TigerCQApplicable").asText(), "N");

        JsonNode va = MAPPER.readTree(mapper.build(Map.of(7, "VA", 40, "741")));
        assertEquals(va.at("/Account/Insured/0/InsuranceScoreStatus").asText(), "1");   // insurance score applies in VA
        assertEquals(va.at("/Account/Insured/0/InsuranceScoreRange").asText(), "741");
        assertEquals(va.at("/Coverage/TigerCQApplicable").asText(), "N");

        JsonNode fl = MAPPER.readTree(mapper.build(Map.of(7, "FL", 40, "741")));
        assertEquals(fl.at("/Account/Insured/0/InsuranceScoreRange").asText(), "741");
        assertEquals(fl.at("/Coverage/TigerCQApplicable").asText(), "Y");

        // Without the attribute in the body nothing is added
        RaterRequestMapper noCoverage = new RaterRequestMapper(s,
            "{\"Account\":{\"Insured\":[{\"StateRegionCode\":\"\",\"InsuranceScoreStatus\":\"1\",\"InsuranceScoreRange\":\"\"}]}}");
        assertTrue(MAPPER.readTree(noCoverage.build(Map.of(7, "MD"))).path("Coverage").isMissingNode());
    }

    @Test
    public void medicalPaymentsIsSentEmptyWhereNotApplicable() throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterConfiguration vacant = RaterConfiguration.all(s).stream().filter(c -> c.id().equals("DP1_VACANT")).findFirst().orElseThrow();
        JsonNode body = MAPPER.readTree(new RaterRequestMapper(s, "{}").build(vacant, Map.of(82, "None")));
        JsonNode limit = body.at("/Account/Insured/0/Location/0/Building/0/Coverage/0/MedicalPaymentsCoverageM/Limit");
        assertFalse(limit.isMissingNode(), "attribute constructed");
        assertEquals(limit.asText(), "");
    }

    @Test
    public void minimumAndMaximumTivAreTheBandOfTheTiv() throws IOException {
        Properties p = new Properties();
        p.setProperty("rater.request.tiv.rows", "18,19,20,21");
        p.setProperty("rater.request.tiv.minimumPath", "B.MinimumTiv");
        p.setProperty("rater.request.tiv.maximumPath", "B.MaximumTiv");
        p.setProperty("rater.request.tiv.bands", "0-5000;5000-10000;10000-20000;190000-200000;200000-10000000");
        RaterSettings s = RaterSettings.of(p, Map.of());
        assertEquals(RaterRequestMapper.tivBand(s, Map.of(18, "3000", 19, "0", 20, "1,000", 21, "0")), new long[]{0, 5000});
        assertEquals(RaterRequestMapper.tivBand(s, Map.of(18, "5000")), new long[]{5000, 10000});          // minimum included
        assertEquals(RaterRequestMapper.tivBand(s, Map.of(18, "9999")), new long[]{5000, 10000});
        assertEquals(RaterRequestMapper.tivBand(s, Map.of(18, "150000", 19, "15000", 20, "20000", 21, "10611")),
            new long[]{190000, 200000});                                                                     // 195,611
        assertEquals(RaterRequestMapper.tivBand(s, Map.of(18, "250000", 20, "6519")), new long[]{200000, 10000000});
        assertEquals(RaterRequestMapper.tivBand(s, Map.of(18, "20000000")), new long[]{200000, 10000000}); // above the last
        assertEquals(RaterRequestMapper.tivBand(s, Map.of(18, "abc")), null);

        RaterRequestMapper mapper = new RaterRequestMapper(s, "{\"B\":{\"MinimumTiv\":\"1\",\"MaximumTiv\":\"0.88\"}}");
        JsonNode outside = MAPPER.readTree(mapper.build(Map.of(18, "93160")));   // in no band: template values stay
        assertEquals(outside.at("/B/MinimumTiv").asText(), "1");
        JsonNode inBand = MAPPER.readTree(mapper.build(Map.of(18, "7000")));
        assertEquals(inBand.at("/B/MinimumTiv").asText(), "5000");
        assertEquals(inBand.at("/B/MaximumTiv").asText(), "10000");
        // Not added where the body has no such attribute
        RaterRequestMapper bare = new RaterRequestMapper(s, "{\"B\":{}}");
        assertTrue(MAPPER.readTree(bare.build(Map.of(18, "7000"))).at("/B/MinimumTiv").isMissingNode());
    }

    @Test
    public void rejectedTokenWaitsForANewTokenFile() throws Exception {
        Path tokenFile = Files.createTempFile("token", ".txt");
        try {
            Files.writeString(tokenFile, "Token: old");
            // Not waiting (0 minutes): a rejected token fails at once
            assertFalse(new RaterValidationRunner.TokenWait(tokenFile, 0).newTokenAfter("old"));

            RaterValidationRunner.TokenWait wait = new RaterValidationRunner.TokenWait(tokenFile, 1);
            Thread update = new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    Files.writeString(tokenFile, "Token: new");
                } catch (Exception ignored) {
                }
            });
            update.start();
            assertTrue(wait.newTokenAfter("old"));         // returns once the file has the new token
            update.join();
            assertTrue(wait.newTokenAfter("old"));         // a call with the old token picks up the new one at once
        } finally {
            Files.deleteIfExists(tokenFile);
        }
    }

    @Test
    public void responseMapperSurvivesANonJsonResponse() {
        RaterSettings s = settings(Map.of(), Map.of(1, "$.a"));
        Actual a = new RaterResponseMapper(s, "<html>502 Bad Gateway</html>").read(1);
        assertEquals(a.status(), Status.BAD_RESPONSE);
        assertTrue(a.detail().contains("not valid JSON"));
    }

    // ---- JsonText -------------------------------------------------------------------------------

    @Test
    public void numbersAreFormattedPlainly() throws IOException {
        assertEquals(JsonText.of(MAPPER.readTree("1233.0")), "1233");
        assertEquals(JsonText.of(MAPPER.readTree("1E+3")), "1000");
        assertEquals(JsonText.of(MAPPER.readTree("0.035")), "0.035");
        assertEquals(JsonText.of(MAPPER.readTree("null")), "");
        assertEquals(JsonText.of(MAPPER.readTree("\"x\"")), "x");
    }

    // ---- Test data and report workbooks ---------------------------------------------------------

    @Test
    public void testDataSurvivesAWriteReadRoundTrip() throws IOException {
        RaterAttribute state = attribute(15, "Insured", "Location", "State", List.of("Florida", "Texas"));
        RaterAttribute limit = attribute(18, "Coverage", "DwellingCoverage", "Limit", List.of());
        List<RaterAttribute> attrs = List.of(state, limit);

        List<RaterTestCase> cases = RaterTestData.generate(settings(Map.of(), Map.of()), attrs, 5, 42L);
        assertEquals(cases.size(), 5);
        assertEquals(cases.get(0).id(), "TC001");
        for (RaterTestCase tc : cases) {
            assertTrue(List.of("Florida", "Texas").contains(tc.inputs().get(15)) || tc.inputs().get(15).isEmpty());
        }
        // same seed => same data
        assertEquals(RaterTestData.generate(settings(Map.of(), Map.of()), attrs, 5, 42L).get(3).inputs(), cases.get(3).inputs());

        Path file = Files.createTempFile("rater-testdata", ".xlsx");
        try {
            RaterTestData.write(file, "TestData", attrs, cases);
            List<RaterTestCase> read = RaterTestData.read(file, "TestData");
            assertEquals(read.size(), 5);
            assertEquals(read.get(2).id(), cases.get(2).id());
            assertEquals(read.get(2).inputs(), cases.get(2).inputs());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void reportHasTheRequestedColumnsAndPassFailPerAttribute() throws IOException {
        List<RaterReportWriter.Line> lines = List.of(
            new RaterReportWriter.Line("TC001", "Location/Building/FinalPropertyPremium (C166)", "3629.36", "3629.36", "Pass", ""),
            new RaterReportWriter.Line("TC001", "Coverage/PLNonWind/Premium (C153)", "1415.48", "1500", "Fail", "Difference 84.52 exceeds tolerance 0.01"),
            new RaterReportWriter.Line("TC002", "Location/Building/BasePremium (C228)", "3699", "3699", "Pass", ""));
        Path file = Files.createTempFile("rater-report", ".xlsx");
        try {
            RaterReportWriter.write(file, lines);
            try (InputStream in = Files.newInputStream(file); Workbook wb = new XSSFWorkbook(in)) {
                Sheet results = wb.getSheet("Results");
                assertEquals(results.getRow(0).getCell(0).getStringCellValue(), "Test Case ID");
                assertEquals(results.getRow(0).getCell(1).getStringCellValue(), "Attribute Path (D/E/F)");
                assertEquals(results.getRow(0).getCell(2).getStringCellValue(), "Expected (Rater)");
                assertEquals(results.getRow(0).getCell(3).getStringCellValue(), "Actual (API)");
                assertEquals(results.getRow(0).getCell(4).getStringCellValue(), "Result");
                assertEquals(results.getRow(2).getCell(4).getStringCellValue(), "Fail");

                Sheet summary = wb.getSheet("Summary");
                assertEquals(summary.getRow(1).getCell(0).getStringCellValue(), "TC001");
                assertEquals(summary.getRow(1).getCell(4).getStringCellValue(), "Fail");
                assertEquals(summary.getRow(2).getCell(4).getStringCellValue(), "Pass");
                assertEquals(summary.getRow(3).getCell(2).getStringCellValue(), "2");   // total pass
                assertEquals(summary.getRow(3).getCell(3).getStringCellValue(), "1");   // total fail
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static RaterAttribute attribute(int row, String parent, String entity, String name, List<String> values) {
        RaterAttribute a = new RaterAttribute();
        a.setRow(row);
        a.setRole("INPUT");
        a.setParent(parent);
        a.setEntity(entity);
        a.setName(name);
        a.setCurrent(MAPPER.getNodeFactory().textNode(values.isEmpty() ? "" : values.get(0)));
        a.setValues(values);
        return a;
    }

    @Test
    public void iterationsSelectCasesByNumberRangeOrId() {
        List<RaterTestCase> all = List.of("TC001", "TC002", "TC003", "TC004", "TC005").stream().map(RaterTestCase::new).toList();
        assertEquals(RaterValidationRunner.selectIterations(all, "1,2,3").stream().map(RaterTestCase::id).toList(), List.of("TC001", "TC002", "TC003"));
        assertEquals(RaterValidationRunner.selectIterations(all, "2-4").stream().map(RaterTestCase::id).toList(), List.of("TC002", "TC003", "TC004"));
        assertEquals(RaterValidationRunner.selectIterations(all, "tc005, 1").stream().map(RaterTestCase::id).toList(), List.of("TC001", "TC005"));
        assertEquals(RaterValidationRunner.selectIterations(all, "9").size(), 0);
    }

    @Test
    public void valueMappingTranslatesRaterWordingForTheApi() throws IOException {
        String text = String.join("\n", "# comment", "12 | RCV | ALL", "22 | 5 - 10 Mi. | 7.5", "50 | None | ", "broken line");
        RaterValueMapping m = RaterValueMapping.parse(new java.io.BufferedReader(new java.io.StringReader(text)));
        assertEquals(m.toApi(12, "RCV"), "ALL");
        assertEquals(m.toApi(22, " 5 - 10 Mi. "), "7.5");
        assertEquals(m.toApi(50, "None"), "");           // blank API value is allowed
        assertEquals(m.toApi(12, "Other"), "Other");     // no entry -> unchanged
        assertEquals(m.toApi(99, "RCV"), "RCV");         // other rows unaffected
    }

    // ---- Configurations (Subline + Occupancy Type) ----------------------------------------------

    private static Map<Integer, String> inputs(String subline, String occupancy, String dwelling, String contents) {
        Map<Integer, String> in = new LinkedHashMap<>();
        in.put(9, subline);
        in.put(12, dwelling);
        in.put(13, contents);
        in.put(26, occupancy);
        return in;
    }

    @DataProvider(name = "configurations")
    public Object[][] configurationCases() {
        return new Object[][]{
            // subline, occupancy, dwelling/contents valuation, expected configuration (null = not supported)
            {"HO3", "Primary", "RCV", "ACV", "HO3"},
            {"HO5", "Secondary/Seasonal", "RCV", "RCV", "HO5"},
            {"HO6", "Rental - Annual", "FRC", "FRC", "HO6"},              // HO6 is rated with ALL whatever the Rater says
            {"HO6", "Vacant", "RCV", "RCV", "HO6"},                       // Vacant is an HO6 occupancy, not a combination
            {"HO8", "Short-Term Rental", "FRC", "ACV", "HO8"},
            {"DP1", "Long-Term Rental", "ACV", "ACV", "DP1"},
            {"DP3", "Primary", "RCV", "ACV", "DP3"},
            {"DP1", "Builders Risk", "RCV", "RCV", "DP1_BUILDERS_RISK"},
            {"DP3", "Builders Risk", "ACV", "FRC", "DP3_BUILDERS_RISK"},
            {"DP1", "Vacant", "ACV", "ACV", "DP1_VACANT"},
            {"DP3", "Vacant", "RCV", "ACV", "DP3_VACANT"},
            {"HO3", "Vacant", "RCV", "ACV", null},                        // Builders Risk / Vacant only combine with DP1/DP3
            {"HO8", "Builders Risk", "ACV", "ACV", null},
            {"HO3", "Primary", "ACV", "RCV", null},                       // valuation pair not rated for HO3
            {"DP3", "Primary", "RCV", "RCV", null},
            {"", "Primary", "RCV", "ACV", null},
        };
    }

    @Test(dataProvider = "configurations")
    public void testCasesResolveToOneOfTheTenConfigurations(String subline, String occupancy, String dwelling, String contents,
                                                            String expected) throws IOException {
        RaterSettings s = RaterSettings.load();
        List<RaterConfiguration> all = RaterConfiguration.all(s);
        assertEquals(all.size(), 10);
        RaterConfiguration.Resolution r = RaterConfiguration.resolve(s, all, inputs(subline, occupancy, dwelling, contents));
        if (expected == null) {
            assertFalse(r.supported(), subline + "/" + occupancy);
            assertFalse(r.problem().isEmpty());
        } else {
            assertTrue(r.supported(), r.problem());
            assertEquals(r.configuration().id(), expected);
        }
    }

    @Test
    public void requestTargetsHonourConfigurationOverridesAndExtraAttributes() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("row.40", "Account.Score");
        entries.put("row.40.status", "Account.Status");
        entries.put("row.58", "EQ.Selected");
        entries.put("row.60", "");
        entries.put("HO6.row.58", "");
        entries.put("HO6.row.60", "EQ.Selected");
        RaterSettings s = RaterSettings.of(new Properties(), entries);

        assertEquals(s.requestTargets("HO3", 40), Map.of("40", "Account.Score", "40.status", "Account.Status"));
        assertEquals(s.requestTargets("HO3", 58), Map.of("58", "EQ.Selected"));
        assertTrue(s.requestTargets("HO3", 60).isEmpty(), "blank = not sent");
        assertTrue(s.requestTargets("HO6", 58).isEmpty(), "configuration entry removes the generic one");
        assertEquals(s.requestTargets("ho6", 60), Map.of("60", "EQ.Selected"));
        assertEquals(s.requestPath(40), "Account.Score");
    }

    @Test
    public void valueMappingSupportsConfigurationsWildcardsAndExtraTargets() throws IOException {
        String text = String.join("\n",
            "12 | * | ALL | HO6, DP3_VACANT",
            "40 | No Hit | -1",
            "40.status | No Hit | 2",
            "40.status | * | 1",
            "50 | None | ",
            "50 | None | X | HO6");
        RaterValueMapping m = RaterValueMapping.parse(new java.io.BufferedReader(new java.io.StringReader(text)));
        assertEquals(m.toApi("HO6", "12", "RCV"), "ALL");
        assertEquals(m.toApi("DP3_VACANT", "12", "FRC"), "ALL");
        assertEquals(m.toApi("HO3", "12", "RCV"), "RCV", "limited to other configurations");
        assertEquals(m.toApi("HO3", "40", "No Hit"), "-1");
        assertEquals(m.toApi("HO3", "40.status", "No Hit"), "2");
        assertEquals(m.toApi("HO3", "40.status", "614-652"), "1", "wildcard");
        assertTrue(m.lookup("HO3", "40", "614-652").isEmpty(), "no entry -> caller decides");
        assertEquals(m.toApi("HO6", "50", "None"), "X", "configuration entry wins");
        assertEquals(m.toApi("HO3", "50", "None"), "");
    }

    @DataProvider(name = "ranges")
    public Object[][] ranges() {
        return new Object[][]{
            {"614-652", RangeValue.Pick.MIN, "614"},
            {"614-652", RangeValue.Pick.MAX, "652"},
            {"614-652", RangeValue.Pick.MID, "633"},
            {"0-548", RangeValue.Pick.MIN, "0"},
            {"0 - 5 Years", RangeValue.Pick.MID, "2"},
            {"6 - 10 Years", RangeValue.Pick.MAX, "10"},
            {"11 or more Years", RangeValue.Pick.MAX, "11"},          // open-ended: the lower bound
            {"No Hit", RangeValue.Pick.MIN, null},
            {"$250,000 - $349,999", RangeValue.Pick.MIN, null},      // a band label, not a numeric range
            {"5 - 10 Mi.", RangeValue.Pick.MIN, "5"},                 // numeric shape; row 22 is not a range row, see below
            {"1500", RangeValue.Pick.MIN, null},
            {"652-614", RangeValue.Pick.MIN, null},
        };
    }

    @Test(dataProvider = "ranges")
    public void rangeValuesBecomeOneNumberInsideTheRange(String text, RangeValue.Pick pick, String expected) {
        assertEquals(RangeValue.resolve(text, pick).orElse(null), expected);
    }

    @DataProvider(name = "requests")
    public Object[][] requests() {
        return new Object[][]{{"HO3"}, {"HO5"}, {"HO6"}, {"HO8"}, {"DP1"}, {"DP3"},
            {"DP1_BUILDERS_RISK"}, {"DP3_BUILDERS_RISK"}, {"DP1_VACANT"}, {"DP3_VACANT"}};
    }

    /** Builds a real request for every configuration from its template and the real mapping files. */
    @Test(dataProvider = "requests")
    public void requestIsBuiltFromTheConfigurationTemplate(String id) throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterConfiguration c = RaterConfiguration.all(s).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        Map<Integer, String> in = inputs(c.subline(), c.occupancies().get(0), "RCV", "ACV");
        in.put(7, "FL");                       // derived state code (a TigerCQ state: its own insurance score)
        in.put(15, "Florida");
        in.put(22, "5 - 10 Mi.");
        in.put(28, "11 or more Years");
        in.put(30, "2");
        in.put(36, "Full Policy Limits");
        in.put(40, "614-652");
        in.put(50, "None");
        in.put(57, "Yes");                     // Personal Injury: HO6 only
        in.put(58, "No");
        in.put(59, "5% Deductible");
        in.put(60, "Excluded");
        in.put(82, "1000");
        in.put(43, "No");                      // wind: see windRequestHasExactlyTheKeysOfTheWindSample

        JsonNode body = MAPPER.readTree(new RaterRequestMapper(s, "{}").build(c, in));
        JsonNode building = body.at("/Account/Insured/0/Location/0/Building/0");
        JsonNode coverage = building.at("/Coverage/0");
        assertEquals(body.path("SublineName").asText(), c.subline());
        assertEquals(building.path("Occupancy").asText(), c.occupancies().get(0));
        assertEquals(body.at("/Account/Insured/0/Location/0/StateRegionCode").asText(), "FL");
        assertEquals(building.path("CoastalWaterDistance").asText(), "25000", "band key, not miles");
        assertEquals(building.path("AgeOfRoof").asText(), "11", "a number inside the range");
        assertEquals(building.path("NumberOfLosses").asText(), "2");
        assertFalse(building.has("NumberofLosses"));
        assertEquals(body.at("/Account/Insured/0/InsuranceScoreRange").asText(), "614", "never the range text");
        assertEquals(body.at("/Account/Insured/0/InsuranceScoreStatus").asText(), "1");
        assertEquals(coverage.at("/WaterDamageSublimit/Limit").asText(), "");
        assertEquals(coverage.at("/PLWindCoverage/CoverageSelected").asText(), "No");
        assertFalse(coverage.has("TigerCQApplicable"), "no wind attributes without wind");
        assertEquals(coverage.at("/PersonalInjuryCoverage/CoverageSelected").asText(id.equals("HO6") ? "" : "<absent>"),
            id.equals("HO6") ? "Yes" : "<absent>");

        boolean allValuation = List.of("HO6", "DP1_BUILDERS_RISK", "DP3_BUILDERS_RISK", "DP1_VACANT", "DP3_VACANT").contains(id);
        assertEquals(coverage.path("DwellingValuation").asText(), allValuation ? "ALL" : "RCV");
        assertEquals(coverage.path("PersonalPropertyValuation").asText(), allValuation ? "ALL" : "ACV");
        assertFalse(coverage.path("MedicalPaymentsCoverageM").path("Limit").isMissingNode(),
            "Medical Payments is always sent (\"\" where it is not applicable)");
        if (id.equals("HO6")) {
            assertEquals(coverage.at("/EarthquakeCoverage/CoverageSelected").asText(), "Excluded", "HO6 uses row 60");
            assertTrue(coverage.at("/EarthquakeCoverage/Deductible").isMissingNode());
            assertTrue(coverage.path("LimitedMoldCoverage").isMissingNode(), "HO6 has no Limited Mold");
        } else {
            assertEquals(coverage.at("/EarthquakeCoverage/CoverageSelected").asText(), "No", "row 58");
            assertEquals(coverage.at("/EarthquakeCoverage/Deductible").asText(), "5");
            assertEquals(coverage.at("/EarthquakeCoverage/EarthquakeDeductiblePercentage").asText(), "5");
            assertEquals(coverage.at("/LimitedMoldCoverage/Limit").asText(), "");
        }
        assertEquals(body.at("/ServiceRequestDetail/Token").asText(), "", "templates hold no token");
    }

    @Test
    public void insuranceScoreStatusesWithoutAScore() throws IOException {
        RaterSettings s = RaterSettings.load();
        RaterConfiguration ho3 = RaterConfiguration.all(s).get(0);
        RaterRequestMapper mapper = new RaterRequestMapper(s, "{}");
        for (String[] t : new String[][]{{"No Hit", "-1", "2"}, {"Reject", "-1", "Reject"}, {"Not Needed", "", ""}, {"0-548", "0", "1"}}) {
            Map<Integer, String> in = inputs("HO3", "Primary", "RCV", "ACV");
            in.put(40, t[0]);
            JsonNode insured = MAPPER.readTree(mapper.build(ho3, in)).at("/Account/Insured/0");
            assertEquals(insured.path("InsuranceScoreRange").asText(), t[1], t[0]);
            assertEquals(insured.path("InsuranceScoreStatus").asText(), t[2], t[0]);
        }
    }

    @Test
    public void generatedCasesAreAlwaysASupportedConfiguration() throws IOException {
        RaterSettings s = RaterSettings.load();
        List<RaterConfiguration> all = RaterConfiguration.all(s);
        java.util.Random random = new java.util.Random(7);
        String[] sublines = {"HO3", "HO5", "HO6", "HO8", "DP1", "DP3"};
        String[] occupancies = {"Primary", "Vacant", "Builders Risk", "Rental - Annual"};
        String[] valuations = {"RCV", "ACV", "FRC"};
        for (int i = 0; i < 300; i++) {
            RaterTestCase tc = new RaterTestCase("TC" + i);
            tc.inputs().putAll(inputs(sublines[random.nextInt(6)], occupancies[random.nextInt(4)],
                valuations[random.nextInt(3)], valuations[random.nextInt(3)]));
            tc.inputs().put(82, "5000");
            RaterTestData.conform(s, all, tc, random);
            RaterConfiguration.Resolution r = RaterConfiguration.resolve(s, all, tc.inputs());
            assertTrue(r.supported(), tc.inputs() + " -> " + r.problem());
            if (r.configuration().id().contains("_")) assertEquals(tc.inputs().get(82), "None");
        }
    }

    @Test
    public void reportCountsNotApplicableAndSkippedSeparately() throws IOException {
        List<RaterReportWriter.Line> lines = List.of(
            new RaterReportWriter.Line("TC001", "a (C110)", "1", "1", "Pass", "", "HO3"),
            new RaterReportWriter.Line("TC001", "b (C113)", "N/A", "0", "N/A", "Not applicable", "HO3"),
            new RaterReportWriter.Line("TC002", "(test case)", "", "", "Skipped", "Unsupported configuration", ""),
            new RaterReportWriter.Line("TC003", "a (C110)", "1", "2", "Fail", "Difference", "DP3_VACANT"));
        Path file = Files.createTempFile("rater-report", ".xlsx");
        try {
            RaterReportWriter.write(file, lines);
            try (InputStream in = Files.newInputStream(file); Workbook wb = new XSSFWorkbook(in)) {
                assertEquals(wb.getSheet("Results").getRow(0).getCell(6).getStringCellValue(), "Configuration");
                Sheet summary = wb.getSheet("Summary");
                assertEquals(summary.getRow(1).getCell(4).getStringCellValue(), "Pass");      // TC001: N/A does not fail
                assertEquals(summary.getRow(1).getCell(5).getStringCellValue(), "1");
                assertEquals(summary.getRow(2).getCell(4).getStringCellValue(), "Skipped");
                assertEquals(summary.getRow(3).getCell(4).getStringCellValue(), "Fail");
                Sheet configurations = wb.getSheet("Configurations");
                assertEquals(configurations.getRow(1).getCell(0).getStringCellValue(), "HO3");
                assertEquals(configurations.getRow(1).getCell(2).getStringCellValue(), "1");
                assertEquals(configurations.getRow(3).getCell(0).getStringCellValue(), "DP3_VACANT");
                assertEquals(configurations.getRow(3).getCell(3).getStringCellValue(), "1");
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void emptyDropdownInputIsReportedAsInvalidTestData() {
        Map<Integer, RaterAttribute> byRow = Map.of(
            35, attribute(35, "Location", "Building", "Alarm", List.of("None", "Central Station Fire Alarm")),
            18, attribute(18, "Coverage", "DwellingCoverage", "Limit", List.of()));       // free input, no dropdown
        RaterTestCase tc = new RaterTestCase("TC1");
        tc.inputs().put(35, "None");
        tc.inputs().put(18, "");
        assertEquals(RaterValidationRunner.blankDropdownInputs(tc, byRow, List.of()), null, "an empty free input is allowed");
        tc.inputs().put(35, " ");
        String problem = RaterValidationRunner.blankDropdownInputs(tc, byRow, List.of());
        assertTrue(problem.startsWith("Invalid test data") && problem.contains("Alarm (row 35)"), problem);
        assertEquals(RaterValidationRunner.blankDropdownInputs(tc, byRow, List.of(35)), null, "optional rows may be empty");
    }

    @Test
    public void tokenIsMaskedEvenWhenVeryLong() {
        String token = "ab/c+d=".repeat(2000);                                         // ~14,000 chars incl. '/'
        String plain = "{\"ServiceRequestDetail\":{\"Token\":\"" + token + "\",\"User\":\"x\"}}";
        String escaped = "{\"RequestJSON\":\"{\\\"Token\\\":\\\"" + token.replace("/", "\\/") + "\\\",\\\"User\\\":\\\"x\\\"}\"}";
        String maskedPlain = RaterValidationRunner.maskToken(plain);
        String maskedEscaped = RaterValidationRunner.maskToken(escaped);
        assertFalse(maskedPlain.contains("ab/c+d="), maskedPlain);
        assertTrue(maskedPlain.contains("\"Token\":\"****\""), maskedPlain);
        assertFalse(maskedEscaped.contains("ab"), maskedEscaped);
        assertTrue(maskedEscaped.contains("\"User\\\":\\\"x\\\""), maskedEscaped);      // the rest is untouched
    }
}
