package com.cozentus.enrichment.tests.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A read-only view over a message observed on the enriched topic.
 *
 * <p>Reads fields by name from the parsed JSON rather than binding to a record,
 * so a field the suite does not know about is simply not read — the service is
 * free to add one without breaking the suite, which is what a consumer of a
 * published contract should tolerate.
 */
public record EnrichedView(ConsumedMessage message, JsonNode payload) {

    public String bookingId() {
        return text("bookingId");
    }

    public String origin() {
        return text("origin");
    }

    public String destination() {
        return text("destination");
    }

    public String shipper() {
        return text("shipper");
    }

    public String mode() {
        return text("mode");
    }

    public String requestedDate() {
        return text("requestedDate");
    }

    public String originalOrigin() {
        return enrichmentText("originalOrigin");
    }

    public String originalDestination() {
        return enrichmentText("originalDestination");
    }

    public boolean hasOriginConfidence() {
        return payload.path("enrichment").path("originConfidence").isNumber();
    }

    public boolean hasDestinationConfidence() {
        return payload.path("enrichment").path("destinationConfidence").isNumber();
    }

    /**
     * B-5. The value itself, not merely whether one is present.
     *
     * @throws AssertionError if the field is absent, so a missing confidence is
     *         reported as a missing confidence rather than as a wrong number
     */
    public double originConfidence() {
        return confidence("originConfidence");
    }

    public double destinationConfidence() {
        return confidence("destinationConfidence");
    }

    private double confidence(String field) {
        JsonNode value = payload.path("enrichment").path(field);
        if (!value.isNumber()) {
            throw new AssertionError("The enriched message carries no numeric " + field
                    + ". enrichment block was: " + payload.path("enrichment"));
        }
        return value.asDouble();
    }

    public String raw() {
        return message.payload();
    }

    private String text(String field) {
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private String enrichmentText(String field) {
        JsonNode node = payload.path("enrichment").path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
