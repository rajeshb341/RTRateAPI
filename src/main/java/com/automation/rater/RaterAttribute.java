package com.automation.rater;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One Rater row: its position in the sheet, its D/E/F identity, current value and dropdown values. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RaterAttribute {
    private int row;
    private String role;               // INPUT or OUTPUT
    private String parent;             // column D
    private String entity;             // column E
    private String name;               // column F
    private JsonNode current;          // current value of column C
    private List<String> values = new ArrayList<>();
    private Map<String, List<String>> valuesByParent = new LinkedHashMap<>();
    private Integer dependsOnRow;
    private String validation;

    /** Parent/Entity/Name. Not unique on its own (e.g. rows 62-75), so the row is always shown with it. */
    public String path() {
        return parent + "/" + entity + "/" + name;
    }

    public String label() {
        return path() + " (C" + row + ")";
    }

    public String currentText() {
        return JsonText.of(current);
    }
}
