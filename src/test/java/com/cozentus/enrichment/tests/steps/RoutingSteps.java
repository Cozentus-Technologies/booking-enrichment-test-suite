package com.cozentus.enrichment.tests.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.model.EnrichedView;
import com.cozentus.enrichment.tests.model.FlaggedView;
import com.cozentus.enrichment.tests.support.ScenarioContext;
import io.cucumber.java.en.Then;
import java.util.Optional;

/** Which topic a booking landed on, and — just as importantly — which it did not. */
public class RoutingSteps {

    private final ScenarioContext context;

    public RoutingSteps(ScenarioContext context) {
        this.context = context;
    }

    @Then("it lands on the enriched topic")
    public void itLandsOnTheEnrichedTopic() {
        String bookingId = context.currentBookingId();
        Optional<EnrichedView> view = context.harness()
                .awaitEnriched(bookingId, context.config().awaitTimeout());

        assertThat(view)
                .as("an enriched message for %s within %s.%nService log:%n%s",
                        bookingId, context.config().awaitTimeout(), context.service().readLog())
                .isPresent();
        context.observed(view.get());
    }

    @Then("it lands on the flagged topic")
    public void itLandsOnTheFlaggedTopic() {
        String bookingId = context.currentBookingId();
        Optional<FlaggedView> view = context.harness()
                .awaitFlagged(bookingId, context.config().awaitTimeout());

        assertThat(view)
                .as("a flagged message for %s within %s.%nService log:%n%s",
                        bookingId, context.config().awaitTimeout(), context.service().readLog())
                .isPresent();
        context.observed(view.get());
    }

    @Then("the booking lands on the enriched topic")
    public void theBookingLandsOnTheEnrichedTopic() {
        itLandsOnTheEnrichedTopic();
    }

    @Then("the booking lands on the {string} route")
    public void theBookingLandsOnRoute(String route) {
        // Scenarios say "ENRICHED"/"FLAGGED"; topics are lower case.
        if (route.toLowerCase(java.util.Locale.ROOT).contains("enriched")) {
            itLandsOnTheEnrichedTopic();
        } else {
            itLandsOnTheFlaggedTopic();
        }
    }

    /**
     * The negative assertion the brief calls for, and the reason the harness
     * exists. It watches for the whole configured window rather than sampling
     * once, and it is a bounded observation rather than a proof.
     */
    @Then("it is confirmed absent from the enriched topic after a bounded wait")
    public void confirmedAbsentFromEnriched() {
        assertAbsent(context.harness().enrichedTopic(), "enriched");
    }

    @Then("it is confirmed absent from the flagged topic after a bounded wait")
    public void confirmedAbsentFromFlagged() {
        assertAbsent(context.harness().flaggedTopic(), "flagged");
    }

    private void assertAbsent(String topic, String label) {
        String bookingId = context.currentBookingId();
        // Recorded so the report can state how long absence was observed for;
        // the claim is a bounded observation, not a proof, and the window is
        // the whole of its strength.
        context.absenceWindowUsed("%s watched on %s for %s"
                .formatted(bookingId, label, context.config().absenceWindow()));

        assertThat(context.harness().nothingArrivedFor(bookingId, topic,
                context.config().absenceWindow()))
                .as("%s must never reach the %s topic; watched for %s",
                        bookingId, label, context.config().absenceWindow())
                .isTrue();
    }

    @Then("it lands on exactly one of the enriched or flagged topics")
    public void itLandsOnExactlyOneTopic() {
        String bookingId = context.currentBookingId();
        Optional<EnrichedView> enriched = context.harness()
                .awaitEnriched(bookingId, context.config().awaitTimeout());

        if (enriched.isPresent()) {
            context.observed(enriched.get());
            assertThat(context.harness().nothingArrivedFor(bookingId,
                    context.harness().flaggedTopic(), context.config().absenceWindow()))
                    .as("%s reached the enriched topic, so it must not also be flagged", bookingId)
                    .isTrue();
            return;
        }

        Optional<FlaggedView> flagged = context.harness()
                .awaitFlagged(bookingId, context.config().awaitTimeout());
        assertThat(flagged)
                .as("%s reached neither output topic.%nService log:%n%s",
                        bookingId, context.service().readLog())
                .isPresent();
        context.observed(flagged.get());
        assertThat(context.harness().nothingArrivedFor(bookingId,
                context.harness().enrichedTopic(), context.config().absenceWindow()))
                .as("%s was flagged, so it must not also be enriched", bookingId)
                .isTrue();
    }
}
