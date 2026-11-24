package com.automation.rater;

import java.util.Locale;
import java.util.Map;

/** Two-letter postal code of a US state name as the Rater writes it ("New_Jersey", "Washington_DC"). */
final class StateCodes {
    private static final Map<String, String> CODES = Map.ofEntries(
        Map.entry("ALABAMA", "AL"), Map.entry("ALASKA", "AK"), Map.entry("ARIZONA", "AZ"), Map.entry("ARKANSAS", "AR"),
        Map.entry("CALIFORNIA", "CA"), Map.entry("COLORADO", "CO"), Map.entry("CONNECTICUT", "CT"), Map.entry("DELAWARE", "DE"),
        Map.entry("FLORIDA", "FL"), Map.entry("GEORGIA", "GA"), Map.entry("HAWAII", "HI"), Map.entry("IDAHO", "ID"),
        Map.entry("ILLINOIS", "IL"), Map.entry("INDIANA", "IN"), Map.entry("IOWA", "IA"), Map.entry("KANSAS", "KS"),
        Map.entry("KENTUCKY", "KY"), Map.entry("LOUISIANA", "LA"), Map.entry("MAINE", "ME"), Map.entry("MARYLAND", "MD"),
        Map.entry("MASSACHUSETTS", "MA"), Map.entry("MICHIGAN", "MI"), Map.entry("MINNESOTA", "MN"), Map.entry("MISSISSIPPI", "MS"),
        Map.entry("MISSOURI", "MO"), Map.entry("MONTANA", "MT"), Map.entry("NEBRASKA", "NE"), Map.entry("NEVADA", "NV"),
        Map.entry("NEW HAMPSHIRE", "NH"), Map.entry("NEW JERSEY", "NJ"), Map.entry("NEW MEXICO", "NM"), Map.entry("NEW YORK", "NY"),
        Map.entry("NORTH CAROLINA", "NC"), Map.entry("NORTH DAKOTA", "ND"), Map.entry("OHIO", "OH"), Map.entry("OKLAHOMA", "OK"),
        Map.entry("OREGON", "OR"), Map.entry("PENNSYLVANIA", "PA"), Map.entry("RHODE ISLAND", "RI"), Map.entry("SOUTH CAROLINA", "SC"),
        Map.entry("SOUTH DAKOTA", "SD"), Map.entry("TENNESSEE", "TN"), Map.entry("TEXAS", "TX"), Map.entry("UTAH", "UT"),
        Map.entry("VERMONT", "VT"), Map.entry("VIRGINIA", "VA"), Map.entry("WASHINGTON", "WA"), Map.entry("WASHINGTON DC", "DC"),
        Map.entry("DISTRICT OF COLUMBIA", "DC"), Map.entry("WEST VIRGINIA", "WV"), Map.entry("WISCONSIN", "WI"), Map.entry("WYOMING", "WY"));

    private StateCodes() {}

    /** "New_Jersey" / "new jersey" / "NJ" -> "NJ"; null when the name is not a known state. */
    static String of(String state) {
        if (state == null || state.isBlank()) return null;
        String name = state.trim().replace('_', ' ').replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (CODES.containsKey(name)) return CODES.get(name);
        return name.length() == 2 && CODES.containsValue(name) ? name : null;
    }
}
