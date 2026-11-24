package com.automation.ui.support;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Playwright helpers for the RT application pages, ported from the reference project NonConfigUpdatedInspectionUpdate
 * (utils.PropertyUtils): waits for the "custom-loader-container" overlay, scrolling, typing, clicks with a JavaScript
 * fallback, MUI dropdowns / autocompletes / date pickers. The pages of com.automation.ui.pages extend it.
 * Left out: configuration-file reading and screenshot paths (the UI validation handles those).
 */
public class UiActions {

    // ─────────────────────────────────────────────
    // ─────────────────────────────────────────────
    // Wait Helpers
    // ─────────────────────────────────────────────

    /**
     * Wait until a locator is visible (replaces waitToBeVisible).
     */
    public void waitToBeVisible(Page page, Locator locator) {
        locator.waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(30_000));
    }





    /**
     * Wait until a locator is enabled / clickable (replaces waitToBeClickable).
     */
    public void waitToBeClickable(Page page, Locator locator) {
        locator.waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(60_000));
        // Playwright's click already waits for actionability; this is an explicit guard.
    }

    // ─────────────────────────────────────────────
    // Loader Validation
    // ─────────────────────────────────────────────

    /**
     * Waits for the custom loader to disappear (replaces loaderValidation).
     */
    public void loaderValidation(Page page) {
        try {
            Locator loader = page.locator("div.custom-loader-container");
            if (loader.count() > 0 && loader.isVisible()) {
                loader.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.HIDDEN)
                        .setTimeout(60_000));
            }
        } catch (PlaywrightException e) {
            // Loader not present – safe to continue
        }
    }

    public boolean isLoaderPresent(Page page) {
        if (page == null) {
            System.out.println("Page value is null");
            return false;
        }
        try {
            Locator loader = page.locator("div.custom-loader-container");
            return loader.count() > 0 && loader.isVisible();
        } catch (PlaywrightException e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────
    // Scroll & Move
    // ─────────────────────────────────────────────

    /**
     * Scroll the element into view (replaces scrollAndMove).
     */
    public void scrollAndMove(Page page, Locator locator) {
        locator.scrollIntoViewIfNeeded();
        locator.hover();
    }

    /**
     * Scroll to the bottom of the page, then bring element into view
     * (replaces scrollToEnd).
     */
    public void scrollToEnd(Page page, Locator locator) {
        page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
        locator.scrollIntoViewIfNeeded();
    }

    /**
     * Scroll element into view – scrollIntoView(true) equivalent (replaces scroll).
     */
    public void scroll(Page page, Locator locator) {
        loaderValidation(page);
        locator.evaluate("el => el.scrollIntoView(true)");
    }

    /**
     * Scroll element into view – scrollIntoView(false) equivalent (replaces scrollUp).
     */
    public void scrollUp(Page page, Locator locator) {
        loaderValidation(page);
        locator.evaluate("el => el.scrollIntoView(false)");
    }

    /**
     * Time-based scroll using keyboard keys (replaces scrollUsingFor).
     */
    public void scrollUsingFor(Page page, String scrollDirection, int times) {
        loaderValidation(page);
        page.locator("//*[@name='nextButton']").click();
        for (int i = 0; i <= times; i++) {
            if (scrollDirection.equalsIgnoreCase("Up")) {
                page.keyboard().press("ArrowUp");
            } else if (scrollDirection.equalsIgnoreCase("Down")) {
                page.keyboard().press("ArrowDown");
            }
        }
    }

    // ─────────────────────────────────────────────
    // Enter Data
    // ─────────────────────────────────────────────

    public void scrollAndClick(Page page, Locator xpath) {
        xpath.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        xpath.scrollIntoViewIfNeeded();
        xpath.click();
    }

    /**
     * Clear + type into a field (replaces enterData).
     */
    public void enterData(Page page, Locator locator, String value) {
        loaderValidation(page);
        waitToBeVisible(page, locator);
        scrollAndMove(page, locator);
        locator.fill(value);
        loaderValidation(page);
    }

    public void enterDataWithBackspace(Page page, Locator locator, String value) {
        loaderValidation(page);
        waitToBeVisible(page, locator);
        scrollAndMove(page, locator);

        locator.click();
        // select all existing text then delete it, or loop backspaces
        page.keyboard().press("Control+A");
        page.keyboard().press("Backspace");

        locator.pressSequentially(value);
        loaderValidation(page);
    }
    /**
     * Type phone number digit-by-digit (replaces enterPhoneNumber).
     */
    public void enterPhoneNumber(Page page, Locator locator, String value) {
        loaderValidation(page);
        waitToBeVisible(page, locator);
        locator.click();
        // pressSequentially fires the same per-character key events as the old
        // per-digit loop (needed for masked phone inputs), natively and in one call —
        // no need for a loader check between every single keystroke.
        locator.pressSequentially(value);
        loaderValidation(page);
    }

    // ─────────────────────────────────────────────
    // Click
    // ─────────────────────────────────────────────

    /**
     * Primary click with loader checks (replaces click).
     */
    public void click(Page page, Locator locator) {
        loaderValidation(page);
        waitToBeVisible(page, locator);
        scrollAndMove(page, locator);
        try {
            locator.click();
            loaderValidation(page);
        } catch (PlaywrightException e) {
            // Fallback: force click via JS
            loaderValidation(page);
            customClick(page, locator);
            loaderValidation(page);
        }
    }


    public void doubleClick(Page page, Locator locator) {
        loaderValidation(page);
        waitToBeVisible(page, locator);
        scrollAndMove(page, locator);
        try {
            locator.dblclick();
            loaderValidation(page);
        } catch (PlaywrightException e) {
            // Fallback: force double click via JS
            loaderValidation(page);
            locator.evaluate("el => { el.dispatchEvent(new MouseEvent('dblclick', { bubbles: true })); }");
            loaderValidation(page);
        }
    }

    /**
     * JavaScript-forced click (replaces customClick).
     */
    public void customClick(Page page, Locator locator) {
        loaderValidation(page);
        scrollAndMove(page, locator);
        try {
            locator.click();
        } catch (PlaywrightException e) {
            locator.evaluate("el => el.click()");
        }
    }

    /**
     * Click via dispatchEvent – bypass pointer interception (replaces clickusingActions).
     */
    public void clickUsingActions(Page page, Locator locator) {
        scrollAndMove(page, locator);
        locator.dispatchEvent("click");
    }

    /**
     * Reliable checkbox click with checked-state verification.
     * Handles MUI / custom-styled checkboxes where the visible toggle is an SVG/span
     * overlay, not the raw <input type="checkbox">, causing plain clicks to be missed.
     *
     * Playwright's own setChecked() already implements this natively: it's a no-op if
     * the box is already checked (same idempotent guard the old 3-attempt/500ms-sleep
     * loop was hand-rolling), otherwise clicks the input and verifies the resulting
     * state, retrying internally until the actionability timeout. force=true is only
     * used as a fallback for the case the old code's "attempt 2/3" handled — a styled
     * overlay sitting on top of the real input intercepting the click.
     */
    public void clickCheckBox(Page page, Locator checkBox) {
        loaderValidation(page);
        waitToBeVisible(page, checkBox);
        scrollAndMove(page, checkBox);
        try {
            checkBox.setChecked(true, new Locator.SetCheckedOptions().setTimeout(15_000));
        } catch (PlaywrightException e) {
            checkBox.setChecked(true, new Locator.SetCheckedOptions().setForce(true).setTimeout(15_000));
        }
        loaderValidation(page);
    }

    /**
     * Reliable radio button click with checked-state verification.
     * Handles MUI / custom-styled radio inputs where direct clicks are intermittent.
     * See clickCheckBox() above — same rationale, using Playwright's native check().
     */
    public void clickRadioButton(Page page, Locator radioInput) {
        loaderValidation(page);
        waitToBeVisible(page, radioInput);
        scrollAndMove(page, radioInput);
        try {
            radioInput.check(new Locator.CheckOptions().setTimeout(15_000));
        } catch (PlaywrightException e) {
            radioInput.check(new Locator.CheckOptions().setForce(true).setTimeout(15_000));
        }
        loaderValidation(page);
    }

    /**
     * Clicks a Next / Submit navigation button and waits for the page URL to change.
     *
     * Problem solved: button.click() fires but form validation or a pending loader
     * silently keeps the UI on the same page; subsequent steps then fail because
     * next-page elements are not found.
     *
     * Strategy (up to 3 attempts, same worst-case ceiling as before — 3 × 5s):
     *   1. Capture the current URL as a baseline.
     *   2. Click the button (with JS-click fallback on PlaywrightException).
     *   3. page.waitForURL() natively waits for the URL to differ from the baseline,
     *      up to 5s — no manual polling loop — returning as soon as navigation happens
     *      rather than only checking every 500ms.
     *   4. If still on the same URL, run loaderValidation and retry.
     *   5. After 3 failed attempts, fire a JS dispatchEvent click as a last resort.
     */
    public void clickNextButton(Page page, Locator nextBtn) {
        loaderValidation(page);
        waitToBeVisible(page, nextBtn);
        scrollToEnd(page, nextBtn);

        String urlBefore = page.url();

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                nextBtn.click();
            } catch (PlaywrightException e) {
                nextBtn.evaluate("el => el.click()");
            }

            try {
                page.waitForURL(url -> !url.equals(urlBefore),
                        new Page.WaitForURLOptions().setTimeout(5_000));
                loaderValidation(page);
                return;
            } catch (PlaywrightException e) {
                loaderValidation(page);
            }
        }

        // Last-resort: JS mouse-event dispatch
        nextBtn.evaluate(
            "el => el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))"
        );
        loaderValidation(page);
    }

    /**
     * Click element A, wait, then click dropdown item B
     * (replaces ClickAndAselectDropDown).
     */
    public void clickAndSelectDropDown(Page page, Locator trigger, Locator dropdownItem) {
        trigger.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        trigger.click();
        dropdownItem.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        dropdownItem.click();
    }

    // ─────────────────────────────────────────────
    // Dropdown / Select
    // ─────────────────────────────────────────────

    /**
     * MUI dropdown by data-value attribute (replaces dropDownFunction).
     */
    public void dropDownFunction(Page page, Locator parentElement, String option) {
        if (option != null && !option.isEmpty()) {
            loaderValidation(page);
            scrollAndMove(page, parentElement);
            click(page, parentElement);
            // No blind pause here — the waitFor() below already blocks (up to 5s) until
            // the dropdown item actually renders, so a fixed pre-sleep was pure dead time.

            Locator dropdownItem;
            try {
                dropdownItem = page.locator("li[data-value='" + option + "']");
                dropdownItem.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE).setTimeout(5_000));
            } catch (PlaywrightException e) {
                dropdownItem = page.locator("li:text-is('" + option + "')");
            }
            click(page, dropdownItem);
        }
    }

    /**
     * LOB dropdown by partial text match (replaces lobSelection).
     */
    public void lobSelection(Page page, Locator parentElement, String option) {
        if (option != null && !option.isEmpty()) {
            loaderValidation(page);
            scrollAndMove(page, parentElement);
            parentElement.click();
            try {
                // No blind pause here — the waitFor() below already blocks until the
                // dropdown item renders, so a fixed pre-sleep was pure dead time.
                Locator dropdownItem = page.locator("//*[@data-value='"+option+"']");
                dropdownItem.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE).setTimeout(5_000));
                dropdownItem.click();
            }
            catch(Exception e)
            {
                Locator dropdownItem = page.locator("//li[contains(text(),'" + option + "')]");
                dropdownItem.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE).setTimeout(5_000));
                dropdownItem.click();
            }
        }
    }


    public void clickAndSelectByText(Page page, Locator triggerLocator, String option) {
        loaderValidation(page);
        scrollAndMove(page, triggerLocator);
        click(page, triggerLocator);
        // No blind pause here — the waitFor() below already blocks until the
        // option renders, so a fixed pre-sleep was pure dead time.
        Locator optionLocator = page.locator("//li[contains(text(),'" + option + "')]");
        try {
            optionLocator.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5_000));
        } catch (PlaywrightException e) {
            // Fallback: try matching by data-value attribute
            optionLocator = page.locator("//li[@data-value='" + option + "']");
            optionLocator.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(5_000));
        }
        click(page, optionLocator);
    }

    /**
     * Agency autocomplete: type, arrow-down, enter (replaces agencySelect).
     */
    public void agencySelect(Page page, Locator locator, String value) {
        loaderValidation(page);
        waitToBeVisible(page, locator);
        locator.fill(value);

        // Wait for the autocomplete suggestion list to actually render instead of a blind
        // 15s pause. Same worst-case ceiling as before (falls straight through on timeout),
        // but returns as soon as the list appears — which is almost always well under 15s.
        try {
            page.locator("li[role='option'], ul[role='listbox'] li, .MuiAutocomplete-option")
                    .first()
                    .waitFor(new Locator.WaitForOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(15_000));
        } catch (PlaywrightException e) {
            // No matching suggestion list detected — proceed exactly as the old fixed wait did.
        }

        page.keyboard().press("ArrowDown");
        page.keyboard().press("Enter");
    }

    /**
     * Autocomplete without a visible list (replaces noListDropdown).
     */
    public void noListDropdown(Page page, Locator locator, String data, Locator str) {
        locator.click();
        locator.clear();
        locator.fill(data);
        str.waitFor();
        str.click();
    }
    /**
     * Office dropdown via JS click + data-value li (replaces selectOffice).
     */
    public void selectOffice(Page page, Locator locator, String option) {
//        locator.dispatchEvent("click");
        locator.click();
        Locator dropdown = page.locator("//li[@data-value='" + option + "']");
        try {
            // Wait for the option list to render instead of a blind 4s pause — same
            // ceiling as before, resolves as soon as the option actually appears.
            dropdown.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(4_000));
        } catch (PlaywrightException e) {
            // Slower than usual to render — click(...) below waits again regardless.
        }
        page.keyboard().press("ArrowDown");
        click(page, dropdown);
    }

    /**
     * Address autocomplete: type, arrow-down, enter (replaces selectAddress).
     */
    public void selectAddress(Page page, Locator locator, String data) {
        waitToBeVisible(page, locator);
        locator.fill(data);
        // waitToBeVisible() below already blocks (up to 30s) until the Google Places
        // suggestion renders, so the old fixed 3s pre-pause added nothing but delay.
        Locator suggestion = page.locator("//div[@class='pac-item']").first();
        suggestion.waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(5_000));
        suggestion.click();
    }

    public void clearAndFill(Page page, Locator xpath, String value) {
        Locator input = xpath;
        input.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        input.click();
        input.clear();
        input.pressSequentially(value, new Locator.PressSequentiallyOptions().setDelay(100));
        waitInSeconds(page, 2);
        loaderValidation(page);
    }

    public void selectLiabilityType(Page page , String val, Locator dropDownField) {
//        Locator field = page.locator("//div[@id='LiabilityType']");
        dropDownField.scrollIntoViewIfNeeded();
        boolean boolField = dropDownField.isVisible();
        if(boolField) {
            dropDownField.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            dropDownField.click();

            Locator option;
            // Build the option locator dynamically from the value
            try {
                option = page.locator("//li[@data-value='" + val + "']");

            } catch (Exception e)
            {
                option = page.locator("//li[text()='" + val + "']");

            }


            option.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            option.click();
            // MUI menu closes after selection; confirm the listbox is gone
            page.locator("//ul[@role='listbox']")
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

            waitInSeconds(page, 2);
            loaderValidation(page);
        }
    }

    // ─────────────────────────────────────────────
    // Date Picker  (MUI v3 KeyboardDatePicker)
    // ─────────────────────────────────────────────

    /**
     * MUI date picker navigation (replaces selectDate).
     */
    public void selectDate(Page page, Locator datePicker, String date) {
        if (date == null || date.isEmpty()) return;

        try {
            loaderValidation(page);
            click(page, datePicker);

            String screenDate = datePicker.getAttribute("value");
            if (screenDate == null || screenDate.isEmpty()) {
                screenDate = new SimpleDateFormat("MM/dd/yyyy").format(new Date());
            }

            String[] screenSplitter = screenDate.split("/");
            String ssMonth = screenSplitter[0];
            String ssYear  = screenSplitter[2];

            String[] inputSplitter = date.split("/");
            String inMonth = inputSplitter[0];
            String inDate  = inputSplitter[1];
            String inYear  = inputSplitter[2];

            if (inMonth.startsWith("0")) inMonth = String.valueOf(inMonth.charAt(1));
            if (inDate.startsWith("0"))  inDate  = String.valueOf(inDate.charAt(1));

            // ── Year ──
            if (!ssYear.equals(inYear)) {
                loaderValidation(page);
                try {
                    page.locator("h6:text-is('" + ssYear + "')").click();
                } catch (PlaywrightException nse) {
                    page.locator("h6:text-is('2006')").click();
                }
                page.locator("div:text-is('" + inYear + "')").click();
            }

            // ── Month ──
            int diff = Integer.parseInt(ssMonth) - Integer.parseInt(inMonth);
            if (diff < 0) {
                for (int i = diff; i < 0; i++) {
                    loaderValidation(page);
                    page.locator("div.MuiPickersCalendarHeader-switchHeader button:nth-child(2)").click();
                }
            } else if (diff > 0) {
                for (int i = 0; i < diff; i++) {
                    loaderValidation(page);
                    page.locator("div.MuiPickersCalendarHeader-switchHeader button:nth-child(1)").click();
                }
            }

            // ── Day ──
            loaderValidation(page);
            page.locator("button:not(.hidden) p:text-is('" + inDate + "')").click();

            // ── OK ──
            loaderValidation(page);
            page.locator("span:text-is('OK')").click();

        } catch (PlaywrightException e) {
            datePicker.fill(date);
        }
    }

    // ─────────────────────────────────────────────
    // Wait Utilities
    // ─────────────────────────────────────────────

    /**
     * Hard pause in seconds (replaces waitInSeconds).
     */
    public void waitInSeconds(Page page, int seconds) {
        page.waitForTimeout(seconds * 1_000L);
    }

    /**
     * Wait until a toastify / snackbar notification disappears
     * (replaces waitUntilToastifyDisappears).
     */
    public void waitUntilToastifyDisappears(Page page, Locator locator) {
        locator.waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.HIDDEN)
                .setTimeout(30_000));
    }

    // ─────────────────────────────────────────────
    // MGA Commission helper
    // ─────────────────────────────────────────────

    /**
     * Clear and enter MGA commission value (replaces enterMGAComm).
     */
    public void enterMGAComm(Page page, Locator locator) {
        locator.click();
        locator.fill("");
        locator.pressSequentially("15");
    }

    // ─────────────────────────────────────────────
    // Screenshot
    // ─────────────────────────────────────────────



    // ─────────────────────────────────────────────
    // Config Reader  (unchanged – no Selenium dependency)
    // ─────────────────────────────────────────────



    // ─────────────────────────────────────────────
    // Exception-safe runner (replaces TryCatchHandle)
    // ─────────────────────────────────────────────

    /**
     * Runs an action and swallows PlaywrightException / RuntimeException.
     */
    public void tryCatchHandle(Runnable action) {
        try {
            action.run();
        } catch (PlaywrightException e) {
            System.out.println("Playwright exception: " + e.getMessage());
        } catch (RuntimeException e) {
            System.out.println("Runtime exception: " + e.getMessage());
        }
    }


    // ── 1. ClickAndSelectDropDown ─────────────────────────────────────────────────
