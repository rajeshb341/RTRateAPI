package com.automation.ui.pages;

import com.automation.ui.support.UiActions;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Risk Details (Location 1) of a New Business submission - locators recorded from the current RT UI (UiExplorer, 17 Nov 2026).
 * Each field is filled only when it is on the page and the iteration has a value for it (API request values via
 * config/ui-input-mapping.properties, else the UI:&lt;field&gt; column); otherwise it is left as the UI pre-fills it.
 * Ends with Next (to Coverage Details).
 */
@Slf4j
public class RiskDetailsPage extends UiActions {

    /** data key -> element: kind D = dropdown (div#id), I = input (name), R = radio (name, value = data). */
    private record Field(String key, char kind, String id) {}

    private static final List<Field> FIELDS = List.of(
        // Property Details
        new Field("Occupancy_Type", 'D', "Occupancy"),
        new Field("residenceType", 'D', "ResType"),
        new Field("Year_Built", 'I', "DateBuilt"),
        new Field("Sq_Ft", 'I', "SquareFt"),
        new Field("noOfStories", 'D', "NumberOfStories"),
        new Field("hasHomeGutted", 'D', "IsNewConstructionOrRenovation"),
        new Field("remodeledYear", 'I', "YearUpgraded"),
        new Field("isBuildingOverWater", 'D', "BuildingOverWater"),
        // Additional Property Details
        new Field("homeConsistMobileOrManufacturedHome", 'D', "PremisesConsistMobileHome"),
        new Field("isWorkingFarmOrRanchProperty", 'D', "FarmRanch"),
        new Field("StudentHousing", 'D', "StudentHousing"),
        new Field("MoreThan4UnrelatedIndividualsPerUnit", 'D', "MoreThan4UnrelatedIndividualsPerUnit"),
        new Field("MoreThan2MortgageesOnAnySingleLocation", 'D', "MoreThan2MortgageesOnAnySingleLocation"),
        new Field("MoreThan4LossesInLastThreeYears", 'D', "MoreThan4LossesInLastThreeYears"),
        new Field("DisallowedCircuitBreaker", 'D', "DisallowedCircuitBreaker"),
        new Field("PropertyUsedForHuntingOtherThanInsured", 'D', "PropertyUsedForHuntingOtherThanInsured"),
        new Field("PropertyBeDemolishedDuringPolicyPeriod", 'D', "PropertyBeDemolishedDuringPolicyPeriod"),
        new Field("IsDwellingUsedAsBoardingHome", 'D', "IsDwellingUsedAsBoardingHome"),
        new Field("AwareOfChineseDrywall", 'D', "AwareOfChineseDrywall"),
        new Field("HasUnremediatedMoldIssue", 'D', "HasUnremediatedMoldIssue"),
        new Field("DoesLocationHaveOtherStructuresRentedToOthersResidence", 'D', "DoesLocationHaveOtherStructuresRentedToOthersResidence"),
        new Field("KnobAluminumCloth", 'D', "KnobAluminumCloth"),
        new Field("AutoWaterShutOff", 'D', "AutoWaterShutOffDeviceOperational"),
        new Field("specHome", 'D', "DeveloperSpecHome"),
        // Previous Insurance
        new Field("previousInsurance", 'D', "PriorInsurance"),
        new Field("lapseReason", 'I', "ReasonForLapse"),
        new Field("coverageSince", 'I', "LapseInCoverage"),
        new Field("insuranceCompanyName", 'I', "InsuranceCompanyName"),
        // Protection Class (PPC)
        new Field("protectClass", 'D', "ProtectionClass"),
        new Field("respondingFireStation", 'I', "RespondingFireStation"),
        new Field("distFireDepartment", 'I', "FireDeptResTime"),
        new Field("fireStationName", 'I', "FireStation"),
        new Field("fireStation", 'D', "FireStationDistance"),
        new Field("fireHydrant", 'D', "FireHydrantDistance"),
        // Construction
        new Field("constType", 'D', "ConstructionType"),
        new Field("plumbingType", 'D', "PlumbingType"),
        new Field("electricPanelType", 'D', "ElectricServiceType"),
        new Field("builtPriorTo1998", 'R', "BuiltPriorTo1998"),
        new Field("wiringType", 'D', "AluminumKnobTubeFuseWiring"),
        new Field("aluminumWiringPigtailed", 'D', "IsAluminumWiringPigTailed"),
        new Field("centralHeatingSystem", 'D', "IsCentralHeatingSystem"),
        new Field("primaryHeatSource", 'D', "HeatSource"),
        new Field("secondaryHeatSource", 'D', "AlternateHeatingType"),
        new Field("claddingType", 'D', "CladdingType"),
        new Field("openingProtection", 'D', "NonFLOpeningProtection"),
        new Field("interiorSprinkler", 'D', "InteriorSprinkler"),
        new Field("waterHeaterLocation", 'D', "WaterHeaterLocation"),
        // Roof
        new Field("RoofType", 'D', "RoofMaterial"),
        new Field("roofShape", 'D', "RoofShape"),
        new Field("roofCover", 'D', "RoofCover"),
        new Field("roofAnchor", 'D', "RoofAnchorage"),
        new Field("roofYears", 'I', "NumberofLayers"),
        new Field("roofYearUpdated", 'I', "YearofRoof"),
        new Field("RoofValue", 'I', "RoofValue"),
        // System Update Years
        new Field("waterHeaterUpdateType", 'D', "HVACWaterHeaterUpdateType"),
        new Field("waterHeaterUpdated", 'I', "WaterHeaterUpdated"),
        new Field("supplyLineType", 'D', "SupplyLineType"),
        new Field("plumbingUpdateType", 'D', "PlumbingTypeUpdateType"),
        new Field("plumbingYearUpdated", 'I', "PlumbingUpdated"),
        new Field("electricUpdateType", 'D', "ElectricTypeUpdateType"),
        new Field("electricPanelUpdated", 'I', "ElectricUpdated"),
        new Field("wiringUpdateType", 'D', "WiringUpdateType"),
        new Field("wiringYearUpdated", 'I', "WiringUpdated"),
        new Field("heatSourceUpdateType", 'D', "HeatingSourceUpdateType"),
        new Field("primaryHeatSourceUpdated", 'I', "HeatingUpdated"));

