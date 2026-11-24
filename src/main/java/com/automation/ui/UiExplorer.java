package com.automation.ui;

import com.automation.rater.RaterSettings;
import com.automation.ui.pageobjects.AgencyAgentPO;
import com.automation.ui.pages.AgencyAndAgent;
import com.automation.ui.pages.HomePage;
import com.automation.ui.pages.InsuredDetailsHybridConfig;
import com.automation.ui.pages.LoginPage;
import com.automation.ui.pages.RiskDetailsPage;
import com.automation.ui.pages.CoverageDetailsPage;
import com.automation.ui.pages.CarrierSelectionPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Development helper: finds the UI elements of the pages after Insured Details. Runs the known flow (login, home, agency,
 * insured) for one iteration with its API request data, then records the page it lands on: every visible field with its
 * label, locator (div#id / input[name]), type, the options of each dropdown (opened and closed again), radio values, buttons
 * - to target/ui-output/explore/&lt;page&gt;.json + .png. Nothing is saved or submitted on the explored page.
 *
 * <pre>mvn compile exec:java -Dexec.mainClass=com.automation.ui.UiExplorer -Dexec.args="&lt;request.json of the iteration&gt; [pages]"</pre>
 * pages = how many further pages to explore by clicking the page's Save &amp; Next / Next after recording it (default 1).
 */
@Slf4j
public final class UiExplorer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UiExplorer() {}

    public static void main(String[] args) throws Exception {
        Path requestFile = Path.of(args[0]);
        int pagesArg = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int pages = pagesArg;
        String id = requestFile.getParent().getFileName().toString();
        String requestJson = MAPPER.readTree(requestFile.toFile()).path("body").toString();
        RaterSettings settings = RaterSettings.load();
        Map<String, String> rows = new LinkedHashMap<>();
        com.automation.rater.RaterTestData.read(settings.path("rater.testdata.file"), "TestData").stream()
            .filter(c -> c.id().equals(id)).findFirst()
            .ifPresent(c -> c.inputs().forEach((r, v) -> rows.put("row." + r, v == null ? "" : v)));
        Map<String, String> input = new LinkedHashMap<>(UiInputMapping.load().uiData(requestJson,
            UiTestData.read(settings.path("rater.testdata.file")).row(id), rows));
        input.put("TestCaseId", id);
        // -Dui.set.<field>=<value> overrides a field for the exploration, e.g. -Dui.set.product=Homeowners
        System.getProperties().forEach((k, v) -> {
            if (k.toString().startsWith("ui.set.")) input.put(k.toString().substring("ui.set.".length()), v.toString());
        });
        Path out = Path.of("target", "ui-output", "explore");
        Files.createDirectories(out);

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false))) {
            Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1600, 1000));
            // what goes wrong in the page: script errors and failed API calls (bodies masked)
            page.onConsoleMessage(m -> { if ("error".equals(m.type())) log.warn("BROWSER CONSOLE: {}", m.text().replaceAll("\\s+", " ")); });
            page.onPageError(e -> log.warn("BROWSER PAGE ERROR: {}", String.valueOf(e).replaceAll("\\s+", " ")));
            page.onResponse(r -> {
                if (r.status() >= 400) {
                    String body;
                    try {
                        body = UiNetworkCapture.mask(r.text());
                    } catch (Exception e) {
                        body = "";
                    }
                    log.warn("FAILED CALL {} {} -> {} {}", r.request().method(), r.url(), r.status(),
                        body.length() > 300 ? body.substring(0, 300) : body);
                }
            });
            LoginPage login = new LoginPage(page);
            login.login(UiCredentials.load());
            if (!login.isLoggedIn(60_000)) throw new IllegalStateException("login failed");
            new HomePage(page).selectOfficeDetails(input);
            new AgencyAndAgent(page).fillAgency(input, new AgencyAgentPO(page));
            new InsuredDetailsHybridConfig(page).ConfigNewInsuredDetails(input);
            // -Dui.explore.after=risk: also fill Risk Details (RiskDetailsPage) and record the page after it
            String after = System.getProperty("ui.explore.after", "insured");
            if (after.equals("risk") || after.equals("coverage") || after.equals("markets")) new RiskDetailsPage(page).fill(input);
            if (after.equals("coverage") || after.equals("markets")) new CoverageDetailsPage(page).fill(input);
            if (after.equals("markets")) new CarrierSelectionPage(page).getAvailableMarkets(input);
            // -Dui.explore.steps="record:a;reload;text:Coverage Details;click:<xpath>;wait:10;record:b" - scripted probing
            String steps = System.getProperty("ui.explore.steps", "");
            if (!steps.isBlank()) {
                for (String step : steps.split(";")) {
                    String s = step.trim();
                    String arg = s.contains(":") ? s.substring(s.indexOf(':') + 1).trim() : "";
                    log.info("STEP {}", s);
                    try {
                        if (s.startsWith("record")) record(page, out.resolve(arg.isEmpty() ? "step" : arg));
                        else if (s.equals("reload")) page.reload();
                        else if (s.startsWith("wait")) page.waitForTimeout(Integer.parseInt(arg) * 1000L);
                        else if (s.startsWith("text")) {
                            Locator t = page.locator("//*[normalize-space(text())='" + arg + "']").last();
                            t.scrollIntoViewIfNeeded();
                            t.click(new Locator.ClickOptions().setTimeout(10_000));
                        } else if (s.startsWith("click")) page.locator(arg).last().click(new Locator.ClickOptions().setTimeout(10_000));
                        else if (s.startsWith("fill")) {       // fill:<xpath>=<value>
                            int eq = arg.lastIndexOf('=');
                            Locator f = page.locator(arg.substring(0, eq)).first();
                            f.scrollIntoViewIfNeeded();
                            f.fill(arg.substring(eq + 1));
                        } else if (s.startsWith("html")) {     // html:<xpath> - the element's markup (attributes that matter)
                            for (Locator e : page.locator(arg).all()) {
                                String html = (String) e.evaluate("el => el.outerHTML.replace(/ (class|style|d|viewBox|focusable|aria-hidden|tabindex)=\"[^\"]*\"/g, '')"
                                    + ".replace(/<svg.*?<\\/svg>/g, '').replace(/\\s+/g, ' ')");
                                log.info("HTML {}: {}", arg, html.length() > 2500 ? html.substring(0, 2500) + "..." : html);
                            }
                        } else if (s.equals("expand")) {
                            expandSections(page);
                        } else if (s.startsWith("shot")) {
                            page.screenshot(new Page.ScreenshotOptions().setPath(out.resolve(arg + ".png")));
                        } else if (s.startsWith("select")) {   // select:<dropdown id>=<data-value or text>
                            int eq = arg.lastIndexOf('=');
                            Locator dd = page.locator("//div[@id='" + arg.substring(0, eq) + "']").first();
                            dd.scrollIntoViewIfNeeded();
                            dd.click();
                            String v = arg.substring(eq + 1);
                            Locator o = page.locator("//ul[@role='listbox']//li[@data-value='" + v + "' or normalize-space(.)='" + v + "']").first();
                            o.click(new Locator.ClickOptions().setTimeout(10_000));
                            waitReady(page);
                        }
                        log.info("STEP {} done - page {}", s, page.url().replaceFirst("^.*#/", "").replaceFirst("(/[^/]{12})[^/]*", "$1..."));
                    } catch (Exception e) {
                        log.warn("STEP {} failed: {}", s, e.getMessage().split("\n")[0]);
                    }
                }
                pages = 0;
            }
            for (int n = 1; n <= pages; n++) {
                page.waitForTimeout(3000);
                String name = String.format("%s_%02d", after, n);
                record(page, out.resolve(name));
                if (n < pages) {
                    Locator next = page.locator("//button[@name='SaveandNext' or @name='nextButton' or @type='submit']").last();
                    next.click();
                    page.waitForTimeout(5000);
                }
            }
        }
        log.info("Explored pages are in {}", out.toAbsolutePath());
    }

    /** Records the visible fields of the page (with dropdown options) to &lt;base&gt;.json and a full-page screenshot. */
    /** Waits (up to 120 s) until no loader / spinner / progress element is visible and the network is idle. */
    static void waitReady(Page page) {
        long end = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < end) {
            try {
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(15_000));
            } catch (Exception ignore) {
                // keep polling the loader below
            }
            Object busy = page.evaluate("() => Array.from(document.querySelectorAll("
                + "'[class*=loader], [class*=Loader], [class*=spinner], [class*=Spinner], [class*=progress], [class*=Progress], [role=progressbar]'))"
                + ".some(e => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e);"
                + " return r.width > 20 && r.height > 20 && s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0'; })");
            if (!Boolean.TRUE.equals(busy)) break;
            page.waitForTimeout(2000);
        }
        // the page's form may render after the loader is gone: wait until its fields are there and their number is stable
        int last = -1;
        for (int i = 0; i < 15; i++) {
            Object n = page.evaluate("() => Array.from(document.querySelectorAll('main input[name], main div[role=button][id], "
                + "form input[name], form div[role=button][id]')).filter(e => { const r = e.getBoundingClientRect(); "
                + "return r.width > 0 && r.height > 0; }).length");
            int count = ((Number) n).intValue();
            if (count > 0 && count == last) return;
            last = count;
            page.waitForTimeout(3000);
        }
        log.warn("The page's fields did not settle within 45 s: recording it as it is");
    }

    /** Opens collapsed accordion sections (e.g. "Location 1") so their fields can be recorded. */
    static void expandSections(Page page) {
        for (Locator section : page.locator("[aria-expanded='false'].MuiAccordionSummary-root, .MuiExpansionPanelSummary-root[aria-expanded='false'], "
            + "div[role=button][aria-expanded='false'][aria-label='Expand']").all()) {
            try {
                section.click(new Locator.ClickOptions().setTimeout(5000));
                waitReady(page);
            } catch (Exception e) {
                log.info("Could not expand a section: {}", e.getMessage().split("\n")[0]);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void record(Page page, Path base) throws Exception {
        waitReady(page);
        expandSections(page);
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(base + ".png")).setFullPage(true));
        List<Map<String, Object>> fields = (List<Map<String, Object>>) page.evaluate("() => {"
            + " const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };"
            + " const label = e => { const fc = e.closest('.MuiFormControl-root, .MuiGrid-item');"
            + "   const l = fc && fc.querySelector('label, p'); return l ? l.innerText.replace(/\\s+/g,' ').trim() : ''; };"
            + " const section = e => { let n = e; while (n && n !== document.body) { const h = n.querySelector && n.querySelector(':scope > h6, :scope > div > h6');"
            + "   if (h) return h.innerText.trim(); n = n.parentElement; } return ''; };"
            + " const out = [];"
            + " document.querySelectorAll('div[role=button][id], div[role=combobox][id], div[aria-haspopup=listbox][id]').forEach(e => { if (vis(e))"
            + "   out.push({kind:'dropdown', locator:\"//div[@id='\"+e.id+\"']\", label: label(e), section: section(e), value: e.innerText.trim(),"
            + "   disabled: e.getAttribute('aria-disabled') === 'true'}); });"
            + " document.querySelectorAll('input[name], textarea[name]').forEach(e => { if (!vis(e) && e.type !== 'radio' && e.type !== 'checkbox') return;"
            + "   if (e.getAttribute('aria-hidden') === 'true' || (e.type === 'text' && e.closest('div[role=button]'))) return;"
            + "   const lab = e.type === 'radio' ? (e.closest('[role=radiogroup]')?.parentElement?.querySelector('label,p')?.innerText || label(e)) : label(e);"
            + "   out.push({kind: e.type === 'radio' ? 'radio' : e.type === 'checkbox' ? 'checkbox' : (e.tagName === 'TEXTAREA' ? 'textarea' : 'input'),"
            + "   locator: \"//\"+e.tagName.toLowerCase()+\"[@name='\"+e.name+\"']\" + (e.type === 'radio' ? \"[@value='\"+e.value+\"']\" : ''),"
            + "   label: lab.replace(/\\s+/g,' ').trim(), section: section(e), value: e.type === 'radio' || e.type === 'checkbox' ? String(e.checked) : e.value,"
            + "   placeholder: e.placeholder || '', inputType: e.type, disabled: e.disabled}); });"
            + " document.querySelectorAll('button[name], a[name]').forEach(e => { if (vis(e)) out.push({kind:'button', locator:\"//\"+e.tagName.toLowerCase()+\"[@name='\"+e.getAttribute('name')+\"']\","
            + "   label: e.innerText.trim()}); });"
            + " document.querySelectorAll('h5, h6').forEach(e => { if (vis(e)) out.push({kind:'heading', label: e.innerText.trim()}); });"
            + " return out; }");
        // dropdown options: open each enabled dropdown, read its options, close it
        for (Map<String, Object> f : fields) {
            if (!"dropdown".equals(f.get("kind")) || Boolean.TRUE.equals(f.get("disabled"))) continue;
            try {
                Locator dd = page.locator((String) f.get("locator")).first();
                dd.scrollIntoViewIfNeeded();
                dd.click(new Locator.ClickOptions().setTimeout(5000));
                Locator options = page.locator("//ul[@role='listbox']//li");
                options.first().waitFor(new Locator.WaitForOptions().setTimeout(5000));
                List<String> list = new ArrayList<>();
                for (Locator o : options.all()) {
                    String dataValue = o.getAttribute("data-value");
                    String text = o.innerText().trim();
                    list.add(dataValue == null || dataValue.equals(text) ? text : text + " [data-value=" + dataValue + "]");
                }
                f.put("options", list);
                page.keyboard().press("Escape");
                page.locator("//ul[@role='listbox']").first().waitFor(new Locator.WaitForOptions()
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN).setTimeout(5000));
            } catch (Exception e) {
                f.put("options", "could not open: " + e.getMessage().split("\n")[0]);
                page.keyboard().press("Escape");
            }
        }
        Files.writeString(Path.of(base + ".json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(fields), StandardCharsets.UTF_8);
        log.info("Recorded {} element(s) of {} -> {}.json", fields.size(), page.url(), base);
    }
}