// Selenium: WebDriverWait + elementToBeClickable on both element and dropdown
// Playwright: Locators auto-wait before every action — no explicit wait needed
    public void clickAndSelectDropDown(Locator element, Locator dropDownEle) {
        element.waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(60000));
        element.click();
        dropDownEle.waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(60000));
        dropDownEle.click();
    }


    // ── 2. scrollToElement ────────────────────────────────────────────────────────
// Selenium: JavascriptExecutor scrollIntoView(true) — scrolls to top of element
// Playwright: scrollIntoViewIfNeeded() — only scrolls if element is off-screen
    public void scrollToElement(Locator locator) {
        locator.scrollIntoViewIfNeeded();
    }


    // ── 3. getActions (Actions builder) ──────────────────────────────────────────
// Selenium: Returns an Actions instance for chaining mouse/keyboard actions
// Playwright: No Actions class — keyboard and mouse are direct page-level APIs.
//             Returns a Mouse object for chaining mouse actions if needed.
    public Mouse getActions(Page page) {
        return page.mouse();
    }


    // ── 4. scrollToEnds ──────────────────────────────────────────────────────────
// Selenium: Scrolls element into view, then scrolls to absolute bottom of page
// Playwright: scrollIntoViewIfNeeded() + JS scroll to page bottom
    public void scrollToEnds(Page page, Locator locator) {
        locator.scrollIntoViewIfNeeded();
        page.evaluate("window.scrollTo({ top: document.body.scrollHeight, behavior: 'instant' })");
    }


    // ── 5. scrollActions ──────────────────────────────────────────────────────────
// Selenium: Actions chain — pause 5s then scroll to element
// Playwright: wait for the element to render, then scrollIntoViewIfNeeded() — a blind
// 5s pause regardless of whether the (often animated/expanding) section was ready
// sooner or needed longer added nothing but latency; same 10s ceiling either way.
    public void scrollActions(Page page, Locator locator) {
        locator.waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(10_000));
        locator.scrollIntoViewIfNeeded();
    }


    // ── 6. pageLoadWait ───────────────────────────────────────────────────────────
// Selenium: Sets a page-load timeout on the driver (caps how long page loads wait)
// Playwright: waitForLoadState(NETWORKIDLE) — waits until network is fully idle
    public void pageLoadWait(Page page) {
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }


    // ── 7. scrollToCenter ────────────────────────────────────────────────────────
// Selenium: JS scrollIntoView with block:'center', inline:'center'
// Playwright: evaluate() to pass the same JS — Playwright has no native center-scroll
    public void scrollToCenter(Locator locator) {
        locator.evaluate("el => el.scrollIntoView({ block: 'center', inline: 'center' })");
    }
}