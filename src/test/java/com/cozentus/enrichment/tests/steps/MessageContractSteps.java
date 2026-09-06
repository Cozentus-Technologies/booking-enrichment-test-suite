package com.cozentus.enrichment.tests.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.model.ConsumedMessage;
import com.cozentus.enrichment.tests.support.Json;
import com.cozentus.enrichment.tests.support.ScenarioContext;
import io.cucumber.java.en.Then;
import java.util.List;

/** The message envelope rather than the payload: key, headers, relative order. */
public class MessageContractSteps {

    private final ScenarioContext context;

    public MessageContractSteps(ScenarioContext context) {
        this.context = context;
    }

    /**
     * B-7. The message is located by its payload, never by its key.
     *
     * <p>The harness correlates by key, so a message fetched through
     * {@code awaitEnriched} necessarily carries the key it was fetched with, and
     * asserting that key afterwards cannot fail. Worse, a service that emitted
     * the wrong key would not fail here at all: the await would simply time out
     * and the scenario would report "nothing arrived", which reads as a broken
     * environment rather than a broken key.
     *
     * <p>Draining and matching on the payload makes the key a genuine variable:
     * the message is found whatever key it carries, and the assertion then has
     * something real to say about it.
     */
    @Then("the message key equals the booking id")
    public void messageKeyEqualsBookingId() {
        ConsumedMessage message = locatedByPayload();
        String idInPayload = Json.field(message.payload(), "bookingId");

        assertThat(idInPayload)
                .as("the output payload must carry a booking id to key on")
                .isNotBlank();
        assertThat(message.key())
                .as("message key must equal the bookingId carried in the payload; "
                        + "downstream partitioning depends on it")
                .isEqualTo(idInPayload);
    }

    @Then("the message key is {string}")
    public void theMessageKeyIs(String expected) {
        assertThat(locatedByPayload().key()).as("message key").isEqualTo(expected);
    }

    /**
     * B-9 (TC-49). The timestamp on the output was not covered at all, and the
     * two possible behaviours are opposite: copying the inbound timestamp
     * through, or stamping the moment of processing. TEST_STRATEGY section 4.1
     * requires the choice to be stated and asserted either way.
     *
     * <p>The inbound timestamp is set to 2020, so a copied value fails this by
     * years rather than by milliseconds and no clock skew can mask the result.
     */
    @Then("the output message timestamp is at or after the time it was published")
    public void outputTimestampReflectsProcessing() {
        ConsumedMessage message = locatedByPayload();

        assertThat(message.timestamp())
                .as("the output carries the inbound timestamp rather than the processing "
                        + "time, so a consumer cannot tell when enrichment happened")
                .isAfter(java.time.Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Then("the output message carries the correlation-id header {string}")
    public void carriesCorrelationId(String expected) {
        assertThat(locatedByPayload().header("correlation-id"))
                .as("a publisher's correlation-id must survive, or a booking cannot be traced")
                .isEqualTo(expected);
    }

    @Then("the output message carries an enrichment status header of {string}")
    public void carriesEnrichmentStatus(String expected) {
        assertThat(locatedByPayload().header("x-enrichment-status"))
                .as("x-enrichment-status").isEqualTo(expected);
    }

    /**
     * Ordering is only meaningful within a partition, which is why both bookings
     * share a key. Offsets are compared rather than arrival order, since the
     * collector observes several partitions at once.
     */
    @Then("their outputs arrive in the same relative order they were published")
    public void outputsRetainRelativeOrder() {
        String staged = context.publishedOrder().get(0);
        String key = staged.substring(0, staged.indexOf('#'));

        // drain() is a snapshot: asserting on it straight after publishing races
        // the service. Wait for both outputs, bounded, before judging their order.
        com.cozentus.enrichment.tests.support.PollUntil.isTrue(
                () -> outputsFor(key).size() >= 2, context.config().awaitTimeout());

        List<ConsumedMessage> observed = outputsFor(key);

        assertThat(observed)
                .as("both outputs for key %s within %s.%nService log:%n%s",
                        key, context.config().awaitTimeout(), context.service().readLog())
                .hasSize(2);
        assertThat(observed.get(0).partition())
                .as("a shared key must land on one partition, or order is undefined")
                .isEqualTo(observed.get(1).partition());

        // B-7. Comparing observed.get(0) with observed.get(1) asserted nothing:
        // the collector appends in arrival order, so within one partition the
        // first element always holds the lower offset whether or not the service
        // preserved order. Reversing the two outputs would not have failed it.
        //
        // Each output is now identified by the booking it came from, so the
        // claim is about the messages themselves: the booking published first
        // must hold the lower offset.
        ConsumedMessage first = withOrigin(observed, ORIGIN_PUBLISHED_FIRST);
        ConsumedMessage second = withOrigin(observed, ORIGIN_PUBLISHED_SECOND);

        assertThat(first.offset())
                .as("the booking published first (origin %s, offset %d) must hold a lower "
                                + "offset than the one published second (origin %s, offset %d)",
                        ORIGIN_PUBLISHED_FIRST, first.offset(),
                        ORIGIN_PUBLISHED_SECOND, second.offset())
                .isLessThan(second.offset());
    }

    /**
     * The two bookings TC-39 publishes, named by the city that tells them apart.
     * They are corrected before they arrive, so the output carries the canonical
     * spelling rather than the input.
     */
    private static final String ORIGIN_PUBLISHED_FIRST = "Mumbai";
    private static final String ORIGIN_PUBLISHED_SECOND = "Pune";

    private static ConsumedMessage withOrigin(List<ConsumedMessage> messages, String origin) {
        return messages.stream()
                .filter(message -> origin.equals(Json.field(message.payload(), "origin")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No output carried origin " + origin + ". Observed: " + messages.stream()
                                .map(m -> Json.field(m.payload(), "origin") + "@" + m.offset())
                                .toList()));
    }

    private List<ConsumedMessage> outputsFor(String key) {
        return context.harness().drain(context.harness().enrichedTopic()).stream()
                .filter(message -> key.equals(message.key()))
                .toList();
    }

    /**
     * B-7. Finds this scenario's output by looking at payloads, so the key stays
     * a variable the assertion can be wrong about.
     */
    private ConsumedMessage locatedByPayload() {
        String bookingId = context.currentBookingId();
        String published = context.publishedPayload(bookingId);

        return com.cozentus.enrichment.tests.support.PollUntil.present(
                        () -> locate(bookingId, published), context.config().awaitTimeout())
                .orElseThrow(() -> new AssertionError(
                        "No output on either topic carried booking %s. Observed payloads: %s"
                                .formatted(bookingId, allOutputs().stream()
                                        .map(ConsumedMessage::payload).toList())));
    }

    private java.util.Optional<ConsumedMessage> locate(String bookingId, String published) {
        return allOutputs().stream()
                .filter(message -> carries(message, bookingId, published))
                .findFirst();
    }

    private boolean carries(ConsumedMessage message, String bookingId, String published) {
        if (bookingId.equals(Json.field(message.payload(), "bookingId"))) {
            return true;
        }
        // A malformed message has no booking id of its own; it is identified by
        // the raw text the service carried through in `original`.
        com.fasterxml.jackson.databind.JsonNode original =
                Json.parse(message.payload()).path("original");
        return original.isTextual() && original.asText().equals(published);
    }

    private List<ConsumedMessage> allOutputs() {
        List<ConsumedMessage> all = new java.util.ArrayList<>(
                context.harness().drain(context.harness().enrichedTopic()));
        all.addAll(context.harness().drain(context.harness().flaggedTopic()));
        return all;
    }
}
