package com.cozentus.enrichment.tests.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.model.EnrichedView;
import com.cozentus.enrichment.tests.support.ScenarioContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Then;

/** What an enriched message contains. */
public class EnrichmentSteps {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScenarioContext context;

    public EnrichmentSteps(ScenarioContext context) {
        this.context = context;
    }

    @Then("its origin is {string}")
    public void itsOriginIs(String expected) {
        assertThat(context.enriched().origin())
                .as("corrected origin").isEqualTo(expected);
    }

    @Then("its destination is {string}")
    public void itsDestinationIs(String expected) {
        assertThat(context.enriched().destination())
                .as("corrected destination").isEqualTo(expected);
    }

    /**
     * B-5. Exactly 1.0 means the value resolved without fuzzy matching. The
     * suite never asserts against the confidence floor: copying that constant
     * in would make the suite and the service agree by construction, and would
     * turn a deliberate retune of the threshold into a suite failure.
     */
    @Then("the origin confidence is exactly 1.0")
    public void originConfidenceIsExactlyOne() {
        assertThat(context.enriched().originConfidence())
                .as("origin took the exact path, so no correction was applied")
                .isEqualTo(1.0d);
    }

    @Then("the destination confidence is exactly 1.0")
    public void destinationConfidenceIsExactlyOne() {
        assertThat(context.enriched().destinationConfidence())
                .as("destination took the exact path, so no correction was applied")
                .isEqualTo(1.0d);
    }

    @Then("the origin confidence is below 1.0")
    public void originConfidenceIsBelowOne() {
        assertThat(context.enriched().originConfidence())
                .as("origin was corrected, so it cannot have matched exactly")
                .isLessThan(1.0d);
    }

    @Then("the destination confidence is below 1.0")
    public void destinationConfidenceIsBelowOne() {
        assertThat(context.enriched().destinationConfidence())
                .as("destination was corrected, so it cannot have matched exactly")
                .isLessThan(1.0d);
    }

    @Then("the enrichment metadata records the original origin {string} and the original destination {string}")
    public void metadataRecordsOriginals(String origin, String destination) {
        EnrichedView view = context.enriched();
        assertThat(view.originalOrigin()).as("originalOrigin").isEqualTo(origin);
        assertThat(view.originalDestination()).as("originalDestination").isEqualTo(destination);
    }

    @Then("a confidence score is present for the origin correction")
    public void confidencePresentForOrigin() {
        assertThat(context.enriched().hasOriginConfidence()).as("originConfidence").isTrue();
    }

    @Then("a confidence score is present for the destination correction")
    public void confidencePresentForDestination() {
        assertThat(context.enriched().hasDestinationConfidence())
                .as("destinationConfidence").isTrue();
    }

    /** Fields unrelated to city matching must come through untouched. */
    @Then("the shipper, mode and requested date are unchanged")
    public void nonCityFieldsAreUnchanged() {
        EnrichedView view = context.enriched();
        JsonNode published = publishedJson(view.bookingId());

        assertThat(view.shipper()).as("shipper").isEqualTo(published.path("shipper").asText());
        assertThat(view.mode()).as("mode").isEqualTo(published.path("mode").asText());
        assertThat(view.requestedDate()).as("requestedDate")
                .isEqualTo(published.path("requestedDate").asText());
    }

    /**
     * A plain date with no timezone. Any attempt to parse and re-serialise it
     * risks a day shift, which in a cross-border logistics context is a real
     * defect, so the assertion is byte-identity rather than date equality.
     */
    @Then("the requested date on the output is byte-identical to {string}")
    public void requestedDateIsByteIdentical(String expected) {
        assertThat(context.enriched().requestedDate())
                .as("requestedDate must pass through as an opaque string")
                .isEqualTo(expected);
    }

    @Then("if it lands on the enriched topic, the shipper name is unchanged")
    public void shipperUnchangedIfEnriched() {
        if (!context.hasEnriched()) {
            return;
        }
        EnrichedView view = context.enriched();
        assertThat(view.shipper())
                .as("a long shipper name must pass through unchanged")
                .isEqualTo(publishedJson(view.bookingId()).path("shipper").asText());
    }

    private JsonNode publishedJson(String bookingId) {
        String payload = context.publishedPayload(bookingId);
        if (payload == null) {
            payload = context.publishedPayload(context.currentBookingId());
        }
        try {
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Published payload was not JSON: " + payload, e);
        }
    }
}
