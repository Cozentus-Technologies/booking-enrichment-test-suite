package com.cozentus.enrichment.tests.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.model.FlaggedView;
import com.cozentus.enrichment.tests.support.ScenarioContext;
import io.cucumber.java.en.Then;

/** What a flagged message contains: which field failed, why, and with what candidates. */
public class FlaggingSteps {

    private final ScenarioContext context;

    public FlaggingSteps(ScenarioContext context) {
        this.context = context;
    }

    @Then("the reason is {string}")
    public void theReasonIs(String reason) {
        assertThat(context.flagged().reasons())
                .as("flag reasons").containsExactly(reason);
    }

    @Then("the reasons are {string} and {string}, in that order")
    public void theReasonsAreInOrder(String first, String second) {
        assertThat(context.flagged().reasons())
                .as("both reasons, origin before destination")
                .containsExactly(first, second);
    }

    @Then("the reason concerns the origin field")
    public void reasonConcernsOrigin() {
        assertReasonConcerns("origin");
    }

    @Then("the reason concerns the destination field")
    public void reasonConcernsDestination() {
        assertReasonConcerns("destination");
    }

    private void assertReasonConcerns(String field) {
        FlaggedView view = context.flagged();

        assertThat(view.flaggedFields())
                .as("the flagged fields on %s", view.raw())
                .contains(field);
        assertThat(view.reasonFor(field))
                .as("reason recorded against %s", field)
                .isNotNull()
                .endsWith(field.toUpperCase() + "_CITY");
    }

    @Then("the candidates are {string} and {string}, in reference order")
    public void theCandidatesAre(String first, String second) {
        FlaggedView view = context.flagged();
        String field = view.flaggedFields().isEmpty() ? "origin" : view.flaggedFields().get(0);

        assertThat(view.candidatesFor(field))
                .as("candidates for %s, in reference-file order", field)
                .containsExactly(first, second);
    }

    /**
     * A name the reference list does not contain is expected to be flagged; what
     * matters is that the value comes back exactly as published, not mangled by a
     * charset assumption somewhere in the pipeline.
     */
    @Then("the origin value on the flagged message is byte-identical to the value that was published")
    public void originValueIsByteIdentical() {
        FlaggedView view = context.flagged();
        String published = com.cozentus.enrichment.tests.support.Json
                .field(context.publishedPayload(context.currentBookingId()), "origin");

        assertThat(view.valueOf("origin"))
                .as("the value must survive the round trip unchanged")
                .isEqualTo(published);
    }

    @Then("the malformed payload lands on the flagged topic with reason {string}")
    public void malformedLandsWithReason(String reason) {
        // The malformed payload is the first thing published in these scenarios.
        String bookingId = context.publishedOrder().get(0);
        var view = context.harness().awaitFlagged(bookingId, context.config().awaitTimeout());

        assertThat(view).as("a flagged message for the malformed payload %s.%nService log:%n%s",
                        bookingId, context.service().readLog())
                .isPresent();
        context.observed(view.get());
        assertThat(view.get().reasons()).containsExactly(reason);
    }
}
