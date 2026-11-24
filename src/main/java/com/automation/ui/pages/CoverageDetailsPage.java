package com.automation.ui.pages;

import com.automation.ui.support.FormFiller;
import com.automation.ui.support.FormFiller.Field;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Coverage Details of a New Business submission (locators recorded from the RT UI on rtconnectorpldev, 17 Nov 2026).
 * Form Type first (it builds the rest of the page), then the primary coverages / deductibles, then the optional coverages
 * (collapsed section, Included / Not Included). Values: the API request (ui-input-mapping.properties), the UI: columns,
 * default.&lt;key&gt;. Ends with Save Coverage and Next (to Carrier Selection).
 */
@Slf4j
public class CoverageDetailsPage {

    // ids: the current dev UI first, then the earlier one ("new|old"); recorded 23 Nov 2026
    private static final List<Field> PRIMARY = List.of(
        Field.radio("includeWind", "IncludeWind"),
        Field.dropdown("dwellingValuation", "DwellingValuation"),
        Field.dropdown("ppValuation", "PersonalPropertyValuation"),
        Field.input("coverage_A", "DwellingCoverage.Limit"),
        Field.input("coverage_B", "OtherStructureCoverage.Limit"),
        Field.dropdown("coverage_C", "PersonalPropertyCoverage_dt_Limit"),
        Field.input("coverage_D", "LossOfUseCoverage.Limit"),
        Field.dropdown("liabilityLimit", "PersonalLiabilityCoverageL_dt_Limit"),
        Field.dropdown("medPayLimit", "MedicalPaymentsCoverageM_dt_Limit"),
        Field.dropdown("aopDeductible", "DwellingCoverage_dt_AopPolicyDeductibleAmount"),
        Field.dropdown("windDeductible", "DwellingCoverage_dt_WindDeductibleType"),
        Field.dropdown("covBDeductible", "OtherStructureCoverage_dt_OtherStructureCoverageAmount|OtherStructureCoverageAmount"),
        Field.dropdown("replacementCostOnContents", "PersonalPropertyCoverage_dt_ReplacementCostonContents|ReplacementCostonContents"),
        Field.dropdown("covCDeductible", "PersonalPropertyCoverage_dt_PersonalPropertyAmount|PersonalPropertyAmount"),
        Field.dropdown("liabilityType", "PersonalLiabilityCoverageL_dt_LiabilityType"));

    private static final List<Field> OPTIONAL = List.of(
        Field.dropdown("extendedReplacementCost", "ExtendedReplacementCost_dt_CoverageSelected|ExtendedReplacementCostMap"),
        Field.dropdown("identityFraud", "IdentityFraudCoverage_dt_CoverageSelected|IdentityFraudCoverageMap"),
        Field.dropdown("increasedLossAssessment", "IncreasedLossAssessment_dt_CoverageSelected|IncreasedLossAssessmentMap"),
        Field.dropdown("limitedMold", "LimitedMoldCoverage_dt_CoverageSelected|LimitedMoldCoverageMap"),
        Field.dropdown("personalInjury", "PersonalInjuryCoverage|PersonalInjuryCoverageMap"),
        Field.dropdown("waterBackup", "WaterBackupCoverage_dt_CoverageSelected|WaterBackupCoverageMap"),
        Field.dropdown("waterDamageDeductible", "WaterDamageDeductible_dt_CoverageSelected|WaterDamageDeductibleMap"),
        Field.dropdown("theftDeductible", "TheftBurglaryDeductible_dt_CoverageSelected|TheftBurglaryDeductibleMap"));

    private final Page page;

    public CoverageDetailsPage(Page page) {
        this.page = page;
    }

    public void fill(Map<String, String> data) {
        FormFiller form = new FormFiller(page, "Coverage Details");
        Locator formType = page.locator("//div[@id='SublineName']").first();
        formType.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(180_000));
        // Form Type = the API's subline (HO3 / HO5 / HO6 / HO8); the coverages are built for it
        form.fill(List.of(Field.dropdown("Policy_Form", "SublineName")), data);
        page.locator("//input[@name='DwellingCoverage.Limit']").first()
            .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(120_000));
        form.fill(PRIMARY, data);
        form.expandSections();
        form.fill(OPTIONAL, data);
        // every required field must have a value, and Save Coverage must confirm, before Next
        form.requireFilled();
        form.saveAndConfirm("//button[@name='SaveCoverage']", "Saved Successfully");
        form.press("//button[@name='next']");
        form.logMessages("after Next");
    }
}
