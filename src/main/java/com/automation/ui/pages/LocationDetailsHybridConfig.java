package com.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.automation.ui.support.UiActions;

import java.util.Map;
/**
 * Location tile, ConfiguredNew market (ported from the reference project NonConfigUpdatedInspectionUpdate,
 * Pages.HybridConfig.LocationDetailsHybridConfig - {@link #ConfigNewLocation} and {@link #AdditionalPropertyDetails}).
 * Difference: the Distance-to-Coast unit (DTCUnit) radio is clicked; the reference only located it.
 */
public class LocationDetailsHybridConfig extends UiActions {

    private final Page page;

    // ── Locators ──────────────────────────────────────────────────────────────
    private final Locator occType, alarmType, Restype, waterShutOffDevice;
    private final Locator noOfUnits;
    private final Locator fireHydrant;
    private final Locator fireStation;
    private final Locator protectClass;
    private final Locator noOfStories;
    private final Locator constType, respondingFireStation, distFireDepartment;
    private final Locator sqFt;
    private final Locator yearBuilt;
    private final Locator saveBtn, saveLocation;
    private final Locator uwQues;
    private final Locator claims;
    private final Locator saveUnderwriter;
    private final Locator nxtBtn;
    private final Locator electricPanelType, toastify, waterHeaterUpdated, plumbingYearUpdated, electricPanelUpdated, wiringYearUpdated, primaryHeatSourceUpdated, waterHeaterUpdateType, plumbingUpdateType, electricUpdateType, wiringUpdateType, heatSourceUpdateType;
    private final Locator address;
    private final Locator lapseCovCalender,lapseCovOkBtn , addressLoader, unitNumber, insuranceCompanyName;
    private final Locator subSummary, plumbingType, wiringType, centralHeatingSystem, secondaryHeatSource, RoofType, bracedOrUnbraceed, slopeOfRoof;
    private final Locator addInterestText, builprior, roofYear, subNoTxt, previousInsurance, lapseReason, coverageSince, roofYearUpdated;
    private final Locator AddInformationBtn,addLine2, AddInformationType, AdditionalIntType, AdditionalIntFName, AdditionalIntLName, AddInterestAddressline1, AddInterestAddressline2,
            AddInterestCity, AddInterestState, AddInterestCounty, AddInterestZipcode, AddInterestZipcode2, AddInterestSaveBtn, AdditionalInsuredType, AddInsFName,AddInsLName, AddInsAddressline1, AddInsAddressline2, AddInsCity, AddInsState, AddInsCounty, AddInsZipcode, AddInsZipcode2,
            MortgageeFirstName, MortagageeLoanNumber,mortgegaeeSave, MortgageeAddline1, MortgageeAddline2, Mortgageecity, MortgageeState, MortgageeCounty, MortgageeZipcode, MortgageeZipcode2;
    private final Locator dtcTxt, homeConsistMobileOrManufacturedHome, isWorkingFarmOrRanchProperty, lossInFiveYears, trampoline, burglarBarsHaveQuickRelease, AutoWaterShutOff, specHome;
    private final Locator supplyLineType, alarm, hasHomeGutted, remodeledYear, isBuildingOverWater;

