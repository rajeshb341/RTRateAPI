package com.automation.rater;

import com.automation.rater.RaterReportWriter.Line;
import com.automation.rater.RaterValidationRunner.Called;
import com.automation.ui.UiCase;
import com.automation.ui.UiRatingFlow;
import com.automation.ui.UiResult;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** UI vs API validation: attribute keys, inputs handed to the UI flow and the comparison rules. */
public class UiApiValidationTest {

    @Test
    public void theUiFieldsOfAnIterationAreTheUiColumnsOfItsTestDataRow() throws Exception {
        RaterSettings s = RaterSettings.load();
        com.automation.ui.UiTestData ui = com.automation.ui.UiTestData.read(s.path("rater.testdata.file"));
        Map<String, String> row = ui.row("7350");
        assertEquals(row.get("Market"), "ConfiguredNew");
        assertTrue(!row.get("OfficeName").isBlank() && !row.get("Agency_name").isBlank(), "office / agency from the UI: columns");
        assertTrue(!row.containsKey("Insured_Address"), "the address comes from the API request, not a UI: column");
        // the API test case reader ignores the UI: columns
        List<RaterTestCase> cases = RaterTestData.read(s.path("rater.testdata.file"), "TestData");
        assertTrue(cases.stream().allMatch(c -> c.requestValues().keySet().stream().noneMatch(k -> k.startsWith("UI"))));
    }

    @Test
    public void todayIsTheEffectiveDateOfTheRaterTheRequestAndTheUi() throws Exception {
        RaterSettings s = RaterSettings.load();
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy"));
        String nextYear = java.time.LocalDate.now().plusYears(1).format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy"));
        RaterTestCase tc = new RaterTestCase("1", new LinkedHashMap<>(Map.of(7, "FL", 9, "HO3", 14, "New_Business", 43, "No")),
            new LinkedHashMap<>());
        Map<Integer, String> in = RaterValidationRunner.withValuesFromRows(s, List.of(tc)).get(0).inputs();
        assertEquals(in.get(2), today, "Rater Applicable start date");
        assertEquals(in.get(3), today, "Rater Effective date");
        assertEquals(in.get(4), nextYear, "Rater Expiration date");
        RaterConfiguration ho3 = RaterConfiguration.all(s).stream().filter(c -> c.id().equals("HO3")).findFirst().orElseThrow();
        String body = new RaterRequestMapper(s, "{}").build(ho3, new LinkedHashMap<>(in), Map.of());
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(json.findValue("PolicyEffectiveDate").asText(), today);
        assertEquals(json.findValue("EffectiveDate").asText(), today);
        assertEquals(json.findValue("ApplicableDate").asText(), today);
        assertEquals(json.findValue("PolicyExpirationDate").asText(), nextYear);
        assertEquals(com.automation.ui.UiInputMapping.load().apply(body).get("PolicyEffectiveDate"), today, "the UI gets the same date");
    }

    @Test
    public void uiCredentialsComeFromTheEnvFileAndThePasswordIsNeverPrinted() throws Exception {
        Path env = Files.createTempFile("ui", ".env");
        Files.writeString(env, "# comment\nRT_URL=https://uat.example.test/web#/\nRT_USERNAME=qa.user\nRT_PASSWORD=\"s3cret\"\n");
        com.automation.ui.UiCredentials c = com.automation.ui.UiCredentials.load(env);
        assertEquals(c.url(), "https://uat.example.test/web#/");
        assertEquals(c.username(), "qa.user");
        assertEquals(c.password(), "s3cret");
        assertTrue(!c.toString().contains("s3cret") && c.toString().contains("****"));
    }

    @Test
    public void theUiGetsTheApiRequestValuesThroughTheMappingFile() throws Exception {
        String request = """
            {"SublineName":"DP3","PolicyEffectiveDate":"10/15/2026",
             "Account":{"Insured":[{"Location":[{"LocationFullAddress":"906 GULF SHORES PKWY, GULF SHORES, AL, 36542, United States",
               "Building":[{"DateBuilt":"2004","Coverage":[{"DwellingCoverage":{"Limit":"299000"},"DwellingValuation":""}]}]}]}]}}""";
        Map<String, String> ui = com.automation.ui.UiInputMapping.load().apply(request);
        assertEquals(ui.get("Insured_Address"), "906 GULF SHORES PKWY, GULF SHORES, AL, 36542, United States");
        assertEquals(ui.get("Policy_Form"), "DP3");
        assertEquals(ui.get("product"), "Dwelling Fire", "value.product.DP3");
        assertEquals(ui.get("coverage_A"), "299000");
        assertEquals(ui.get("Year_Built"), "2004");
        assertTrue(!ui.containsKey("LossSettlement_Type"), "an empty API value is not mapped (the UI: column is used)");
    }

    @Test
    public void recordedUiCallsHaveTheirSecretsMasked() throws Exception {
        java.lang.reflect.Method mask = com.automation.ui.UiNetworkCapture.class.getDeclaredMethod("mask", String.class);
        mask.setAccessible(true);
        String masked = (String) mask.invoke(null,
            "{\"Token\":\"abc\",\"accessToken\":\"x1\",\"Password\":\"p\",\"SublineName\":\"HO3\"}");
        assertEquals(masked, "{\"Token\":\"****\",\"accessToken\":\"****\",\"Password\":\"****\",\"SublineName\":\"HO3\"}");
    }

