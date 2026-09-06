package com.cozentus.enrichment.tests.steps;

import static com.cozentus.enrichment.tests.data.BookingBuilder.booking;
import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Given and When: what the scenario puts into the system.
 *
 * <p>No Kafka API appears here or in any other step class; everything goes
 * through the harness.
 */
public class PublishSteps {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final ScenarioContext context;

    // TC-51 stages a malformed payload AND a booking, then publishes them in
    // separate When steps. One slot would let the second Given overwrite the
    // first, and the scenario would silently publish the wrong thing twice.
    private String pendingId;
    private String pendingPayload;
    private String pendingRawId;
    private byte[] pendingRawPayload;
    private final Map<String, String> pendingHeaders = new LinkedHashMap<>();

    public PublishSteps(ScenarioContext context) {
        this.context = context;
    }

    private static String nextId() {
        return "BKG-%04d".formatted(SEQUENCE.incrementAndGet());
    }

    private void stage(String bookingId, String payload) {
        this.pendingId = bookingId;
        this.pendingPayload = payload;
    }

    private void stageRaw(String bookingId, byte[] payload) {
        this.pendingRawId = bookingId;
        this.pendingRawPayload = payload;
    }

    /** Rewrites a field on the booking already staged. */
    private void amendStaged(java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> change) {
        try {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode)
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(pendingPayload);
            change.accept(node);
            pendingPayload = node.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not amend the staged booking", e);
        }
    }

    // --- Given ----------------------------------------------------------

    @Given("a booking with origin {string} and destination {string}")
    public void aBookingWithOriginAndDestination(String origin, String destination) {
        String id = nextId();
        stage(id, booking(id).origin(origin).destination(destination).build());
    }

    @Given("a booking {string} with origin {string} and destination {string}")
    public void anIdentifiedBooking(String bookingId, String origin, String destination) {
        stage(bookingId, booking(bookingId).origin(origin).destination(destination).build());
    }

    @Given("a booking {string} with a null origin and destination {string}")
    public void aBookingWithNullOrigin(String bookingId, String destination) {
        stage(bookingId, booking(bookingId).nullOrigin().destination(destination).build());
    }

    @Given("a booking {string} with no origin field and destination {string}")
    public void aBookingWithNoOriginField(String bookingId, String destination) {
        stage(bookingId, booking(bookingId).withoutOrigin().destination(destination).build());
    }

    @Given("a booking {string} with a null destination and origin {string}")
    public void aBookingWithNullDestination(String bookingId, String origin) {
        stage(bookingId, booking(bookingId).origin(origin).nullDestination().build());
    }

    @Given("a booking {string} with no destination field and origin {string}")
    public void aBookingWithNoDestinationField(String bookingId, String origin) {
        stage(bookingId, booking(bookingId).origin(origin).withoutDestination().build());
    }

    /**
     * Setup, not an assertion: every scenario using this phrasing does so before
     * its When, naming the cities of the booking staged by the previous Given.
     */
    @Given("its origin is {string} and its destination is {string}")
    public void itsCitiesAre(String origin, String destination) {
        assertThat(pendingPayload).as("no Given staged a booking to give cities to").isNotNull();
        amendStaged(node -> {
            node.put("origin", origin);
            node.put("destination", destination);
        });
    }

    @Given("a booking with shipper {string}, mode {string} and requested date {string}")
    public void aBookingWithNonCityFields(String shipper, String mode, String requestedDate) {
        String id = nextId();
        stage(id, booking(id).origin("Mumbi").destination("Pune")
                .shipper(shipper).mode(mode).requestedDate(requestedDate).build());
    }

    @Given("a booking with requested date {string}")
    public void aBookingWithRequestedDate(String requestedDate) {
        String id = nextId();
        stage(id, booking(id).origin("Mumbai").destination("Pune")
                .requestedDate(requestedDate).build());
    }

    @Given("a booking with a shipper name of {int} characters")
    public void aBookingWithALongShipperName(int length) {
        String id = nextId();
        stage(id, booking(id).origin("Mumbai").destination("Pune")
                .shipper("S".repeat(length)).build());
    }

    @Given("a booking whose payload size is close to the broker's message size limit")
    public void aBookingCloseToTheMessageSizeLimit() {
        String id = nextId();
        // Under the 1 MiB default so the broker accepts it; the assertion is that
        // the service handles a large payload predictably, not that Kafka rejects it.
        stage(id, booking(id).origin("Mumbai").destination("Pune")
                .extraField("padding", "x".repeat(900_000)).build());
    }

    @Given("an extra unrecognised field {string} set to {string}")
    public void anExtraUnrecognisedField(String name, String value) {
        String id = pendingId != null ? pendingId : nextId();
        // Rebuild on top of whatever the previous Given staged.
        com.fasterxml.jackson.databind.node.ObjectNode node;
        try {
            node = (com.fasterxml.jackson.databind.node.ObjectNode)
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            pendingPayload != null ? pendingPayload
                                    : booking(id).origin("Mumbai").destination("Pune").build());
        } catch (Exception e) {
            throw new IllegalStateException("Could not add an extra field", e);
        }
        node.put(name, value);
        stage(id, node.toString());
    }

    @Given("a payload that is not valid JSON")
    public void aPayloadThatIsNotValidJson() {
        String id = nextId();
        // Truncated mid-value: Jackson rejects it, but the bookingId is still
        // textually recoverable, so the flagged message keeps its key.
        stageRaw(id, "{\"bookingId\":\"%s\",\"shipper\":\"ABC".formatted(id)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * B-9 (TC-49). The inbound timestamp is set years in the past so a copied
     * timestamp and a freshly stamped one cannot be mistaken for each other.
     */
    @io.cucumber.java.en.When("it is published to the raw topic with a timestamp of {string}")
    public void publishedWithTimestamp(String timestamp) {
        assertThat(pendingPayload).as("no Given staged a booking to publish").isNotNull();
        context.harness().publishAt(context.harness().rawTopic(), pendingId,
                pendingPayload, java.time.Instant.parse(timestamp));
        context.recordPublished(pendingId, pendingPayload, java.util.Map.of());
        publishedAt = java.time.Instant.now();
        pendingPayload = null;
    }

    /** When the suite handed the message to the broker, for TC-49 to compare against. */
    private java.time.Instant publishedAt;

    public java.time.Instant publishedAt() {
        return publishedAt;
    }

    @Given("a payload that is empty")
    public void anEmptyPayload() {
        stageRaw(nextId(), new byte[0]);
    }

    @Given("a payload that is a JSON array instead of a booking object")
    public void aJsonArrayPayload() {
        String id = nextId();
        stageRaw(id, "[{\"bookingId\":\"%s\"}]".formatted(id).getBytes(StandardCharsets.UTF_8));
    }

    @Given("the same booking published twice with the booking id {string}")
    public void theSameBookingTwice(String bookingId) {
        stage(bookingId, booking(bookingId).origin("Mumbai").destination("Pune").build());
    }

    @Given("two bookings sharing the key {string}, the first with origin {string} and destination {string} and the second with origin {string} and destination {string}")
    public void twoBookingsSharingAKey(String key, String firstOrigin, String firstDestination,
                                       String secondOrigin, String secondDestination) {
        context.recordPublished(key + "#1",
                booking(key).origin(firstOrigin).destination(firstDestination).build(), Map.of());
        context.recordPublished(key + "#2",
                booking(key).origin(secondOrigin).destination(secondDestination).build(), Map.of());
        pendingId = key;
    }

    // --- When -----------------------------------------------------------

    @When("it is published to the raw topic")
    public void itIsPublished() {
        publishPending(pendingHeaders);
    }

    @When("it is published to the raw topic with a correlation-id header {string}")
    public void itIsPublishedWithCorrelationId(String correlationId) {
        publishPending(Map.of("correlation-id", correlationId));
    }

    @When("it is published to the raw topic without a message key")
    public void itIsPublishedWithoutAKey() {
        assertThat(pendingRawPayload).as("a keyless publish is only meaningful for a raw payload")
                .isNotNull();
        context.harness().publishRaw(context.harness().rawTopic(), null, pendingRawPayload);
        // The service keys it UNKNOWN, per SPEC section 4.
        context.recordPublished("UNKNOWN", new String(pendingRawPayload, StandardCharsets.UTF_8), Map.of());
    }

    @When("the malformed payload is published to the raw topic")
    public void theMalformedPayloadIsPublished() {
        assertThat(pendingRawPayload).as("no Given staged a malformed payload").isNotNull();
        context.harness().publishRaw(context.harness().rawTopic(), pendingRawId, pendingRawPayload);
        context.recordPublished(pendingRawId,
                new String(pendingRawPayload, StandardCharsets.UTF_8), Map.of());
    }

    @When("the booking is published to the raw topic immediately afterward")
    public void theBookingIsPublishedAfterward() {
        assertThat(pendingPayload).as("no Given staged a booking").isNotNull();
        context.harness().publish(context.harness().rawTopic(), pendingId, pendingPayload, Map.of());
        context.recordPublished(pendingId, pendingPayload, Map.of());
    }

    @When("both copies are published to the raw topic")
    public void bothCopiesArePublished() {
        publishPending(Map.of());
        context.harness().publish(context.harness().rawTopic(), pendingId, pendingPayload, Map.of());
    }

    @When("they are published to the raw topic in that order")
    public void theyArePublishedInOrder() {
        for (String id : context.publishedOrder()) {
            String key = id.substring(0, id.indexOf('#'));
            context.harness().publish(context.harness().rawTopic(), key,
                    context.publishedPayload(id), Map.of());
        }
    }

    private void publishPending(Map<String, String> headers) {
        // "it" means the raw payload when that is all the scenario staged.
        if (pendingRawPayload != null && pendingPayload == null) {
            context.harness().publishRaw(context.harness().rawTopic(), pendingRawId, pendingRawPayload);
            context.recordPublished(pendingRawId,
                    new String(pendingRawPayload, StandardCharsets.UTF_8), headers);
            return;
        }
        assertThat(pendingPayload).as("no Given staged a booking to publish").isNotNull();
        context.harness().publish(context.harness().rawTopic(), pendingId, pendingPayload, headers);
        context.recordPublished(pendingId, pendingPayload, headers);
    }
}
