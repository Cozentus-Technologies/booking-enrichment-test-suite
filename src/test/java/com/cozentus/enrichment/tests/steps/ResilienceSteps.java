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
}
