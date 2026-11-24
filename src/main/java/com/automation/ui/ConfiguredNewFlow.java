package com.automation.ui;

import com.automation.ui.pageobjects.AgencyAgentPO;
import com.automation.ui.pages.AgencyAndAgent;
import com.automation.ui.pages.CarrierSelectionPage;
import com.automation.ui.pages.CoverageDetailsPage;
import com.automation.ui.pages.HomePage;
import com.automation.ui.pages.InsuredDetailsHybridConfig;
import com.automation.ui.pages.RiskDetailsPage;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * The "ConfiguredNew" market New Business flow after login: Home (office / underwriter) -> Agency &amp; Agency User ->
 * Insured / LOB Details (Configured-New market) -> Risk Details (Location 1) -> Coverage Details -> Carrier Selection.
 * Home, Agency and Insured come from the reference project NonConfigUpdatedInspectionUpdate; Risk Details was mapped from
 * the current UI. Coverage Details and Carrier Selection are being mapped (the flow ends after Risk Details until then).
 * Data: the UI fields of one iteration (RtUiRatingFlow.uiInput).
 */
@Slf4j
public final class ConfiguredNewFlow {
    private final Page page;
    /** The last step completed (for the report of how far a case got). */
    private String reached = "Login";
    /** The UI's answer to Get Available Market when it shows no markets (e.g. "No policy forms available ..."), else "". */
    private String marketsMessage = "";

    /** true = the flow ends once Get Available Market is clicked (ui.flow.stopAt=markets). */
    private final boolean stopAtMarkets;

    public ConfiguredNewFlow(Page page) {
        this(page, false);
    }

    public ConfiguredNewFlow(Page page, boolean stopAtMarkets) {
        this.page = page;
        this.stopAtMarkets = stopAtMarkets;
    }

    public String reached() {
        return reached;
    }

    public String marketsMessage() {
        return marketsMessage;
    }

    /** Runs the flow with the UI fields of one iteration; returns the quote number shown on the page (or ""). */
    public String run(Map<String, String> data) {
        String id = data.getOrDefault("TestCaseId", "");

        new HomePage(page).selectOfficeDetails(data);
        log.info("{}: Home page done (office {}, underwriter {})", id, data.get("OfficeName"), data.get("AssignedUnderwriter"));
        reached = "Home";

        new AgencyAndAgent(page).fillAgency(data, new AgencyAgentPO(page));
        log.info("{}: Agency & Agency User done", id);
        reached = "Agency & Agency User";

        new InsuredDetailsHybridConfig(page).ConfigNewInsuredDetails(data);
        log.info("{}: Insured / LOB Details done", id);
        reached = "Insured / LOB Details";

        new RiskDetailsPage(page).fill(data);
        log.info("{}: Risk Details done", id);
        reached = "Risk Details (saved)";

        new CoverageDetailsPage(page).fill(data);
        log.info("{}: Coverage Details done", id);
        reached = "Coverage Details (saved)";

        marketsMessage = new CarrierSelectionPage(page).getAvailableMarkets(data);
        reached = "Carrier Selection - Get Available Market clicked";
        log.info("{}: Carrier Selection - available markets requested{}", id,
            marketsMessage.isEmpty() ? "" : " - the UI says: " + marketsMessage);
        // ui.flow.stopAt=markets: the case is done here (markets / quote not yet possible on the environment)
        if (stopAtMarkets) {
            return "";
        }
        if (!marketsMessage.isEmpty()) {
            throw new IllegalStateException("Carrier Selection: no quote possible - the UI says '" + marketsMessage + "'");
        }

        // markets -> quote -> issue policy: being mapped from the UI
        return "";
    }
}