    // ── Constructor ───────────────────────────────────────────────────────────
    public LocationDetailsHybridConfig(Page page) {
        this.page     = page;

        lapseCovCalender = page.locator("//input[@name='LastDateOfCoverage']/following::button)[1]");
        lapseCovOkBtn = page.locator("//span[text()='OK']/parent::button");
        occType       = page.locator("//div[@id='Occupancy']");
        noOfUnits     = page.locator("//div[@id='NumberOfUnits']");
        fireHydrant   = page.locator("//div[@id='FireHydrantDistance']");
        fireStation   = page.locator("//div[@id='FireStationDistance']");
        protectClass  = page.locator("//div[@id='ProtectionClass']");
        noOfStories   = page.locator("//div[@id='NumberOfStories']");
        constType     = page.locator("//div[@id='ConstructionType']");
        sqFt          = page.locator("//input[@name='SquareFt']");
        yearBuilt     = page.locator("//input[@name='DateBuilt']");
        saveBtn       = page.locator("//button[@name='SaveBuilding']");
        uwQues        = page.locator("(//input[@value='No'])[last()]");
        claims        = page.locator("//h6[text()='Claims History']");
        saveUnderwriter = page.locator("//button[@name='SaveUnderwriter']");
        nxtBtn        = page.locator("//button[@name='nextButton']");
        toastify      = page.locator("//div[@class='Toastify__toast-body']");
        address       = page.locator("//input[@name='StreetNumberAddress1']");
        addressLoader = page.locator("//div[contains(@class, 'loader')]");
        subSummary    = page.locator("//p[contains(text(),'Submission')]");
        subNoTxt      = page.locator("//label[text()='Submission Number']");
        addLine2      = page.locator("//input[@name='StreetNameAddress2']");
        dtcTxt        = page.locator("//*[text()='Distance to Coast (Unit of Measurement)']");
        alarm         = page.locator("//*[@id='RoofToWallConnection']");
        Restype       = page.locator("//div[@id='ResType']");
        hasHomeGutted = page.locator("//div[@id='IsNewConstructionOrRenovation']");
        remodeledYear = page.locator("//input[@name='YearUpgraded']");
        isBuildingOverWater = page.locator("//div[@id='BuildingOverWater']");
        specHome        = page.locator("//div[@id='DeveloperSpecHome']");
        homeConsistMobileOrManufacturedHome        = page.locator("//div[@id='PremisesConsistMobileHome']");
        isWorkingFarmOrRanchProperty        = page.locator("//div[@id='FarmRanch']");
        lossInFiveYears        = page.locator("//input[@id='NumberOfClaims']");
        trampoline        = page.locator("//div[@id='TrampolineOnPremises']");
        burglarBarsHaveQuickRelease        = page.locator("//div[@id='RiskhaveBurglarBarsQuickReleaseMechanisms']");
        AutoWaterShutOff        = page.locator("//div[@id='AutoWaterShutOffDeviceOperational']");
        previousInsurance        = page.locator("//div[@id='PriorInsurance']");
        lapseReason        = page.locator("//input[@name='ReasonForLapse']");
        respondingFireStation        = page.locator("//input[@name='RespondingFireStation']");
        distFireDepartment        = page.locator("//input[@id='FireDeptResTime']");
        coverageSince              = page.locator("//input[@name='LapseInCoverage']");
        plumbingType = page.locator("//div[@id='PlumbingType']");
        roofYearUpdated = page.locator("//input[@id='YearofRoof']");
        supplyLineType = page.locator("//div[@id='SupplyLineType']");
        saveLocation = page.locator("//button[@name='LocationSave']");
        alarmType = page.locator("//div[@id='Alarm']");


        wiringType        = page.locator("//div[@id='AluminumKnobTubeFuseWiring']");
        centralHeatingSystem        = page.locator("//div[@id='IsCentralHeatingSystem']");
        secondaryHeatSource        = page.locator("//div[@id='AlternateHeatingType']");
        RoofType        = page.locator("//div[@id='RoofMaterial']");
        bracedOrUnbraceed              = page.locator("//div[@id='RoofBrace']");
        slopeOfRoof = page.locator("//div[@id='RoofSlope']");

        waterHeaterUpdated        = page.locator("//input[@name='WaterHeaterUpdated']");
        plumbingYearUpdated        = page.locator("//input[@name='PlumbingUpdated']");
        electricPanelUpdated        = page.locator("//input[@name='ElectricUpdated']");
        wiringYearUpdated              = page.locator("//input[@name='WiringUpdated']");
        primaryHeatSourceUpdated = page.locator("//input[@name='HeatingUpdated']");

        waterHeaterUpdateType     = page.locator("//div[@id='HVACWaterHeaterUpdateType']");
        plumbingUpdateType        = page.locator("//div[@id='PlumbingTypeUpdateType']");
        electricUpdateType        = page.locator("//div[@id='ElectricTypeUpdateType']");
        wiringUpdateType          = page.locator("//div[@id='WiringUpdateType']");
        heatSourceUpdateType      = page.locator("//div[@id='HeatingSourceUpdateType']");
        waterShutOffDevice = page.locator("//div[@id='Watershutoffdevice']");

        unitNumber = page.locator("//input[@id='UnitNumber']");
        insuranceCompanyName = page.locator("//input[@name='InsuranceCompanyName']");
        builprior = page.locator("//input[@name='BuiltPriorTo1998' and @value='No']");
        roofYear = page.locator("//input[@name='NumberofLayers']");
        electricPanelType = page.locator("//div[@id='ElectricServiceType']");
        addInterestText = page.locator("//*[text()='Additional Risk Details']");

        AddInformationType = page.locator("//div[@id='Type']");
        AdditionalIntType = page.locator("//div[@id='AdditionalInterestType']");
        AdditionalIntFName = page.locator("//input[@name='FirstName']");
        AdditionalIntLName = page.locator("//input[@name='LastName']");
        AddInterestAddressline1 = page.locator("//input[@name='LastName']/following::input[@name='StreetNumberAddress1']");
        AddInterestAddressline2 = page.locator("//input[@name='LastName']/following::input[@name='StreetNameAddress2']");
        AddInterestCity = page.locator("//input[@name='LastName']/following::input[@name='City']");
        AddInterestState = page.locator("//input[@name='LastName']/following::input[@name='StateRegionCode']");
        AddInterestCounty = page.locator("//input[@name='LastName']/following::input[@name='County']");
        AddInterestZipcode = page.locator("//input[@name='LastName']/following::input[@name='Zipcode1']");
        AddInterestZipcode2 = page.locator("//input[@name='LastName']/following::input[@name='Zipcode2']");
        AddInterestSaveBtn = page.locator("//input[@name='LastName']/following::button[@name='SaveInformation']");
        mortgegaeeSave = page.locator("//button[@name='SaveInformation']");
        AdditionalInsuredType = page.locator("//div[@id='AdditionalInterestType']");
        AddInsFName = page.locator("//input[@name='FirstName']");
        AddInsLName = page.locator("//input[@name='LastName']");
        AddInsAddressline1 = page.locator("//input[@name='LastName']/following::input[@name='StreetNumberAddress1']");

        AddInsAddressline2 = page.locator("//input[@name='LastName']/following::input[@name='StreetNameAddress2']");

        AddInsCity = page.locator("//input[@name='LastName']/following::input[@name='City']");

        AddInsState = page.locator("//input[@name='LastName']/following::input[@name='StateRegionCode']");

        AddInsCounty = page.locator("//input[@name='LastName']/following::input[@name='County']");

        AddInsZipcode = page.locator("//input[@name='LastName']/following::input[@name='Zipcode1']");

        AddInsZipcode2 = page.locator("//input[@name='LastName']/following::input[@name='Zipcode2']");

        MortgageeFirstName =  page.locator("//input[@name='FirstName']");
        MortagageeLoanNumber =  page.locator("//input[@name='LoanNumber']");

        MortgageeAddline1 =  page.locator("//input[@name='LoanNumber']/following::input[@name='StreetNumberAddress1']");

        MortgageeAddline2 =  page.locator("//input[@name='LoanNumber']/following::input[@name='StreetNameAddress2']");

        Mortgageecity =  page.locator("//input[@name='LoanNumber']/following::input[@name='City']");

        MortgageeState =  page.locator(" //input[@name='LoanNumber']/following::input[@name='StateRegionCode']");

        MortgageeCounty =  page.locator("//input[@name='LoanNumber']/following::input[@name='County']");

        MortgageeZipcode =  page.locator("//input[@name='LoanNumber']/following::input[@name='Zipcode1']");

        MortgageeZipcode2 =  page.locator(" //input[@name='LoanNumber']/following::input[@name='Zipcode2']");
        AddInformationBtn = page.locator("//h6[text()='Additional Interests/Additional Insureds & Mortgagees']//button[contains(@class,'addicon')]");

    }

