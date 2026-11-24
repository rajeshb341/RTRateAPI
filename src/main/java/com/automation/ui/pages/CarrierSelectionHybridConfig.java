package com.automation.ui.pages;

import com.automation.ui.support.UiActions;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Carrier Selection (ported from the reference project NonConfigUpdatedInspectionUpdate,
 * Pages.HybridConfig.CarrierSelectionHybridConfig): effective date, term, billing / quote / loss settlement type,
 * Coverage A, Find Available Markets, policy form, carrier + contract, Adjust Coverages.
 * Left out: the reference's performance timing and subjectivity-list validation (no effect on rating).
 */
@Slf4j
public class CarrierSelectionHybridConfig extends UiActions {

    private final Page page;

    private final Locator term;
    private final Locator billType;
    private final Locator lossSettlementType;
    private final Locator quoteType;
    private final Locator selBtn;
    private final Locator adjustCoverages;
    private final Locator dp3Policy, ho3Policy, ho8Policy, ho5Policy, dp1Policy, excessFlood, ho4Policy, ho6Policy;
    private final Locator exclWindHail;
    private final Locator covA;
    private final Locator carSelectionDropDown;
    private final Locator conSelectionDropDown;
    private final Locator quoteNo;

    public CarrierSelectionHybridConfig(Page page) {
        this.page = page;
        term                 = page.locator("//div[@id='TermInMonths']");
        billType             = page.locator("//div[@id='BillingType']");
        lossSettlementType   = page.locator("//div[@id='PolicyformOption']");
        quoteType            = page.locator("//div[@id='QuoteType']");
        selBtn               = page.locator("//button[@name='FindAvailableMarkets']");
        adjustCoverages      = page.locator("//a[@name='AdjustCoverages']");
        dp3Policy            = page.locator("//input[@value='DP3']");
        ho3Policy            = page.locator("//input[@value='HO3']");
        ho8Policy            = page.locator("//input[@value='HO8']");
        ho5Policy            = page.locator("//input[@value='HO5']");
        dp1Policy            = page.locator("//input[@value='DP1']");
        excessFlood          = page.locator("//input[@value='Excess Flood']");
        ho4Policy            = page.locator("//input[@value='HO4']");
        ho6Policy            = page.locator("//input[@value='HO6']");
        exclWindHail         = page.locator("//*[@name='ExcludeWind']");
        covA                 = page.locator("(//input[@name='DwellingCoverage'])[1]");
        carSelectionDropDown = page.locator("#CarrierDisplayName");
        conSelectionDropDown = page.locator("#ContractName");
        quoteNo              = page.locator("//label[text()='Quote Number -']//following-sibling::p");
    }

    public void selectCarrier(Map<String, String> inputData) {

        // 1. Policy effective date
        loaderValidation(page);
        Locator policyEffDateInput = page.locator("//*[@name='PolicyEffectiveDate']");
        scrollAndMove(page, policyEffDateInput);
        waitToBeVisible(page, policyEffDateInput);
        policyEffDateInput.click();
        policyEffDateInput.selectText();
        page.keyboard().press("Backspace");
        policyEffDateInput.fill("");
        enterData(page, policyEffDateInput, inputData.get("PolicyEffectiveDate"));
        loaderValidation(page);
        log.info("PolicyEffectiveDate entered = {}", inputData.get("PolicyEffectiveDate"));

        // 2. Term, Billing Type, Quote Type, Loss Settlement
        lobSelection(page, term, inputData.get("Term"));
        lobSelection(page, billType, inputData.get("Billing_Type"));
        lobSelection(page, quoteType, inputData.get("Quote_Type"));
        lobSelection(page, lossSettlementType, inputData.get("LossSettlement_Type"));

        // 3. Coverage A
        click(page, covA);
        while (!covA.inputValue().isEmpty()) {
            covA.press("Backspace");
        }
        enterData(page, covA, inputData.get("coverage_A"));
        page.waitForTimeout(2000);

        // 4. Houston - Exclude Wind/Hail
        if ("Houston".equals(inputData.get("OfficeName")) && "Yes".equals(inputData.get("Exclude_Wind_Hail"))) {
            clickCheckBox(page, exclWindHail);
        }

        // 5. Find Available Markets
        scrollAndMove(page, selBtn);
        click(page, selBtn);
        page.waitForTimeout(5000);

        // 6. Policy form
        String policyForm = inputData.get("Policy_Form");
        switch (policyForm == null ? "" : policyForm) {
            case "HO3" -> click(page, ho3Policy);
            case "HO8" -> click(page, ho8Policy);
            case "DP3" -> click(page, dp3Policy);
            case "HO5" -> click(page, ho5Policy);
            case "DP1" -> click(page, dp1Policy);
            case "HO4" -> click(page, ho4Policy);
            case "HO6" -> click(page, ho6Policy);
            case "ExcessFlood" -> click(page, excessFlood);
            default -> log.warn("No matching policy form found for: {}", policyForm);
        }

        // 7. Carrier and Contract
        loaderValidation(page);
        page.waitForTimeout(2000);
        try {
            lobSelection(page, carSelectionDropDown, inputData.get("CarrierName"));
            loaderValidation(page);
            page.waitForTimeout(2000);
            lobSelection(page, conSelectionDropDown, inputData.get("ContractName"));
        } catch (Exception e) {
            log.warn("Carrier not appeared: {}", e.getMessage());
        }

        // 8. Adjust Coverages
        loaderValidation(page);
        click(page, adjustCoverages);
        loaderValidation(page);
    }

    /** The quote number shown on the page, or "" when it is not shown. */
    public String quoteNumber() {
        return quoteNo.count() > 0 ? quoteNo.first().textContent().trim() : "";
    }
}
