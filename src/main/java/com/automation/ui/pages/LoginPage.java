package com.automation.ui.pages;

import com.automation.ui.UiCredentials;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;

/**
 * RT application login page (adopted from NonConfigUpdatedInspectionUpdate, Pages.NonConfigured.LoginPage):
 * open the application URL, enter the username and password, click Login.
 *
 * <p>The URL, username and password come from {@link UiCredentials} (.env / environment variables RT_URL, RT_USERNAME,
 * RT_PASSWORD) - never from the code. The password is never logged.
 */
@Slf4j
public class LoginPage {
    /** The application's "please wait" overlay; every step waits for it to disappear. */
    private static final String LOADER = "div.custom-loader-container";
    private static final double LOADER_TIMEOUT_MS = 60_000;

    private final Page page;
    private final Locator username;
    private final Locator password;
    private final Locator loginButton;

    public LoginPage(Page page) {
        this.page = page;
        this.username = page.locator("#email");
        this.password = page.locator("#password");
        this.loginButton = page.locator("//*[@type='submit']");
    }

    /** Opens the application URL and logs in with the configured credentials. */
    public void login(UiCredentials credentials) {
        open(credentials.url());
        login(credentials.username(), credentials.password());
    }

    /** Opens the application URL and waits until the page has loaded. */
    public void open(String url) {
        log.info("Opening the RT application: {}", url);
        page.navigate(url);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        waitForLoader();
    }

    /** Enters the username and password and clicks Login. */
    public void login(String user, String pwd) {
        log.info("Logging in as {}", user);
        enterData(username, user);
        enterData(password, pwd);
        click(loginButton);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        waitForLoader();
        log.info("Login submitted");
    }

    /**
     * True once the login form is gone and the application has loaded behind it (page text shown, loader gone); waits up
     * to {@code timeoutMs}. False when the login page is still shown (e.g. wrong credentials - see {@link #loginError()}).
     */
    public boolean isLoggedIn(double timeoutMs) {
        try {
            username.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(timeoutMs));
        } catch (PlaywrightException stillOnLoginPage) {
            return false;
        }
        try {
            page.waitForFunction("() => document.body && document.body.innerText.trim().length > 0", null,
                new Page.WaitForFunctionOptions().setTimeout(timeoutMs));
        } catch (PlaywrightException stillLoading) {
            log.warn("The application did not show any content within {} s after login", (long) (timeoutMs / 1000));
            return false;
        }
        waitForLoader();
        return true;
    }

    /** The message the login page shows (e.g. invalid credentials), or "" when there is none. */
    public String loginError() {
        try {
            Locator message = page.locator("[role='alert'], .error, .alert, .MuiAlert-message, .invalid-feedback, .text-danger");
            return message.count() > 0 ? message.first().innerText().trim() : "";
        } catch (PlaywrightException e) {
            return "";
        }
    }

    // ── helpers (as UtilClass.enterData / click / loaderValidation of the reference project) ──

    private void enterData(Locator field, String value) {
        if (value == null) throw new IllegalArgumentException("No value to enter");
        waitForLoader();
        field.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        field.scrollIntoViewIfNeeded();
        field.fill(value);
    }

    private void click(Locator button) {
        waitForLoader();
        button.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        button.scrollIntoViewIfNeeded();
        try {
            button.click();
        } catch (PlaywrightException e) {
            log.warn("Normal click failed ({}), clicking via JavaScript", e.getMessage());
            button.evaluate("element => element.click()");
        }
    }

    private void waitForLoader() {
        try {
            Locator loader = page.locator(LOADER);
            if (loader.count() > 0 && loader.first().isVisible())
            {
                loader.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(LOADER_TIMEOUT_MS));
            }
        } catch (PlaywrightException e) {
            // no loader on the page - nothing to wait for
        }
    }
}