    // ── Action Method ─────────────────────────────────────────────────────────
    private Locator dtcUnitSelection(String unit) {
        return page.locator("//input[@name='CoastalWaterDistanceUnits' and @value='"+unit+"']");
    }

    private Locator additionalRiskDetails(String risk) {
        return page.locator("//input[@name='"+risk+"']");
    }

    private static final String[] ADDITIONAL_RISKS = {
            "BusinessOnPremise", "DayCareonPremises", "Alarm", "RiskhaveBurglarBarsQuickReleaseMechanisms",
            "MoreThan2MortgageesOnAnySingleLocation", "IsDwellingPoorlyMaintained", "WindowsBoardedUp",
            "CoastalWaterDistance", "Acreage", "AnimalsOnPremises", "SwimmingPoolOnPremises", "HotTubYN",
            "Unsecureddwelling", "Contractofsale", "DwellingUnderRenovationorBuildersRisk",
            "PropertyBeDemolishedDuringPolicyPeriod", "IsHuntingOnProperty", "IsHistoricalHome",
            "HasSupplementalHeatingDevice", "IsPreexistingDamage", "HasFarmingExposure", "Unconventionalhome",
            "HasCastIronPlumbing", "HasPolybutylenePlumbing", "KnobAluminumCloth",
            "DwellingLocatedOnIslandNotAccessibleByCar", "ExcludeOtherStructures", "ExcludeSwimmingPools",
            "RoofLimitedLossSettlement", "RoofExclusion", "lloydsRoofExclusionSigned", "RoofDamageSublimit",
            "GeneralChangeEndorsement"};

