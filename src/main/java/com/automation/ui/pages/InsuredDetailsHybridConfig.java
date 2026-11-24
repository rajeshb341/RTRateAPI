package com.automation.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import com.automation.ui.support.UiActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Insured Details tile, ConfiguredNew market (ported from the reference project NonConfigUpdatedInspectionUpdate,
 * Pages.HybridConfig.InsuredDetailsHybridConfig - only {@link #ConfigNewInsuredDetails} is used here).
 * An ineligible applicant throws IllegalStateException (the reference stopped the TestNG test).
 */
@Slf4j
public class InsuredDetailsHybridConfig extends UiActions {

    private final Page page;

    // ── Static Locators ───────────────────────────────────────────────────────
    private final Locator spouseMidName , saveNxtBtn,saveNxtBtnIndex,InsTileNextBtn,BankruptcyType, BankruptcyDischarge, BankruptcyReason, foreClosureDescribe,foreClosureYear, companyCancelledNonRenewedDescription, insuredType , saveNxt, locationText, inEligibleApplicant, prevMailingAddress, selectProduct, spouseFirstName, spouseLastName, spouseDob, spouseOccupation, MaritalStatus ,phoneNo, configNewDob ,mailingAddressYesOrNoOption, addLine2, insTileNextBtn, insPrevMailAddress, mailingAddressYesOrNo, phNo, configMarketRB, hybridMarketRB, locationAddress, lob, configNewMarket, saveAndNextBtn, OccupationDropdown;


    // ── Constructor ───────────────────────────────────────────────────────────
    public InsuredDetailsHybridConfig(Page page)
    {
        this.page = page;
        configMarketRB              = page.locator("//input[@value='Configured']/ancestor::label");
        hybridMarketRB              = page.locator("//input[@value='HybridConfigured']/ancestor::label");
        configNewMarket             = page.locator("//input[@value='Configured-New']/ancestor::label");
        locationAddress             = page.locator("//input[@placeholder='Enter Risk Address *']");
        phNo                        = page.locator("//input[@name='InspectionContactFirstName']");
        mailingAddressYesOrNo       = page.locator("(//*[@id='MailingAddressUsedForAtleastSixMonths'])[2]");
        mailingAddressYesOrNoOption = page.locator("#MailingAddressUsedForAtleastSixMonths");
        insPrevMailAddress          = page.locator("[name='InsuredsPreviousMailingAddress']");
        lob                         = page.locator("#LOB");
        saveNxtBtn = page.locator("(//button[@name='SaveandNext'])[2]");
        saveNxtBtnIndex = page.locator("//button[@name='SaveandNext']");
        InsTileNextBtn    = page.locator("//button[@name='nextButton']");
         //button[@name='nextButton']
        //button[@name='HomeOwners']
        //(//button[@name='SaveandNext'])[2]
        saveNxt = page.locator("(//button[@name='SaveandNext'])[2]");
        insTileNextBtn              = page.locator("//button[@name='nextButton']");
        addLine2                    = page.locator("(//input[@name='StreetNameAddress2'])[2]");
        saveAndNextBtn              = page.locator("(//button[@name='SaveandNext'])[2]");
        OccupationDropdown          = page.locator("//div[@id='ApplicantsOccupation']");
        configNewDob                = page.locator("(//div[@id='ApplicantsOccupation']/following::input[@name='Dob'])[1]");
        phoneNo                     = page.locator("(//div[@id='ApplicantsOccupation']/following::input[@name='PhoneNumber'])[1]");
        MaritalStatus               = page.locator("(//div[@id='ApplicantsOccupation']/following::div[@id='MaritalStatus'])[1]");
        spouseFirstName             = page.locator("(//p[text()='Spouse First Name *']/following::input[@name='FirstName'])[1]");
        spouseLastName              = page.locator("(//p[text()='Spouse First Name *']/following::input[@name='LastName'])[1]");
        spouseDob                   = page.locator("(//p[text()='Spouse Date of Birth']/following::input)[1]");
        spouseOccupation            = page.locator("//label[text()='Spouse Occupation *']/following::div[@id='Occupation']");
        selectProduct               = page.locator("//div[@id='InsuranceType']");
        prevMailingAddress          = page.locator("//p[text()='Previous mailing address(Street Number, Street Name, State, Zip Code) *']/following::input[@name='InsuredsPreviousMailingAddress']");
        inEligibleApplicant         = page.locator("//div[text()='The applicant is not eligible based on the information provided']");
        locationText                = page.locator("//h6[text()='Location']");
        insuredType                 = page.locator("//div[@id='InsuredType']");
        companyCancelledNonRenewedDescription = page.locator("//input[@name='DescribeCompanyCancelled']");
        foreClosureDescribe         = page.locator("//input[@name='Describe']");
                foreClosureYear     = page.locator("//input[@name='YearofForeclosure']");
        BankruptcyType              = page.locator("//div[@id='Type'])[2] => dropdownField");
        BankruptcyDischarge         = page.locator("//div[@id='DischargedClosed'] => dropdownField");
        BankruptcyReason            = page.locator("//input[@name='ReasonforBankruptcy'] => inputField");
        spouseMidName = page.locator("//input[@name='MiddleName']");
    }

    // ── Dynamic Locators ──────────────────────────────────────────────────────

    /** Mailing address suggestion dropdown option by value */
    private Locator mailingAddressSuggestion(String yesOrNo) {
        return page.locator("//li[@data-value='" + yesOrNo + "']");
    }

    private Locator mailingAddressSixMonths(String yesOrNo) {
        return page.locator("(//label[text()='Has the mailing address been used by the insured for at least six months?' and contains(@class,'mandatory')]/following::input[@value='"+yesOrNo+"'])[1]/ancestor::label");
    }

    private Locator financialLegalHistory(String yesOrNo) {
        return page.locator("(//label[text()='Has the applicant, or anyone with a financial interest in the property, filed for, been involved with or convicted of any of the following within the last 5 years? (Bankruptcy / Repossession / Foreclosure / Arson / Fraud / Other crimes related to a loss on the property)' and contains(@class,'mandatory')]/following::input[@value='"+yesOrNo+"'])[1]/ancestor::label");
    }


    /** True when the element is on the page and visible now (no waiting; an invalid locator counts as not shown). */
    private static boolean isShown(Locator locator) {
        try {
            return locator.count() > 0 && locator.first().isVisible();
        } catch (PlaywrightException e) {
            return false;
        }
    }

    // Locators above return the wrapping <label> of a MUI radio; they only find it, so click it here.
    private void clickRadio(Locator radioLabel) {
        radioLabel.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        radioLabel.scrollIntoViewIfNeeded();
        radioLabel.click();
    }

    private Locator companyCancelledNonRenewed(String yesOrNo)
    {
        return page.locator("(//label[text()='Has any company cancelled, nonrenewed, or refused coverage to applicant?']/following::input[@value='"+yesOrNo+"'])[1]/ancestor::label");
    }

    private Locator convictedFraud(String yesOrNo) {
        return page.locator("(//label[text()='Has applicant ever been convicted of arson, fraud or another felony?']/following::input[@value='"+yesOrNo+"'])[1]/ancestor::label");
    }

    private Locator insuredWithin5Years(String yesOrNo) {
        return page.locator("//label[text()='Please select all that apply to the insured within the last 5 years:']/following::input[@name='"+yesOrNo+"']");
    }

    /**
     * Escapes regex metacharacters one at a time (portable across both
     * java.util.regex and whatever engine actually evaluates a Playwright
     * assertion's Pattern) — unlike Pattern.quote(), which relies on Java-only
     * "\Q...\E" syntax that doesn't survive being shipped to Playwright's driver.
     */
    private static String escapeForRegex(String raw) {
        StringBuilder escaped = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    // ── Action Method ─────────────────────────────────────────────────────────

    public void ConfigNewInsuredDetails(Map<String, String> inputData) {
        // The insured page loads behind the application's spinner (not the custom-loader-container), which can take
        // longer than Playwright's default 30 s: wait up to 120 s, and say which markets are offered when it never shows
        try {
            configNewMarket.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(120_000));
        } catch (PlaywrightException e) {
            List<String> markets = page.locator("//input[@type='radio']").all().stream()
                .map(r -> r.getAttribute("value")).toList();
            throw new IllegalStateException("Market option 'Configured-New' not shown within 120 s; radio options on the page: "
                + markets, e);
        }
        configNewMarket.click();

        loaderValidation(page);

//        selectLiabilityType(page, inputData.get("ConfigNewInsured_Type"), insuredType);

        String InsuredType = inputData.get("ConfigNewInsured_Type");

        String MarriedStatus = inputData.get("MaritalStatus");

        switch (InsuredType) {
            case "Individual":
                selectLiabilityType(page, inputData.get("OccupationType"), OccupationDropdown);
                enterData(page, configNewDob, inputData.get("Date_Of_Birth"));
                enterPhoneNumber(page, phoneNo, inputData.get("Phone_Number"));
                // Email * is required by the current UI (not in the reference flow): UI:Email, else a test address
                Locator email = page.locator("(//div[@id='ApplicantsOccupation']/following::input[@name='EmailAddress'])[1]");
                if (isShown(email) && email.inputValue().isBlank()) {
                    String mail = inputData.get("Email");
                    enterData(page, email, mail == null || mail.isBlank() ? "insured.test@example.com" : mail);
                }
                selectLiabilityType(page, inputData.get("MaritalStatus"), MaritalStatus);

                if (MarriedStatus.equals("Married")) {
                    enterData(page, spouseFirstName, inputData.get("spouseFname"));
                    enterData(page, spouseLastName, inputData.get("spouseLname"));
                    selectLiabilityType(page, inputData.get("spouseOccupation"), spouseOccupation);
                    enterData(page, spouseDob, inputData.get("spouseDob"));
                    enterData(page, spouseMidName, inputData.get("spouseMidName"));
                    selectLiabilityType(page, inputData.get("product"), selectProduct);
                }

                break;
            case "LLC/LLP/Corporation/Company":

                break;
            case "Trust/Estate":

                break;
        }

        locationAddress.waitFor();
        String insuredAddress = inputData.get("Insured_Address");
        locationAddress.fill(insuredAddress);

        Locator addressSuggestion = page.locator("(//div[@class='pac-item'])[1]");
        addressSuggestion.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        waitInSeconds(page, 2);
        addressSuggestion.click();

        loaderValidation(page);

        List<String> lobList01 = List.of("Dwelling Fire (Vacant Only)", "Flood", "High Value","Condo");
        String lobFromExcel = inputData.get("product");

        if (lobList01.contains(lobFromExcel))
        {
            clickRadio(mailingAddressSixMonths(inputData.get("InsuredMailingAddressYesOrNo")));
            loaderValidation(page);
            if (inputData.get("InsuredMailingAddressYesOrNo").equals("No"))
            {
                enterData(page, prevMailingAddress, inputData.get("previousMailingAddress"));
            }
            loaderValidation(page);


            clickRadio(financialLegalHistory(inputData.get("InsuredFinancial/LegalHistory")));
            loaderValidation(page);
            if (inputData.get("InsuredFinancial/LegalHistory").equals("Yes"))
            {
                boolean ApplicantIneligiblity = inEligibleApplicant.isVisible();

                if (ApplicantIneligiblity)
                {
                    log.info("Applicant Ineligible Validation appeared");
                    throw new IllegalStateException("Applicant Ineligible Validation appeared. Stopping execution.");
                } else
                {
                    log.info("Applicant Ineligible Validation not appeared");
                }
            }
        }

        if (lobFromExcel.equals("Homeowners"))
        {
            clickRadio(mailingAddressSixMonths(inputData.get("InsuredMailingAddressYesOrNo")));
            loaderValidation(page);
            if (inputData.get("InsuredMailingAddressYesOrNo").equals("No"))
            {
                enterData(page, prevMailingAddress, inputData.get("previousMailingAddress"));
            }
            loaderValidation(page);


            clickRadio(financialLegalHistory(inputData.get("InsuredFinancial/LegalHistory")));
            loaderValidation(page);
            if (inputData.get("InsuredFinancial/LegalHistory").equals("Yes"))
            {
                boolean ApplicantIneligiblity = inEligibleApplicant.isVisible();

                if (ApplicantIneligiblity)
                {
                    log.info("Applicant Ineligible Validation appeared");
                    throw new IllegalStateException("Applicant Ineligible Validation appeared. Stopping execution.");
                } else
                {
                    log.info("Applicant Ineligible Validation not appeared");
                }
            }
        }

        if (lobFromExcel.equals("Dwelling Fire"))
        {
            clickRadio(mailingAddressSixMonths(inputData.get("InsuredMailingAddressYesOrNo")));
            loaderValidation(page);
            if (inputData.get("InsuredMailingAddressYesOrNo").equals("No"))
            {
                enterData(page, prevMailingAddress, inputData.get("previousMailingAddress"));
            }
            loaderValidation(page);


            clickRadio(companyCancelledNonRenewed(inputData.get("companyCancelledNonRenewed")));
            loaderValidation(page);
            if (inputData.get("companyCancelledNonRenewed").equals("Yes"))
            {
                enterData(page, companyCancelledNonRenewedDescription, inputData.get("previousMailingAddress"));
                loaderValidation(page);
            }

            clickRadio(convictedFraud(inputData.get("convictedFraud")));
            loaderValidation(page);

            String within5YearsForClosure= inputData.get("within5YearsInsuredForClosure");
            String within5YearsBankruptcy= inputData.get("within5YearsInsuredForClosure");

            if(within5YearsForClosure.equals("Y"))
            {
                insuredWithin5Years("Foreclosure");
                loaderValidation(page);
                // Describe is filled only when the page shows it; otherwise go on with the next step
                Locator describe = page.locator("(//input[@name='Describe'])");
                if (describe.count() > 0 && describe.first().isVisible()) {
                    enterData(page, describe.first(), inputData.get("foreClosureDescribe"));
                } else {
                    log.info("Foreclosure 'Describe' field not shown - skipped");
                }
                if (isShown(foreClosureYear)) enterData(page, foreClosureYear, inputData.get("foreClosureYear"));
                else log.info("Foreclosure 'Year' field not shown - skipped");
            }

            if(within5YearsBankruptcy.equals("Y"))
            {
                insuredWithin5Years("Bankruptcy");
                loaderValidation(page);

                // filled only when the page shows them; otherwise go on with the next step
                if (isShown(BankruptcyType)) selectLiabilityType(page, inputData.get("BankruptcyType"), BankruptcyType);
                else log.info("Bankruptcy 'Type' field not shown - skipped");
                if (isShown(BankruptcyDischarge)) selectLiabilityType(page, inputData.get("BankruptcyDischarge"), BankruptcyDischarge);
                else log.info("Bankruptcy 'Discharged' field not shown - skipped");
                if (isShown(BankruptcyReason)) enterData(page, BankruptcyReason, inputData.get("BankruptcyReason"));
                else log.info("Bankruptcy 'Reason' field not shown - skipped");
            }

            clickRadio(financialLegalHistory(inputData.get("InsuredFinancial/LegalHistory")));
            loaderValidation(page);
            if (inputData.get("InsuredFinancial/LegalHistory").equals("Yes"))
            {
                boolean ApplicantIneligiblity = inEligibleApplicant.isVisible();

                if (ApplicantIneligiblity)
                {
                    log.info("Applicant Ineligible Validation appeared");
                    throw new IllegalStateException("Applicant Ineligible Validation appeared. Stopping execution.");
                } else
                {
                    log.info("Applicant Ineligible Validation not appeared");
                }
            }

        }

        loaderValidation(page);
        // Save & Next of the page footer (the reference's "[2]" no longer matches: the last such button is used)
        page.locator("//button[@name='SaveandNext']").last().click();

        pageLoadWait(page);
        loaderValidation(page);
        try {
            locationText.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(60_000));
        } catch (PlaywrightException e) {
            log.warn("'Location' heading not shown after Save & Next (page {}): continuing", page.url());
        }
    }
}