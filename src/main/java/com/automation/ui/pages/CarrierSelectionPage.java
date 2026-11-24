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
 * Carrier Selection of a New Business submission (locators recorded from the RT UI on rtconnectorpldev, 17 Nov 2026):
 * Quote Information (effective date = the API's, term, billing type, brokerage markets) -> Get Available Market.
 */
@Slf4j
public class CarrierSelectionPage {

    private static final List<Field> QUOTE_INFORMATION = List.of(
        Field.date("PolicyEffectiveDate", "PolicyEffectiveDate"),
        Field.dropdown("termMonths", "TermInMonths"),
        Field.dropdown("Billing_Type", "BillingType"),
        Field.dropdown("brokerageMarketsOnly", "SearchforonlyBrokerageMarkets"));

    private final Page page;

    public CarrierSelectionPage(Page page) {
        this.page = page;
    }

    /**
     * Fills Quote Information (every field checked against the API request) and clicks Get Available Market. Returns the
     * UI's "no markets" message when it shows one (e.g. "No policy forms available for the chosen risk."), else "".
     */
    public String getAvailableMarkets(Map<String, String> data) {
        FormFiller form = new FormFiller(page, "Carrier Selection");
        page.locator("//button[@name='GetAvailableMarket']").first()
            .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(180_000));
        form.fill(QUOTE_INFORMATION, data);
        // the quote must be asked for with the same data as the API request
        form.requireFilled();
        String problem = "";
        for (int attempt = 1; attempt <= 3; attempt++) {
            // the click counts only when the UI really sends the markets request (invokeWorkflowEvent) to the server
            boolean requested;
            try {
                page.waitForResponse(r -> r.url().contains("invokeWorkflowEvent") && "POST".equals(r.request().method()),
                    new Page.WaitForResponseOptions().setTimeout(30_000),
                    () -> form.press("//button[@name='GetAvailableMarket']"));
                requested = true;
            } catch (com.microsoft.playwright.TimeoutError notSent) {
                requested = false;
            }
            form.logMessages("after Get Available Market");
            String[] answer = waitForAnswer(requested ? 20_000 : 3_000);
            if (answer[0] != null) {
                log.warn("Carrier Selection: after Get Available Market the UI says '{}'", answer[0]);
                return answer[0];
            }
            if (requested && answer[1].isEmpty()) {
                log.info("Carrier Selection: available markets requested (request sent, no 'no policy forms' message)");
                return "";
            }
            // seen: "Enter a valid date" on a valid-looking Effective Date, or a click that sends nothing - the UI kept
            // a broken date from the key-by-key typing: enter the date again and retry
            problem = !answer[1].isEmpty() ? "the UI shows: " + answer[1] : "the click sent no markets request";
            log.warn("Carrier Selection: Get Available Market not accepted (attempt {}): {}", attempt, problem);
            retypeEffectiveDate(data.getOrDefault("PolicyEffectiveDate", ""));
        }
        throw new IllegalStateException("Carrier Selection: Get Available Market not accepted - " + problem);
    }

    /**
     * After Get Available Market: {UI "no markets" message or null, visible field error texts or ""}. Only short visible
     * texts count: the page's request / response viewer also contains these words inside JSON.
     */
    private String[] waitForAnswer(long timeoutMs) {
        java.util.regex.Pattern noMarkets = java.util.regex.Pattern.compile(
            "No policy forms available|No markets? (available|found)", java.util.regex.Pattern.CASE_INSENSITIVE);
        long end = System.currentTimeMillis() + timeoutMs;
        String errors = "";
        while (System.currentTimeMillis() < end) {
            for (Locator m : page.getByText(noMarkets).all()) {
                String text = visibleShortText(m);
                if (text != null) return new String[] {text, ""};
            }
            errors = fieldErrors();
            page.waitForTimeout(1_000);
        }
        return new String[] {null, errors};
    }

    /** Visible validation messages under the Quote Information fields (e.g. "Enter a valid date"). */
    private String fieldErrors() {
        List<String> texts = new java.util.ArrayList<>();
        for (Locator e : page.locator("//p[contains(@class,'Mui-error')] | //*[contains(@class,'error') and "
            + "starts-with(normalize-space(.),'Enter a valid')]").all()) {
            String text = visibleShortText(e);
            if (text != null && !texts.contains(text)) texts.add(text);
        }
        return String.join("; ", texts);
    }

    private static String visibleShortText(Locator l) {
        try {
            if (!l.isVisible()) return null;
            String text = l.innerText().trim();
            return text.isEmpty() || text.length() > 200 ? null : text;
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return null;
        }
    }

    /**
     * Enters the Effective Date again, the whole date at once (typing digit by digit lets the UI parse partial dates:
     * seen Expiration 11/23/0203 + "Enter a valid date"); digit by digit only when the field does not take it.
     */
    private void retypeEffectiveDate(String value) {
        if (value.isEmpty()) return;
        Locator el = page.locator("//input[@name='PolicyEffectiveDate']").first();
        el.scrollIntoViewIfNeeded();
        el.fill("");
        el.fill(value);
        page.keyboard().press("Tab");
        page.waitForTimeout(1_500);
        if (!value.equals(el.inputValue())) {
            el.click();
            page.keyboard().press("Control+A");
            page.keyboard().press("Delete");
            el.pressSequentially(value.replaceAll("[^0-9]", ""), new Locator.PressSequentiallyOptions().setDelay(150));
            page.keyboard().press("Tab");
            page.waitForTimeout(1_500);
        }
        log.info("Carrier Selection: Effective Date entered again: '{}' (expiration '{}')", el.inputValue(),
            page.locator("//input[@name='PolicyExpirationDate']").count() > 0
                ? page.locator("//input[@name='PolicyExpirationDate']").first().inputValue() : "?");
    }
}
