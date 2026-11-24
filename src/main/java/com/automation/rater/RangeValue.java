package com.automation.rater;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks one valid number from a Rater value that is an inclusive numeric range, for the attributes where the Rater
 * classifies by range but the API needs a single value (rater.range.rows), e.g. insurance score "614-652" -> 614 and
 * roof age "11 or more Years" -> 11. Which number is taken is configurable: rater.range.pick=min|mid|max (default min),
 * per row rater.range.pick.&lt;row&gt;. The result keeps the API's number-as-text format ("614", not "614.0").
 * Anything that is not a numeric range (e.g. "No Hit", "$250,000 - $349,999" bands, distance bands) is left alone.
 */
public final class RangeValue {
    // "614-652", "0 - 5 Years", "835 - 997"; an optional unit word after the upper bound
    private static final Pattern BETWEEN = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*-\\s*(-?\\d+(?:\\.\\d+)?)(?:\\s+[A-Za-z.]+)?\\s*$");
    // "11 or more Years", "11+"
    private static final Pattern AT_LEAST = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(?:\\+|or more)(?:\\s+[A-Za-z.]+)?\\s*$",
        Pattern.CASE_INSENSITIVE);

    public enum Pick { MIN, MID, MAX }

    private RangeValue() {}

    /** True when the row is configured as a range attribute (rater.range.rows). */
    public static boolean isRangeRow(RaterSettings settings, int row) {
        return RaterSettings.parseRows(settings.get("rater.range.rows")).contains(row);
    }

    public static Pick pick(RaterSettings settings, int row) {
        return Pick.valueOf(settings.get("rater.range.pick." + row, settings.get("rater.range.pick", "min")).trim().toUpperCase());
    }

    /** One number inside the range, or empty when the text is not a numeric range. */
    public static Optional<String> resolve(String text, Pick pick) {
        if (text == null) return Optional.empty();
        Matcher between = BETWEEN.matcher(text);
        if (between.matches()) {
            BigDecimal low = new BigDecimal(between.group(1));
            BigDecimal high = new BigDecimal(between.group(2));
            if (low.compareTo(high) > 0) return Optional.empty();
            BigDecimal value = switch (pick) {
                case MIN -> low;
                case MAX -> high;
                // midpoint, rounded down to the precision of the bounds so it stays inside the range ("0-5" -> 2)
                case MID -> low.add(high).divide(BigDecimal.valueOf(2), Math.max(low.scale(), high.scale()), RoundingMode.FLOOR);
            };
            return Optional.of(JsonText.number(value));
        }
        Matcher atLeast = AT_LEAST.matcher(text);
        if (atLeast.matches()) {
            return Optional.of(JsonText.number(new BigDecimal(atLeast.group(1))));   // open-ended: the lower bound is always valid
        }
        return Optional.empty();
    }
}