    /**
     * Ticks every additional risk marked "Yes" in its own Excel column (header = checkbox name).
     * Blank/"No" = skipped.
     */
    private void selectAdditionalRiskDetails(Map<String, String> inputData) {
        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        for (String risk : ADDITIONAL_RISKS) {
            String val = inputData.get(risk);
            if (val != null && val.trim().equalsIgnoreCase("Yes")) {
                selected.add(risk);
            }
        }
        for (String risk : selected) {
            Locator checkbox = additionalRiskDetails(risk);
            if (checkbox.count() == 0) {
                System.out.println("⚠️ Additional risk not found on page, skipped: " + risk);
                continue;
            }
            if (!checkbox.first().isChecked()) {
                checkbox.first().click();
            }
        }
    }

    public void ConfigNewLocation(Map<String, String> inputData)
    {
        selectLiabilityType(page, inputData.get("Occupancy_Type"), occType);
        clearAndFill(page,yearBuilt,  inputData.get("Year_Built"));
        clearAndFill(page,sqFt, inputData.get("Sq_Ft"));

        selectLiabilityType(page, inputData.get("residenceType"), Restype);
        selectLiabilityType(page, inputData.get("noOfStories"), noOfStories);

        selectLiabilityType(page, inputData.get("hasHomeGutted"), hasHomeGutted);

        if(inputData.get("hasHomeGutted").equals("Yes"))
        {
            enterData(page, remodeledYear, inputData.get("remodeledYear"));
        }

        selectLiabilityType(page, inputData.get("isBuildingOverWater"), isBuildingOverWater);

        Locator dtcUnit = dtcUnitSelection(inputData.get("DTCUnit"));
        if (dtcUnit.count() > 0 && !dtcUnit.first().isChecked()) dtcUnit.first().click();   // reference: locator only, no click

        AdditionalPropertyDetails(inputData);
    }

