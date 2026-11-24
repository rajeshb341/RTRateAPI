package com.automation.ui.pageobjects;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * Locators of the Agency / Agent and Insured section of a new submission (ported from the reference project
 * NonConfigUpdatedInspectionUpdate, PageObjects.AgencyAgentPO).
 */
public class AgencyAgentPO {

    private final Page page;

    public final Locator quoteNow;
    public final Locator AgencyIp;
    public final Locator nextBtn;
    public final Locator insAddr;
    public final Locator insType;
    public final Locator insFname;
    public final Locator indvidualFname;
    public final Locator insLname;
    public final Locator trusteeFname;
    public final Locator phNo;
    public final Locator dob;
    public final Locator address;
    public final Locator insAddLine1;
    public final Locator secFirstName;
    public final Locator secSecondName;
    public final Locator insCntFname;
    public final Locator insCntLname;

    public AgencyAgentPO(Page page) {
        this.page = page;
        quoteNow       = page.locator("//button[@name='HomeOwners']");
        AgencyIp       = page.locator("#AgencyAgentLookUpValue");
        nextBtn        = page.locator("//button[@type='submit']");
        insAddr        = page.locator("//input[@placeholder='Enter Insured Mailing Address *']");
        insType        = page.locator("//div[@id='Type']");
        insFname       = page.locator("//input[@placeholder='Name of LLC/LLP/Corporation? *']");
        indvidualFname = page.locator("//input[@placeholder='First Name *']");
        insLname       = page.locator("//input[@placeholder='Last Name *']");
        trusteeFname   = page.locator("//input[@placeholder='Name of Trust/Estate? *']");
        phNo           = page.locator("//input[@name='InspectionContactPhoneNumber']");
        dob            = page.locator("//input[@id='Dob']");
        address        = page.locator("//input[@name='StreetNumberAddress1']");
        insAddLine1    = page.locator("//input[@name='StreetNumberAddress1']");
        secFirstName   = page.locator("//input[@placeholder='First Name']");
        secSecondName  = page.locator("//input[@placeholder='Last Name']");
        insCntFname    = page.locator("//input[@name='InspectionContactFirstName']");
        insCntLname    = page.locator("//input[@name='InspectionContactLastName']");
    }

    /** The Add / Find Insured radio button by value, e.g. "Add New Insured". */
    public Locator AddOrFindIns(String value) {
        return page.locator("//input[@type='radio' and @value='" + value + "']");
    }
}
