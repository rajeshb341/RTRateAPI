package com.automation.rater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Puts the test case inputs into the request body template, using the row -> JSON path mapping from config. */
@Slf4j
public class RaterRequestMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SEGMENT = Pattern.compile("([^\\[\\].]+)(?:\\[(\\d+)])?");

    private final RaterSettings settings;
    private final String template;

    public RaterRequestMapper(RaterSettings settings, String templateJson) {
        this.settings = settings;
        this.template = templateJson;
    }

    /** Returns the request body for the given inputs. Rows without a mapping are skipped (logged once per call). */
    public String build(Map<Integer, String> inputs) throws IOException {
        JsonNode root = MAPPER.readTree(template);
        for (Map.Entry<Integer, String> e : inputs.entrySet()) {
            String path = settings.requestPath(e.getKey());
            if (path.isEmpty()) {
                log.debug("Input row {} has no request mapping; not sent", e.getKey());
                continue;
            }
            set((ObjectNode) root, path, e.getValue());
        }
        return MAPPER.writeValueAsString(root);
    }

    /** Input rows that are written to the Rater but cannot be sent because no JSON path is configured. */
    public java.util.List<Integer> unmappedRows(java.util.Collection<Integer> rows) {
        return rows.stream().filter(r -> settings.requestPath(r).isEmpty()).toList();
    }

    private static void set(ObjectNode root, String path, String value) {
        ObjectNode current = root;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            Matcher m = SEGMENT.matcher(parts[i]);
            if (!m.matches()) throw new IllegalArgumentException("Bad request mapping path: " + path);
            String name = m.group(1);
            boolean last = i == parts.length - 1;

            if (m.group(2) == null) {
                if (last) {
                    current.put(name, value);
                } else {
                    JsonNode child = current.get(name);
                    current = child instanceof ObjectNode o ? o : current.putObject(name);
                }
            } else {
                int index = Integer.parseInt(m.group(2));
                ArrayNode array = current.get(name) instanceof ArrayNode a ? a : current.putArray(name);
                while (array.size() <= index) array.addObject();
                if (last) {
                    array.set(index, MAPPER.getNodeFactory().textNode(value));
                } else {
                    current = (ObjectNode) array.get(index);
                }
            }
        }
    }
}
