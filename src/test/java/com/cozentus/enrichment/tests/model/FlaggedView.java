package com.cozentus.enrichment.tests.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** A read-only view over a message observed on the flagged topic. */
public record FlaggedView(ConsumedMessage message, JsonNode payload) {

    /** Null for a malformed message, which never parsed far enough to have one. */
    public String bookingId() {
        JsonNode node = payload.path("bookingId");
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /** In the order the service emitted them: origin before destination. */
    public List<String> reasons() {
        List<String> reasons = new ArrayList<>();
        payload.path("reasons").forEach(node -> reasons.add(node.asText()));
        return List.copyOf(reasons);
    }

    public List<String> flaggedFields() {
        List<String> fields = new ArrayList<>();
        payload.path("fields").forEach(node -> fields.add(node.path("field").asText()));
        return List.copyOf(fields);
    }

    /** The value as it arrived, which an encoding test asserts is byte-identical. */
    public String valueOf(String field) {
        for (JsonNode node : payload.path("fields")) {
            if (field.equals(node.path("field").asText())) {
                JsonNode value = node.path("value");
                return value.isNull() || value.isMissingNode() ? null : value.asText();
            }
        }
        return null;
    }

    public String reasonFor(String field) {
        for (JsonNode node : payload.path("fields")) {
            if (field.equals(node.path("field").asText())) {
                return node.path("reason").asText();
            }
        }
        return null;
    }

    public List<String> candidatesFor(String field) {
        for (JsonNode node : payload.path("fields")) {
            if (field.equals(node.path("field").asText())) {
                List<String> candidates = new ArrayList<>();
                node.path("candidates").forEach(c -> candidates.add(c.asText()));
                return List.copyOf(candidates);
            }
        }
        return List.of();
    }

    public boolean isMalformed() {
        return reasons().contains("MALFORMED_MESSAGE");
    }

    public String raw() {
        return message.payload();
    }
}
