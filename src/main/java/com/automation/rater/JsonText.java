package com.automation.rater;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/** Turns JSON values into the plain text used for test data and comparison (no 1.0E3, no trailing zeros). */
public final class JsonText {
    private JsonText() {}

    public static String of(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        if (node.isNumber()) return number(node.decimalValue());
        if (node.isValueNode()) return node.asText();
        return node.toString();
    }

    public static String number(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        return (v.scale() < 0 ? v.setScale(0) : v).toPlainString();
    }

    public static String number(double value) {
        return number(BigDecimal.valueOf(value));
    }
}
