package com.automation.ui.pages;

import com.automation.ui.support.UiActions;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Home page after login (ported from the reference project NonConfigUpdatedInspectionUpdate, Pages.HomePage):
 * Home -> Change -> Office (OfficeName) -> Assigned Underwriter (AssignedUnderwriter) -> Update.
 */
@Slf4j
public class HomePage extends UiActions
{

    private final Page page;

    // ── Locators ──────────────────────────────────────────────────────────────
    public final Locator homeBtn;
    public final Locator changeBtn;
    public final Locator officeBtn;
    public final Locator underwriterDropdown;
    public final Locator updateBtn;
    public final Locator uwOption;

    public HomePage(Page page) {
        this.page = page;
        homeBtn             = page.locator("(//ul[@class='MuiList-root MuiList-padding Sidebarv4-list css-vpqctu']//li)[1]");
        changeBtn           = page.locator("//button[@name='Change']");
        officeBtn           = page.locator("//div[@id='EntitledOffice']");
        underwriterDropdown = page.locator("//input[@name='AssignedUnderwriter']");
        updateBtn           = page.locator("//button[@name='Update']");
        uwOption            = page.locator("//li[@id='AssignedUnderwriter-option-0']");
    }

    // ── Action Method ─────────────────────────────────────────────────────────
    public void selectOfficeDetails(Map<String, String> map) {
        loaderValidation(page);

        // 1. Home
        try {
            click(page, homeBtn);
        } catch (Exception e) {
            log.warn("Home button not found: url={} title={} buttons={}", page.url(), page.title(),
                page.locator("button").allInnerTexts());
            throw new IllegalStateException("Home button not found after login", e);
        }
        page.waitForTimeout(2000);

        // 2. Change -> office / underwriter panel
        click(page, changeBtn);

        // 3. Office
        String officeName = map.get("OfficeName");
        log.info("Office Name = {}", officeName);
        selectOffice(page, officeBtn, officeName);

        // 4. Assigned underwriter
        String underwriterName = map.get("AssignedUnderwriter");
        log.info("Assigned Underwriter = {}", underwriterName);
        loaderValidation(page);
        underwriterDropdown.click();
        underwriterDropdown.fill(underwriterName);
        uwOption.waitFor();
        uwOption.click();
        loaderValidation(page);

        // 5. Update
        click(page, updateBtn);
    }
}