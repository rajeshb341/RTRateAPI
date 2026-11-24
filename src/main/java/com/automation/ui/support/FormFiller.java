package com.automation.ui.support;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fills a page from a field table: each field (data key -> element) is set only when it is on the page and the iteration
 * has a value for it; otherwise it keeps what the UI shows. Logs every value set and every field it could not set.
 * Dropdowns (MUI select, div#id) take the option whose data-value or text equals the value. An id may list alternatives
 * ("NewId|OldId"): the first one on the page is used.
 * <p>
 * The UI must hold the same values as the API request: after filling, {@link #requireFilled()} reads every field back and
 * fails the page when one differs from its value (each check is kept per thread, {@link #inputChecks()}, for the case's
 * ui-input-check.txt). A value the dropdown does not offer (e.g. AOP 1500) fails the case; only with
 * -Dui.fallback.nearestOption=true the nearest option is taken instead (logged in {@link #substitutions()}).
 */
@Slf4j
public final class FormFiller extends UiActions {

    /** kind: D = dropdown (div#id), I = input (name), T = date input (name, typed key by key), R = radio (name). */
    public record Field(String key, char kind, String id) {
        public static Field dropdown(String key, String id) { return new Field(key, 'D', id); }
        public static Field input(String key, String name) { return new Field(key, 'I', name); }
        public static Field radio(String key, String name) { return new Field(key, 'R', name); }
        public static Field date(String key, String name) { return new Field(key, 'T', name); }
    }

    /** The substitutions of the current case ("page: element API value -> UI value"), per thread (one UI case at a time). */
    private static final ThreadLocal<List<String>> SUBSTITUTIONS = ThreadLocal.withInitial(ArrayList::new);

    /** Read-back checks of the current case: "page | field | expected | actual | OK/DIFF", per thread. */
    private static final ThreadLocal<List<String>> CHECKS = ThreadLocal.withInitial(ArrayList::new);

    public static List<String> inputChecks() {
        return List.copyOf(CHECKS.get());
    }

    /** The substitutions made since {@link #resetSubstitutions()} (the current case). */
    public static List<String> substitutions() {
        return List.copyOf(SUBSTITUTIONS.get());
    }

    /** Clears the per-case lists (substitutions, input checks): call at the start of each case. */
    public static void resetSubstitutions() {
        SUBSTITUTIONS.get().clear();
        CHECKS.get().clear();
    }

    private static boolean nearestOptionFallback() {
        return Boolean.parseBoolean(System.getProperty("ui.fallback.nearestOption", "false"));
    }

    private final Page page;
    private final String pageName;
    /** Values this page could not set ("element = value"), e.g. an API value that is not a UI option. */
    private final List<String> notSet = new ArrayList<>();
    /** Fields of this page set to the nearest UI option (field id -> the option used): not set again in pass 2. */
    private final Map<String, String> substituted = new java.util.HashMap<>();
    /** The fields this page filled, with the value each must show (field id -> value), for the read-back check. */
    private final Map<String, Field> filledFields = new java.util.LinkedHashMap<>();
    private final Map<String, String> filledValues = new java.util.LinkedHashMap<>();

    public FormFiller(Page page, String pageName) {
        this.page = page;
        this.pageName = pageName;
    }

    /** Two passes (fields that appear after another one is set get their value in pass 2). Returns the keys not shown. */
    public List<String> fill(List<Field> fields, Map<String, String> values) {
        List<String> notShown = new ArrayList<>();
        for (int pass = 1; pass <= 2; pass++) {
            for (Field f : fields) {
                String value = values.get(f.key());
                if (value == null || value.isBlank()) continue;
                Locator el = locator(f);
                if (!shown(el)) {
                    if (pass == 2) notShown.add(f.key());
                    continue;
                }
                filledFields.put(f.id(), f);
                filledValues.put(f.id(), value);
                try {
                    if (set(f, el, value)) log.info("{}: {} = {}", pageName, primaryId(f), substituted.getOrDefault(f.id(), value));
                    notSet.removeIf(s -> s.startsWith(f.id() + " = "));
                } catch (Exception e) {
                    log.warn("{}: could not set {} to '{}': {}", pageName, f.id(), value, e.getMessage().split("\n")[0]);
                    if (pass == 2) notSet.add(f.id() + " = " + value);
                }
            }
        }
        if (!notShown.isEmpty()) log.info("{}: not on the page, skipped: {}", pageName, notShown);
        return notShown;
    }

    /** Opens collapsed sections (div[role=button][aria-expanded=false][aria-label=Expand]). */
    public void expandSections() {
        for (Locator s : page.locator("div[role=button][aria-expanded='false'][aria-label='Expand']").all()) {
            try {
                s.scrollIntoViewIfNeeded();
                s.click(new Locator.ClickOptions().setTimeout(10_000));
                loaderValidation(page);
            } catch (PlaywrightException e) {
                log.info("{}: could not expand a section: {}", pageName, e.getMessage().split("\n")[0]);
            }
        }
    }

    /**
     * The required fields (label ending in *) that are visible, enabled and still empty ("Select" counts as empty), by label.
     */
    @SuppressWarnings("unchecked")
    public List<String> emptyRequiredFields() {
        List<String> empty = (List<String>) page.evaluate("() => {"
            + " const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };"
            + " const out = [];"
            + " document.querySelectorAll('.MuiFormControl-root').forEach(fc => {"
            + "   const label = fc.querySelector('label');"
            + "   if (!label || !vis(fc)) return;"
            + "   const text = label.innerText.replace(/\\u200b/g, '').replace(/\\s+/g, ' ').trim();"
            + "   if (!text.endsWith('*')) return;"
            + "   const input = fc.querySelector('input[name]:not([type=radio]):not([type=checkbox])');"
            + "   if (!input || input.disabled) return;"
            + "   const select = fc.querySelector('div[role=button]');"
            + "   if (select && select.getAttribute('aria-disabled') === 'true') return;"
            + "   const value = (input.value || '').trim();"
            + "   if (value === '' || value === 'Select') out.push(text.replace(/\\s*\\*$/, '') + ' (' + input.name + ')');"
            + " });"
            + " return out; }");
        return empty;
    }

    /**
     * Fails when a required field is empty, or when a filled field does not show the value it was given (the API request's
     * value): the UI must hold the same data as the API request before the page is saved.
     */
    public void requireFilled() {
        List<String> empty = emptyRequiredFields();
        if (!empty.isEmpty()) {
            throw new IllegalStateException(pageName + ": required field(s) not filled: " + empty
                + (notSet.isEmpty() ? "" : " - values the UI does not take: " + notSet));
        }
        List<String> differ = verifyFilled();
        if (!differ.isEmpty()) {
            throw new IllegalStateException(pageName + ": UI value(s) differ from the API request: " + differ
                + (notSet.isEmpty() ? "" : " - values the UI does not take: " + notSet));
        }
    }

    /** Reads back every field this page filled and compares it with its value; returns the differences. */
    public List<String> verifyFilled() {
        List<String> differ = new ArrayList<>();
        filledFields.forEach((id, f) -> {
            String expected = filledValues.get(id);
            String actual;
            try {
                actual = readBack(f);
            } catch (PlaywrightException e) {
                actual = "(not readable: " + e.getMessage().split("\n")[0] + ")";
            }
            String used = substituted.get(id);
            if (used != null) {
                // -Dui.fallback.nearestOption=true: the API value is not a UI option; the nearest one was chosen on purpose
                boolean shown = sameValue(used, actual);
                CHECKS.get().add(pageName + " | " + primaryId(f) + " | " + expected + " | " + actual + " | "
                    + (shown ? "SUBSTITUTED (not a UI option)" : "DIFF"));
                if (!shown) differ.add(primaryId(f) + " expected '" + used + "' but the UI shows '" + actual + "'");
                return;
            }
            boolean same = sameValue(expected, actual);
            CHECKS.get().add(pageName + " | " + primaryId(f) + " | " + expected + " | " + actual + " | " + (same ? "OK" : "DIFF"));
            if (!same) differ.add(primaryId(f) + " expected '" + expected + "' but the UI shows '" + actual + "'");
        });
        if (differ.isEmpty()) log.info("{}: all {} field(s) show the API request's value", pageName, filledFields.size());
        else log.warn("{}: {} field(s) differ from the API request: {}", pageName, differ.size(), differ);
        return differ;
    }

    /** What the field shows now: dropdown = its selected data-value (else its text), input = its value, radio = the checked one. */
    private String readBack(Field f) {
        Locator el = locator(f);
        if (el.count() == 0 || !el.isVisible()) return "(not on the page)";
        switch (f.kind()) {
            case 'D' -> {
                Locator stored = page.locator("//input[@name='" + el.getAttribute("id") + "']");
                if (stored.count() > 0 && !stored.first().inputValue().isBlank()) return stored.first().inputValue().trim();
                return el.innerText().replace("\u200b", "").trim();
            }
            case 'R' -> {
                Locator checked = page.locator("//input[@name='" + primaryId(f) + "'][@type='radio']");
                for (Locator r : checked.all()) if (r.isChecked()) return r.getAttribute("value");
                return "";
            }
            default -> {
                return el.inputValue().trim();
            }
        }
    }

    /** Same value: equal ignoring case and spaces, or the same number once $ , % are dropped (299000 = $299,000). */
    static boolean sameValue(String expected, String actual) {
        if (expected == null || actual == null) return false;
        String e = expected.replace("\u200b", "").trim();
        String a = actual.replace("\u200b", "").trim();
        if (e.equalsIgnoreCase(a) || e.replaceAll("\\s", "").equalsIgnoreCase(a.replaceAll("\\s", ""))) return true;
        Double ne = number(e);
        Double na = number(a);
        return ne != null && na != null && Math.abs(ne - na) < 1e-9;
    }

    /**
     * Clicks the page's save button and requires the application's confirmation (a toast containing {@code expected},
     * e.g. "Saved Successfully"); otherwise fails with the message and the field errors shown.
     */
    public void saveAndConfirm(String xpath, String expected) {
        press(xpath);
        Locator toast = page.locator("//div[contains(@class,'Toastify__toast-body')]");
        String message = "";
        try {
            toast.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(20_000));
            message = toast.first().innerText().trim();
        } catch (PlaywrightException none) {
            // no message at all
        }
        if (message.contains(expected)) {
            log.info("{}: {}", pageName, message);
            return;
        }
        List<String> errors = new ArrayList<>();
        for (Locator err : page.locator("//p[contains(@class,'Mui-error')]").all()) {
            try {
                if (err.isVisible()) errors.add(err.innerText().trim());
            } catch (PlaywrightException ignore) {
                // gone
            }
        }
        throw new IllegalStateException(pageName + ": not saved - " + (message.isEmpty() ? "no confirmation message" : message)
            + (errors.isEmpty() ? "" : "; field errors: " + errors) + (notSet.isEmpty() ? "" : "; values the UI does not take: " + notSet));
    }

    /** Clicks a button (waits up to 2 minutes for it) and waits for the page to settle. */
    public void press(String xpath) {
        Locator b = page.locator(xpath).last();
        loaderValidation(page);
        b.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(120_000));
        b.scrollIntoViewIfNeeded();
        b.click(new Locator.ClickOptions().setTimeout(120_000));
        loaderValidation(page);
        pageLoadWait(page);
    }

    /** Logs the application's message toast and the fields marked invalid, if any. */
    public void logMessages(String when) {
        Locator toast = page.locator("//div[contains(@class,'Toastify__toast-body')]");
        try {
            toast.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3000));
            log.warn("{} {}: {}", pageName, when, toast.first().innerText().trim());
        } catch (PlaywrightException none) {
            // no message
        }
        for (Locator err : page.locator("//p[contains(@class,'Mui-error')]").all()) {
            try {
                if (err.isVisible()) log.warn("{} {}: field error '{}'", pageName, when, err.innerText().trim());
            } catch (PlaywrightException ignore) {
                // gone
            }
        }
    }

    /** The open list's option nearest to {@code value}: the closest number, else the first real option; null if none. */
    private Locator nearestOption(String value) {
        List<Locator> options = new ArrayList<>();
        for (Locator li : page.locator("//ul[@role='listbox']//li").all()) {
            String v = optionValue(li);
            if (v.isEmpty() || v.equalsIgnoreCase("Select") || v.equalsIgnoreCase("Other")) continue;
            options.add(li);
        }
        if (options.isEmpty()) return null;
        Double target = number(value);
        if (target == null) return options.get(0);
        Locator best = null;
        double bestDiff = Double.MAX_VALUE;
        for (Locator li : options) {
            Double n = number(optionValue(li));
            if (n == null) continue;
            double diff = Math.abs(n - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = li;
            }
        }
        return best != null ? best : options.get(0);
    }

    private static String optionValue(Locator li) {
        String v = li.getAttribute("data-value");
        return (v == null || v.isBlank() ? li.innerText() : v).replace("\u200b", "").trim();
    }

    private static Double number(String s) {
        try {
            return Double.parseDouble(s.replaceAll("[$,%\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The first of the field's alternative ids ("NewId|OldId"). */
    private static String primaryId(Field f) {
        return f.id().split("\\|")[0];
    }

    /** By id (dropdown) or name (input); "A|B" = alternative ids, whichever is on the page. */
    private Locator locator(Field f) {
        StringBuilder xpath = new StringBuilder();
        for (String id : f.id().split("\\|")) {
            if (xpath.length() > 0) xpath.append(" | ");
            xpath.append(f.kind() == 'D' ? "//div[@id='" + id + "']" : "//input[@name='" + id + "']");
        }
        return page.locator(xpath.toString()).first();
    }

    private boolean set(Field f, Locator el, String value) {
        switch (f.kind()) {
            case 'D' -> {
                if ("true".equals(el.getAttribute("aria-disabled")) || substituted.containsKey(f.id())) return false;
                String shownText = el.innerText().replace("​", "").trim();
                Locator stored = page.locator("//input[@name='" + el.getAttribute("id") + "']");
                if (shownText.equalsIgnoreCase(value) || (stored.count() > 0 && value.equals(stored.first().inputValue()))) return false;
                el.scrollIntoViewIfNeeded();
                el.click();
                // the list renders after the click: wait for it before deciding whether the option exists
                page.locator("//ul[@role='listbox']//li").first()
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(10_000));
                Locator option = page.locator("//ul[@role='listbox']//li[@data-value=\"" + value + "\"]");
                if (option.count() == 0) option = page.locator("//ul[@role='listbox']//li[normalize-space(.)=\"" + value + "\"]");
                if (option.count() == 0) {
                    Locator nearest = nearestOptionFallback() ? nearestOption(value) : null;
                    if (nearest == null) {
                        page.keyboard().press("Escape");
                        throw new IllegalArgumentException("no option '" + value + "' in the dropdown");
                    }
                    String note = pageName + ": " + primaryId(f) + " API value '" + value + "' is not a UI option -> '"
                        + optionValue(nearest) + "' used";
                    log.warn(note);
                    SUBSTITUTIONS.get().add(note);
                    substituted.put(f.id(), optionValue(nearest));
                    option = nearest;
                }
                option.first().click();
                loaderValidation(page);
                return true;
            }
            case 'T' -> {
                // masked date field: clear with select-all + Backspace, then type it key by key (as the reference project)
                if (el.isDisabled() || value.equals(el.inputValue())) return false;
                el.scrollIntoViewIfNeeded();
                el.click();
                page.keyboard().press("Control+A");
                page.keyboard().press("Backspace");
                // the mask adds the slashes itself: type the digits only (11/18/2026 -> 11182026)
                el.pressSequentially(value.replaceAll("[^0-9]", ""), new Locator.PressSequentiallyOptions().setDelay(80));
                page.keyboard().press("Tab");
                loaderValidation(page);
                return true;
            }
            case 'R' -> {
                Locator radio = page.locator("//input[@name='" + primaryId(f) + "' and @value='" + value + "']").first();
                if (radio.isChecked()) return false;
                radio.check(new Locator.CheckOptions().setForce(true));
                loaderValidation(page);
                return true;
            }
            default -> {
                if (el.isDisabled() || value.equals(el.inputValue())) return false;
                clearAndFill(page, el, value);
                loaderValidation(page);
                return true;
            }
        }
    }

    private static boolean shown(Locator locator) {
        try {
            return locator.count() > 0 && locator.first().isVisible();
        } catch (PlaywrightException e) {
            return false;
        }
    }
}
