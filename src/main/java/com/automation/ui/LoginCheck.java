package com.automation.ui;

import com.automation.ui.pages.LoginPage;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checks the UI login only: opens RT_URL, logs in with RT_USERNAME / RT_PASSWORD (.env) and reports whether the login
 * form went away. Screenshot: target/ui-output/login-check.png. Headed by default; -Dui.headless=true to hide the browser.
 *
 * <pre>mvn compile exec:java -Dexec.mainClass=com.automation.ui.LoginCheck</pre>
 */
@Slf4j
public final class LoginCheck {
    private LoginCheck() {}

    public static void main(String[] args) throws Exception {
        UiCredentials credentials = UiCredentials.load();
        boolean headless = Boolean.parseBoolean(System.getProperty("ui.headless", "false"));
        Path shot = Path.of("target", "ui-output", "login-check.png");
        Files.createDirectories(shot.getParent());
        boolean ok;
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless))) {
            Page page = browser.newPage();
            LoginPage login = new LoginPage(page);
            login.login(credentials);
            ok = login.isLoggedIn(60_000);
            page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(true));
            log.info("After login: page title \"{}\", URL {}", page.title(), page.url());
            if (!ok && !login.loginError().isEmpty()) log.warn("Login page message: {}", login.loginError());
        }
        log.info(ok ? "LOGIN OK - the login form is gone" : "LOGIN FAILED - still on the login page");
        log.info("Screenshot: {}", shot.toAbsolutePath());
        System.exit(ok ? 0 : 1);
    }
}
