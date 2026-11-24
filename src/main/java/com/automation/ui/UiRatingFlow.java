package com.automation.ui;

/**
 * The UI rating flow (Java + Playwright), plugged into the UI vs API validation
 * ({@code com.automation.rater.UiApiValidationRunner}).
 *
 * <p>Implement {@link #rate(UiCase)}: enter {@link UiCase#inputs()} into the UI elements, run the rating and read the
 * output values shown on screen, keyed by API attribute name ({@link UiCase#outputKeys()}). The runner then compares them
 * (Actual) with the API response of the same inputs (Expected).
 *
 * <p>Set the class in rater.properties ({@code ui.flow.class=com.example.MyUiRatingFlow}); it needs a public no-argument
 * constructor. One instance is created per worker thread ({@code ui.threads}) and used for all of that thread's cases,
 * so open the browser in the constructor (or lazily) and close it in {@link #close()}. A Playwright object must stay on
 * the thread that created it - which this design guarantees.
 */
public interface UiRatingFlow extends AutoCloseable {

    /**
     * Rates one test case in the UI. Return {@link UiResult#failed} (or throw) when the flow cannot complete; the case is
     * then reported as failed with the message (and the screenshot, if any) and the next case continues.
     */
    UiResult rate(UiCase testCase) throws Exception;

    @Override
    default void close() throws Exception {
    }
}
