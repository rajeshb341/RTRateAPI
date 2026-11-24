package com.automation.ui.pages;

import com.automation.ui.pageobjects.AgencyAgentPO;
import com.automation.ui.support.UiActions;
import com.github.javafaker.Faker;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * New submission: Quote Now -> Add/Find Insured -> Agency -> Insured type, names (Faker), DOB, phone, mailing address ->
 * Next (ported from the reference project NonConfigUpdatedInspectionUpdate, Pages.NonConfigured.AgencyAndAgent;
 * the reference's recording of values for the issuance PDF checks is left out).
 */
@Slf4j
public class AgencyAndAgent extends UiActions {

    private final Page page;

    public AgencyAndAgent(Page page) {
        this.page = page;
    }

    /** Fills the agency / insured section and clicks Next; returns the State the UI filled from the address. */
    public String fillAgency(Map<String, String> map, AgencyAgentPO AgencyPO) {

        // ── Quote Now ─────────────────────────────────────────────────────────
        Locator quoteNow = page.locator("//div[contains(@class,'QuoteNewSubmissionHome__wrapperClass')]//button");
        quoteNow.click();
        loaderValidation(page);
        waitInSeconds(page, 3);

        // ── Add / Find Insured ────────────────────────────────────────────────
        AgencyPO.AddOrFindIns(map.get("InsuredSelection")).click();
        loaderValidation(page);

        // ── Agency autocomplete ───────────────────────────────────────────────
        agencySelect(page, AgencyPO.AgencyIp, map.get("Agency_name"));
        loaderValidation(page);

        for (int i = 1; i <= 2; i++) {
            scrollAndMove(page, AgencyPO.indvidualFname);
            waitInSeconds(page, 2);
        }

        // ── Insured Type ──────────────────────────────────────────────────────
        dropDownFunction(page, AgencyPO.insType, map.get("Insured_Type"));
        loaderValidation(page);

        scrollAndMove(page, AgencyPO.insAddLine1);
        waitInSeconds(page, 2);

        // ── Names (generated) ─────────────────────────────────────────────────
        Faker faker = new Faker();
        String firstName = faker.name().firstName();
        String lastName = faker.name().lastName();
        String insType = map.get("Insured_Type");

        if ("Individual".equals(insType)) {
            enterData(page, AgencyPO.indvidualFname, firstName);
            enterData(page, AgencyPO.insLname, lastName);
            enterData(page, AgencyPO.dob, map.get("Date_Of_Birth"));
            enterPhoneNumber(page, AgencyPO.phNo, map.get("Phone_Number"));
            selectAddress(page, AgencyPO.insAddr, map.get("Insured_Address"));
        }
        if ("LLC/LLP/Corporation".equals(insType)) {
            enterData(page, AgencyPO.insFname, firstName);
        }
        if ("Trust/Estate".equals(insType)) {
            enterData(page, AgencyPO.trusteeFname, firstName);
        }

        enterData(page, AgencyPO.insCntFname, map.get("InspectionContactFirstName"));
        enterData(page, AgencyPO.insCntLname, map.get("InspectionContactLastName"));
        enterPhoneNumber(page, AgencyPO.phNo, map.get("Phone_Number"));

        selectAddress(page, AgencyPO.insAddr, map.get("Insured_Address"));

        String stateFromUI = page.locator("input[placeholder='State *']").inputValue();
        log.info("Insured {} {} - State from the address: {}", firstName, lastName, stateFromUI);

        // ── Next ──────────────────────────────────────────────────────────────
        scrollToEnd(page, AgencyPO.nextBtn);
        click(page, AgencyPO.nextBtn);
        waitInSeconds(page, 2);
        return stateFromUI;
    }
}