    @Test
    public void keysAreAttributeNamesWithTheParentOnlyWhenNeeded() {
        Map<String, String> keys = UiApiValidationRunner.keys(List.of(
            "$.Account.Insured[0].Location[0].Building[0].BasePremium",
            "$.Account.Insured[0].Location[0].Building[0].Coverage[0].PLNonWind.Premium",
            "$.Account.Insured[0].Location[0].Building[0].Coverage[0].PLWindCoverage.Premium",
            "$.FDRFloor"));
        assertEquals(keys.get("$.Account.Insured[0].Location[0].Building[0].BasePremium"), "BasePremium");
        assertEquals(keys.get("$.Account.Insured[0].Location[0].Building[0].Coverage[0].PLNonWind.Premium"), "PLNonWind.Premium");
        assertEquals(keys.get("$.Account.Insured[0].Location[0].Building[0].Coverage[0].PLWindCoverage.Premium"), "PLWindCoverage.Premium");
        assertEquals(keys.get("$.FDRFloor"), "FDRFloor");
    }

    @Test
    public void theUiGetsTheRequestValuesByAttributeNameWithoutTheToken() throws Exception {
        Map<String, String> in = UiApiValidationRunner.inputs("""
            {"ServiceRequestDetail":{"Token":"secret"},"SublineName":"HO3",
             "Account":{"Insured":[{"Location":[{"Zipcode1":"23607","Building":[{"Coverage":[{"WindDeductiblePercentage":"0.01",
               "PLWindCoverage":{"CoverageSelected":"No"},"PLNonWind":{"CoverageSelected":"Yes"}}]}]}]}]}}""");
        assertEquals(in.get("SublineName"), "HO3");
        assertEquals(in.get("Zipcode1"), "23607");
        assertEquals(in.get("WindDeductiblePercentage"), "0.01");
        assertEquals(in.get("PLWindCoverage.CoverageSelected"), "No");
        assertTrue(in.values().stream().noneMatch("secret"::equals), "the token is not handed to the UI");
    }

    @Test
    public void uiValuesAreComparedWithTheApiResponse() throws Exception {
        RaterSettings s = RaterSettings.load();
        Path dir = Files.createTempDirectory("ui");
        Files.writeString(dir.resolve("request.json"), "{\"body\":{\"SublineName\":\"HO3\"}}");
        String response = "{\"BasePremium\":\"1536\",\"FDRFloor\":\"0.2622\",\"NonWindFDR\":\"0.1748\"}";
        Map<String, String> outputPaths = new LinkedHashMap<>(Map.of("BasePremium", "$.BasePremium", "FDRFloor", "$.FDRFloor",
            "NonWindFDR", "$.NonWindFDR", "TotalLiabilityPremium", "$.TotalLiabilityPremium"));
        Map<String, Integer> rowOf = Map.of("$.BasePremium", 228, "$.FDRFloor", 239, "$.NonWindFDR", 229, "$.TotalLiabilityPremium", 223);
        RaterSettings mapped = RaterSettings.of(new java.util.Properties(), Map.of(),
            Map.of(228, "$.BasePremium", 239, "$.FDRFloor", 229, "$.NonWindFDR", 223, "$.TotalLiabilityPremium"));
        Called api = new Called(null, new RaterResponseMapper(mapped, response));
        UiRatingFlow ui = c -> {
            assertEquals(c.input("SublineName"), "HO3");
            return UiResult.of(Map.of("BasePremium", "1536", "FDRFloor", "0.2900"));   // NonWindFDR not shown
        };
        RaterTestCase tc = new RaterTestCase("7", new LinkedHashMap<>(Map.of(14, "Renewal")), new LinkedHashMap<>());
        List<Line> lines = UiApiValidationRunner.oneCase(s, tc, "HO3", api, ui, outputPaths, rowOf, false, dir);
        Map<String, String> result = new LinkedHashMap<>();
        lines.forEach(l -> result.put(l.attributePath().split(" ")[0], l.result()));
        assertEquals(result.get("BasePremium"), RaterReportWriter.PASS);
        assertEquals(result.get("FDRFloor"), RaterReportWriter.FAIL);
        assertEquals(result.get("NonWindFDR"), RaterReportWriter.NOT_APPLICABLE, "not shown in the UI");
        assertEquals(result.get("TotalLiabilityPremium"), RaterReportWriter.NOT_APPLICABLE, "in neither");
        assertTrue(Files.exists(dir.resolve("ui-outputs.json")));

        List<Line> failed = UiApiValidationRunner.oneCase(s, tc, "HO3", api, c -> UiResult.failed("Login page did not load", null),
            outputPaths, rowOf, false, dir);
        assertEquals(failed.size(), 1);
        assertEquals(failed.get(0).result(), RaterReportWriter.FAIL);
        assertTrue(failed.get(0).remarks().contains("Login page"));
    }
}
