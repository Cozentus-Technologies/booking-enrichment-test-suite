package com.cozentus.enrichment.tests.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.model.ConsumedMessage;
import com.cozentus.enrichment.tests.support.Json;
import com.cozentus.enrichment.tests.support.ScenarioContext;
import io.cucumber.java.en.Then;
import java.util.List;

/** Bad input, and duplicates. */
public class ResilienceSteps {

    private final ScenarioContext context;

    public ResilienceSteps(ScenarioContext context) {
        this.context = context;
    }

    /**
     * Delivery is at-least-once and the service is deliberately not idempotent,
     * so two identical raw messages produce two enriched messages. The assertion
     * is that both are correct and neither is corrupted — not that the second is
     * suppressed, which would be a different service.
     */
    @Then("two enriched messages for {string} arrive on the enriched topic")
    public void twoEnrichedMessagesArrive(String bookingId) {
        boolean both = com.cozentus.enrichment.tests.support.PollUntil.isTrue(
                () -> countFor(bookingId) >= 2, context.config().awaitTimeout());

        assertThat(both)
                .as("expected two messages for %s, saw %d.%nService log:%n%s",
                        bookingId, countFor(bookingId), context.service().readLog())
                .isTrue();
    }

    @Then("both are correctly enriched")
    public void bothAreCorrectlyEnriched() {
        List<ConsumedMessage> messages = context.harness()
                .drain(context.harness().enrichedTopic());

        assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
        assertThat(messages).allSatisfy(message -> {
            assertThat(Json.field(message.payload(), "origin")).isEqualTo("Mumbai");
            assertThat(Json.field(message.payload(), "destination")).isEqualTo("Pune");
        });
    }

    private long countFor(String bookingId) {
        return context.harness().drain(context.harness().enrichedTopic()).stream()
                .filter(m -> bookingId.equals(m.key()))
                .count();
    }

    /**
     * B-9 (TC-56). Nothing covered a consumer restart, yet redelivery on restart
     * is the whole reason offsets are committed after processing rather than
     * before. A service that committed early would lose whatever was in flight,
     * and no scenario would have noticed.
     */
    @io.cucumber.java.en.Given("{int} bookings are published to the raw topic")
    public void bookingsArePublished(int count) {
        for (int i = 0; i < count; i++) {
            String id = "BKG-RESTART-%03d".formatted(i);
            published.add(id);
            context.harness().publish(context.harness().rawTopic(), id,
                    com.cozentus.enrichment.tests.data.BookingBuilder.booking(id)
                            .origin("Mumbai").destination("Pune").build(),
                    java.util.Map.of());
        }
    }

    private final java.util.List<String> published = new java.util.ArrayList<>();

    /** How much work was still outstanding when the service was killed. */
    private int outstandingAtRestart = -1;

    @io.cucumber.java.en.When("the service is restarted on the same consumer group before all are processed")
    public void serviceIsRestarted() {
        // Same scenario id, so the same consumer group and the same topics: a
        // new group would replay from the beginning and prove nothing about
        // redelivery, and new topics would have no messages at all.
        outstandingAtRestart = published.size() - observedIds().size();
        context.service().close();
        context.replaceService(com.cozentus.enrichment.tests.harness.ServiceController.start(
                context.config(), context.scenarioId(),
                context.harness().rawTopic(), context.harness().enrichedTopic(),
                context.harness().flaggedTopic(), "classpath:/cities.json"));
    }

    @io.cucumber.java.en.Then("every booking is accounted for across both output topics")
    public void everyBookingAccountedFor() {
        java.util.Set<String> expected = java.util.Set.copyOf(published);

        boolean allArrived = com.cozentus.enrichment.tests.support.PollUntil.isTrue(
                () -> observedIds().containsAll(expected), context.config().volumeTimeout());

        java.util.Set<String> missing = new java.util.TreeSet<>(expected);
        missing.removeAll(observedIds());

        assertThat(allArrived)
                .as("%d of %d bookings never arrived after the restart: %s",
                        missing.size(), expected.size(), missing)
                .isTrue();

        // Without this the scenario could pass having restarted a service that
        // had already finished, which proves nothing about redelivery. If this
        // fails, the batch is too small to outlive the restart on this machine -
        // the scenario needs more bookings, not a weaker assertion.
        assertThat(outstandingAtRestart)
                .as("the restart landed after all %d bookings were already processed, "
                        + "so nothing was in flight and redelivery was never exercised",
                        expected.size())
                .isGreaterThan(0);
    }

    private java.util.Set<String> observedIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String topic : java.util.List.of(context.harness().enrichedTopic(),
                context.harness().flaggedTopic())) {
            context.harness().drain(topic).forEach(m -> ids.add(m.key()));
        }
        return ids;
    }
}
