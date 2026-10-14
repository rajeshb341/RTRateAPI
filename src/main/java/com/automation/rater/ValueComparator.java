package com.automation.rater;

import java.math.BigDecimal;

/** Expected (Rater) vs Actual (API) comparison: numbers within a tolerance, text exactly, blanks handled explicitly. */
public final class ValueComparator {
    private ValueComparator() {}

    public record Outcome(boolean pass, String remark) {}

    public static Outcome compare(String expected, String actual, double tolerance) {
        String e = expected == null ? "" : expected.trim();
        String a = actual == null ? "" : actual.trim();

        if (e.isEmpty() && a.isEmpty()) return new Outcome(true, "");
        if (e.isEmpty()) return new Outcome(false, "Expected value is empty in the Rater");
        if (a.isEmpty()) return new Outcome(false, "Actual value is empty in the response");

        BigDecimal en = number(e);
        BigDecimal an = number(a);
        if (en != null && an != null) {
            BigDecimal diff = en.subtract(an).abs();
            if (diff.compareTo(BigDecimal.valueOf(tolerance)) <= 0) return new Outcome(true, "");
            return new Outcome(false, "Difference " + JsonText.number(diff) + " exceeds tolerance " + tolerance);
        }
        return e.equals(a) ? new Outcome(true, "") : new Outcome(false, "Text differs");
    }

    /** Parses "1,233.50" / "$250" / "-0.1" style numbers; returns null for anything else (e.g. "N/A", "HO3"). */
    private static BigDecimal number(String text) {
        String t = text.replace("$", "").replace(",", "").trim();
        if (t.isEmpty() || !t.matches("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?")) return null;
        return new BigDecimal(t);
    }
}
