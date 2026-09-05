package com.cozentus.enrichment.tests.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link SchemaValidator} against real messages captured from the running
 * service (see the class-level payload constants), including the CH-09 mutual
 * exclusion guarantee: an enriched payload must fail the flagged schema and a
 * flagged payload must fail the enriched schema (TEST_SUITE_SPEC.md section 7.5,
 * TC-40..TC-42).
 */
class SchemaValidatorTest {

    /** Genuine booking.enriched message. */
    private static final String ENRICHED_PAYLOAD = "{\"bookingId\":\"BKG-12345\",\"shipper\":\"ABC Logistics\","
            + "\"origin\":\"Mumbai\",\"destination\":\"New Delhi\",\"mode\":\"ROAD\",\"requestedDate\":\"2026-09-05\","
            + "\"enrichment\":{\"originalOrigin\":\"Mumbi\",\"originalDestination\":\"now delhi\","
            + "\"originConfidence\":0.9666666666666667,\"destinationConfidence\":0.9333333333333333}}";

    /** Genuine booking.flagged message for an ordinary flag reason. */
    private static final String FLAGGED_PAYLOAD = "{\"bookingId\":\"BKG-9\",\"reasons\":[\"UNMATCHED_ORIGIN_CITY\"],"
            + "\"fields\":[{\"field\":\"origin\",\"reason\":\"UNMATCHED_ORIGIN_CITY\",\"value\":\"Warsaw\","
            + "\"candidates\":[]}],\"original\":{\"bookingId\":\"BKG-9\",\"shipper\":\"ABC Logistics\","
            + "\"origin\":\"Warsaw\",\"destination\":\"Mumbai\",\"mode\":\"AIR\",\"requestedDate\":\"2026-09-05\"}}";

    /** Genuine booking.flagged message for a message that never parsed at all. */
    private static final String MALFORMED_FLAGGED_PAYLOAD = "{\"bookingId\":null,\"reasons\":[\"MALFORMED_MESSAGE\"],"
            + "\"fields\":[],\"original\":\"{not json\"}";

    private final SchemaValidator validator = new SchemaValidator();

    // ---------------------------------------------------------------- TC-40 / TC-41: each payload validates against its own schema

    @Test
    void enrichedPayloadValidatesAgainstBookingEnrichedV1() {
        SchemaValidator.Result result = validator.validate(ENRICHED_PAYLOAD, "booking-enriched", 1);
        assertThat(result.isValid()).as(result::errorsAsText).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void flaggedPayloadValidatesAgainstBookingFlaggedV1() {
        SchemaValidator.Result result = validator.validate(FLAGGED_PAYLOAD, "booking-flagged", 1);
        assertThat(result.isValid()).as(result::errorsAsText).isTrue();
    }

    @Test
    void malformedFlaggedPayloadValidatesAgainstBookingFlaggedV1() {
        SchemaValidator.Result result = validator.validate(MALFORMED_FLAGGED_PAYLOAD, "booking-flagged", 1);
        assertThat(result.isValid()).as(result::errorsAsText).isTrue();
    }

    // ---------------------------------------------------------------- TC-42 / CH-09: mutual exclusion

    @Test
    void enrichedPayloadFailsTheFlaggedSchema() {
        SchemaValidator.Result result = validator.validate(ENRICHED_PAYLOAD, "booking-flagged", 1);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).isNotEmpty();
        // required (reasons, fields) missing, and additionalProperties rejects shipper/mode/etc.
        assertThat(result.errorsAsText()).contains("booking-flagged-v1");
    }

    @Test
    void flaggedPayloadFailsTheEnrichedSchema() {
        SchemaValidator.Result result = validator.validate(FLAGGED_PAYLOAD, "booking-enriched", 1);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.errorsAsText()).contains("booking-enriched-v1");
    }

    @Test
    void malformedFlaggedPayloadAlsoFailsTheEnrichedSchema() {
        SchemaValidator.Result result = validator.validate(MALFORMED_FLAGGED_PAYLOAD, "booking-enriched", 1);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).isNotEmpty();
    }

    // ---------------------------------------------------------------- TC-43: an extra unrecognised field

    @Test
    void extraUnrecognisedFieldOnAnEnrichedPayloadFailsClosedSchema() {
        // additionalProperties: false in booking-enriched-v1: the schema is a closed contract,
        // so an unrecognised field is itself something the suite must be able to catch.
        String withExtraField = ENRICHED_PAYLOAD.substring(0, ENRICHED_PAYLOAD.length() - 1)
                + ",\"unexpectedField\":\"surprise\"}";
        SchemaValidator.Result result = validator.validate(withExtraField, "booking-enriched", 1);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errorsAsText()).contains("unexpectedField");
    }

    // ---------------------------------------------------------------- Failure message quality

    @Test
    void failureMessagesNameTheJsonPointerAndAreSelfContained() {
        String missingEnrichmentBlock = "{\"bookingId\":\"BKG-1\",\"shipper\":\"ABC Logistics\","
                + "\"origin\":\"Mumbai\",\"destination\":\"New Delhi\",\"mode\":\"ROAD\","
                + "\"requestedDate\":\"2026-09-05\"}";

        SchemaValidator.Result result = validator.validate(missingEnrichmentBlock, "booking-enriched", 1);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        String message = result.getErrors().get(0);
        // Names the schema, a JSON pointer location, and what was wrong -- pasteable into a defect report.
        assertThat(message).contains("booking-enriched-v1");
        assertThat(message).contains("enrichment");
        assertThat(message).containsIgnoringCase("required");
    }

    @Test
    void invalidJsonProducesAnInvalidResultRatherThanThrowing() {
        SchemaValidator.Result result = validator.validate("{not json at all", "booking-enriched", 1);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0)).containsIgnoringCase("json");
    }

    // ---------------------------------------------------------------- TC-44: enrichment metadata detail

    @Test
    void enrichmentBlockCarriesOriginalAndCorrectedValuesPerCity() {
        SchemaValidator.Result result = validator.validate(ENRICHED_PAYLOAD, "booking-enriched", 1);
        assertThat(result.isValid()).as(result::errorsAsText).isTrue();

        String missingOriginalOrigin = ENRICHED_PAYLOAD.replace(
                "\"originalOrigin\":\"Mumbi\",", "");
        SchemaValidator.Result missingFieldResult =
                validator.validate(missingOriginalOrigin, "booking-enriched", 1);
        assertThat(missingFieldResult.isValid()).isFalse();
        assertThat(missingFieldResult.errorsAsText()).contains("originalOrigin");
    }
}
