package com.automation.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One test case handed to the UI flow.
 *
 * @param testCaseId      the TestCaseId / iteration id of the test data
 * @param configuration   the rated configuration, e.g. HO3, HO6, DP1_BUILDERS_RISK (Subline + Occupancy Type)
 * @param transactionType "New_Business" or "Renewal" (test data row 14)
 * @param inputs          the values sent to the API for this case, keyed by API attribute name (e.g. "SublineName",
 *                        "Zipcode1", "WindDeductiblePercentage"); an attribute name used at several places of the request is
 *                        keyed "Parent.Name" (e.g. "PLWindCoverage.CoverageSelected") - see ui-attributes.txt of the run
 * @param outputPaths     the output attributes compared with the API response: key (API attribute name, "Parent.Name" when
 *                        the name is not unique, e.g. "BasePremium", "PLNonWind.Premium") -> its JsonPath in the API
 *                        response. Return the keys the UI shows; the others are reported N/A
 * @param requestJson     the full API request body that was sent (token masked), for anything the inputs map does not hold
 * @param caseDir         the iteration folder of the run (request.json / response.json are there); save screenshots here
 */
public record UiCase(String testCaseId, String configuration, String transactionType, Map<String, String> inputs,
                     Map<String, String> outputPaths, String requestJson, Path caseDir) {

    /** The input value of an API attribute, or "" when the request has none. */
    public String input(String attribute) {
        return inputs.getOrDefault(attribute, "");
    }

    /** The output attribute keys to return (see {@link #outputPaths()}). */
    public List<String> outputKeys() {
        return List.copyOf(outputPaths.keySet());
    }

    public boolean isRenewal() {
        return "Renewal".equalsIgnoreCase(transactionType);
    }
}
