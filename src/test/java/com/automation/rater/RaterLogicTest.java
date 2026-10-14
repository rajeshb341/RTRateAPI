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
        // 11-23 (13) + 25-38 (14) + 40-41 (2) + 43-45 (3) + 47-48 (2) + 50-60 (11) + 62-75 (14) + 77-79 (3) + 81-82 (2)
        assertEquals(s.inputRows().size(), 64, "input rows are expanded from the given cell ranges");
        assertEquals(s.outputRows().get(0), 104);
        assertEquals(s.requestPath(15), "Account.Insured[0].Location[0].State");
        assertEquals(s.requestPath(60), "", "row 60 is intentionally unmapped");
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
}
