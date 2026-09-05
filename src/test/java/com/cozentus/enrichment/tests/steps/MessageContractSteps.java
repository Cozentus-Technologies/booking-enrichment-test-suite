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
     * Downstream partitioning depends on the key, so it is asserted against the
     * booking id inside the payload rather than against the id the test happens
     * to be holding — otherwise the check is close to tautological.
     */
    @Then("the message key equals the booking id")
    public void messageKeyEqualsBookingId() {
        ConsumedMessage message = observedMessage();
        String idInPayload = Json.field(message.payload(), "bookingId");

        assertThat(message.key())
                .as("message key must equal the bookingId carried in the payload")
                .isEqualTo(idInPayload);
    }

    @Then("the message key is {string}")
    public void theMessageKeyIs(String expected) {
        assertThat(observedMessage().key()).as("message key").isEqualTo(expected);
    }

    @Then("the output message carries the correlation-id header {string}")
    public void carriesCorrelationId(String expected) {
        assertThat(observedMessage().header("correlation-id"))
                .as("a publisher's correlation-id must survive, or a booking cannot be traced")
                .isEqualTo(expected);
    }

    @Then("the output message carries an enrichment status header of {string}")
    public void carriesEnrichmentStatus(String expected) {
        assertThat(observedMessage().header("x-enrichment-status"))
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

        List<ConsumedMessage> observed = context.harness()
                .drain(context.harness().enrichedTopic()).stream()
                .filter(m -> m.key().equals(key))
                .toList();

        assertThat(observed).as("both outputs for key %s", key).hasSize(2);
        assertThat(observed.get(0).partition())
                .as("a shared key must land on one partition, or order is undefined")
                .isEqualTo(observed.get(1).partition());
        assertThat(observed.get(0).offset())
                .as("first published must hold the lower offset")
                .isLessThan(observed.get(1).offset());
    }

    private ConsumedMessage observedMessage() {
        return context.hasEnriched()
                ? context.enriched().message()
                : context.flagged().message();
    }
}
