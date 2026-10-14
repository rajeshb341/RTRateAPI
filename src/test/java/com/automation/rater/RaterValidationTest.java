package com.automation.rater;

import org.testng.annotations.Test;

import static org.testng.Assert.assertNotEquals;

/**
 * Starts the Rater validation from TestNG (testng.xml). It does exactly what
 * {@code RaterValidationRunner.main()} does and writes the same output to
 * target/rater-output/Run_&lt;timestamp&gt;/ (console-log.txt, iterations/TCxxx/request.json + response.json,
 * RaterValidationReport_&lt;timestamp&gt;.xlsx).
 *
 * Needs Excel / WPS (COM) and access to rt.endpoint. Restrict the cases with -DraterCases=TC001,TC002.
 * The test fails only when the run itself breaks (exit code 2); attribute-level Fail rows (exit code 1)
 * are reported in the Excel report, not as a TestNG failure.
 */
public class RaterValidationTest {

    @Test(groups = {"rater"}, description = "Rater vs API validation - writes the Run_<timestamp> folder")
    public void runRaterValidation() {
        String cases = System.getProperty("raterCases", "").trim();
        String[] args = cases.isEmpty() ? new String[0] : new String[]{"--cases", cases};

        int exitCode = RaterValidationRunner.run(args);

        assertNotEquals(exitCode, 2, "Rater validation could not complete - see the Run_<timestamp>/console-log.txt");
    }
}