    private final Page page;

    public RiskDetailsPage(Page page) {
        this.page = page;
    }

    /** Fills Location 1 with the iteration's data, saves the location and clicks Next. */
    public void fill(Map<String, String> data) {
        waitForPage();
        Map<String, String> values = new java.util.LinkedHashMap<>(data);
        roofUpdatedYear(values);
        // FormFiller: a value the UI does not offer closes the dropdown again and is reported, the page goes on
        com.automation.ui.support.FormFiller form = new com.automation.ui.support.FormFiller(page, "Risk Details");
        form.fill(FIELDS.stream().map(f -> new com.automation.ui.support.FormFiller.Field(f.key(), f.kind(), f.id())).toList(), values);

        // every required field must have a value, and the location Save must confirm, before Next
        form.requireFilled();
        if (isShown(page.locator("//button[@name='LocationSave']"))) {
            form.saveAndConfirm("//button[@name='LocationSave']", "Saved Successfully");
        }
        // Next: the environment can be slow after Save (press waits up to 2 minutes for the button)
        form.press("//button[@name='SaveandNext']");
        logToast("after Next");
    }

    /** Logs the application's message toast, if one is shown (e.g. "Please save the primary location details"). */
    private void logToast(String when) {
        Locator toast = page.locator("//div[contains(@class,'Toastify__toast-body')]");
        try {
            toast.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3000));
            log.warn("Risk Details {}: {}", when, toast.first().innerText().trim());
        } catch (PlaywrightException none) {
            // no message
        }
        // required fields the page marks as invalid
        for (Locator err : page.locator("//p[contains(@class,'Mui-error')]").all()) {
            try {
                if (err.isVisible()) log.warn("Risk Details {}: field error '{}'", when, err.innerText().trim());
            } catch (PlaywrightException ignore) {
                // gone
            }
        }
    }

    /**
     * Roof Updated Year from the API's Age of Roof: effective year - age (the request sends the age in years, e.g. 11;
     * a Rater band "0 - 5 Years" / "6 - 10 Years" / "11 or more Years" counts as 3 / 8 / 15 years).
     */
    private static void roofUpdatedYear(Map<String, String> values) {
        String age = values.getOrDefault("AgeOfRoof", "").trim();
        String date = values.getOrDefault("PolicyEffectiveDate", "");
        if (age.isBlank() || date.length() < 4) return;
        int year;
        int years;
        try {
            year = Integer.parseInt(date.substring(date.length() - 4));
            years = age.matches("\\d+") ? Integer.parseInt(age) : age.startsWith("0") ? 3 : age.startsWith("6") ? 8 : 15;
        } catch (NumberFormatException e) {
            return;
        }
        values.put("roofYearUpdated", String.valueOf(year - years));
    }

    private void waitForPage() {
        loaderValidation(page);
        page.locator("//div[@id='Occupancy']").first()
            .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(120_000));
    }

    private static boolean isShown(Locator locator) {
        try {
            return locator.count() > 0 && locator.first().isVisible();
        } catch (PlaywrightException e) {
            return false;
        }
    }
}