    public void AdditionalPropertyDetails(Map<String, String> inputData)
    {
        selectLiabilityType(page, inputData.get("homeConsistMobileOrManufacturedHome"), homeConsistMobileOrManufacturedHome);
        selectLiabilityType(page, inputData.get("isWorkingFarmOrRanchProperty"), isWorkingFarmOrRanchProperty);
        enterData(page, lossInFiveYears, inputData.get("lossInFiveYears"));
        selectLiabilityType(page, inputData.get("trampoline"), trampoline);
        selectLiabilityType(page, inputData.get("burglarBarsHaveQuickRelease"), burglarBarsHaveQuickRelease);
        selectLiabilityType(page, inputData.get("AutoWaterShutOff"), AutoWaterShutOff);
        selectLiabilityType(page, inputData.get("specHome"), specHome);

        selectLiabilityType(page, inputData.get("previousInsurance"), previousInsurance);
        enterData(page, lapseReason, inputData.get("lapseReason"));
        enterData(page, coverageSince, inputData.get("coverageSince"));

        selectLiabilityType(page, inputData.get("protectClass"), protectClass);
        enterData(page, respondingFireStation, inputData.get("respondingFireStation"));
        selectLiabilityType(page, inputData.get("fireStation"), fireStation);
        selectLiabilityType(page, inputData.get("fireHydrant"), fireHydrant);
        enterData(page, distFireDepartment, inputData.get("distFireDepartment"));

        selectLiabilityType(page, inputData.get("constType"), constType);
        selectLiabilityType(page, inputData.get("plumbingType"), plumbingType);

        selectLiabilityType(page, inputData.get("electricPanelType"), electricPanelType);

        if(builprior.isVisible())
        {
            click(page, builprior);
        }
        selectLiabilityType(page, inputData.get("wiringType"), wiringType);
        selectLiabilityType(page, inputData.get("centralHeatingSystem"), centralHeatingSystem);

        selectLiabilityType(page, inputData.get("secondaryHeatSource"), secondaryHeatSource);
        selectLiabilityType(page, inputData.get("RoofType"), RoofType);

        selectLiabilityType(page, inputData.get("bracedOrUnbraceed"), bracedOrUnbraceed);
        selectLiabilityType(page, inputData.get("slopeOfRoof"), slopeOfRoof);

        clearAndFill(page, roofYear, inputData.get("roofYear"));

        enterData(page, roofYearUpdated, inputData.get("roofYearUpdated"));
        enterData(page, waterHeaterUpdated, inputData.get("waterHeaterUpdated"));
        enterData(page, plumbingYearUpdated, inputData.get("plumbingYearUpdated"));
        enterData(page, electricPanelUpdated, inputData.get("electricPanelUpdated"));
        enterData(page, wiringYearUpdated, inputData.get("wiringYearUpdated"));
        enterData(page, primaryHeatSourceUpdated, inputData.get("primaryHeatSourceUpdated"));
        selectLiabilityType(page, inputData.get("waterHeaterUpdateType"), waterHeaterUpdateType);
        if(!inputData.get("waterHeaterUpdateType").equals("None"))
        {
            selectLiabilityType(page, inputData.get("supplyLineType"), supplyLineType);
        }
        selectLiabilityType(page, inputData.get("plumbingUpdateType"), plumbingUpdateType);
        selectLiabilityType(page, inputData.get("electricUpdateType"), electricUpdateType);
        selectLiabilityType(page, inputData.get("wiringUpdateType"), wiringUpdateType);
        selectLiabilityType(page, inputData.get("heatSourceUpdateType"), heatSourceUpdateType);

        saveLocation.click();
        loaderValidation(page);
        pageLoadWait(page);

        boolean addintVisibility = addInterestText.isVisible();

        if(addintVisibility)
        {
            selectAdditionalRiskDetails(inputData);
            enterData(page, unitNumber, inputData.get("unitNumber"));
            enterData(page, insuranceCompanyName, inputData.get("insuranceCompanyName"));
        }
    }
}