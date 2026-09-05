package com.cozentus.enrichment.tests.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Small helpers for reading a payload the suite published or observed. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static JsonNode parse(String payload) {
        try {
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Not valid JSON: " + payload, e);
        }
    }

    /** @return the field's text, or null when absent or explicitly null */
    public static String field(String payload, String name) {
        JsonNode node = parse(payload).path(name);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
