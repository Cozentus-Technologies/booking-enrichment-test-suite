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
    /**
     * B-6. A malformed message never parsed, so it has no booking id and no
     * per-field flags. Asserting the reason alone would pass for a message that
     * carried a fabricated id or an invented field list beside it.
     */
    @Then("the flagged booking id is null")
    public void flaggedBookingIdIsNull() {
        assertThat(context.flagged().bookingId())
                .as("a message that never parsed cannot have yielded a booking id")
                .isNull();
    }

    @Then("the flagged fields list is empty")
    public void flaggedFieldsListIsEmpty() {
        assertThat(context.flagged().flaggedFields())
                .as("a message that never parsed has no fields to flag")
                .isEmpty();
    }

    /**
     * B-6. The contract allows {@code original} to be either an object or a
     * string, and which one it is carries meaning: a string says the service
     * never parsed the message. Stating the branch in the scenario stops the
     * comparison step quietly accepting whichever shape arrived.
     */
    @Then("the flagged original is carried as raw text")
    public void flaggedOriginalIsRawText() {
        assertThat(context.flagged().original().isTextual())
                .as("an unparseable message must be carried as the raw text that arrived, "
                        + "not as an object the service invented; it was: %s",
                        context.flagged().original().getNodeType())
                .isTrue();
    }

    @Then("the flagged original is carried as a booking object")
    public void flaggedOriginalIsAnObject() {
        assertThat(context.flagged().original().isObject())
                .as("a message that parsed must carry its booking back as an object; it was: %s",
                        context.flagged().original().getNodeType())
                .isTrue();
    }

    /**
     * Compares against what this scenario actually published, not a literal
     * repeated in the feature: a copy would drift from the Given, and would then
     * assert that the service echoed the wrong thing correctly.
     */
    @Then("the flagged original equals the payload published")
    public void flaggedOriginalEqualsWhatWasPublished() {
        String published = context.publishedPayload(context.currentBookingId());
        assertThat(published)
                .as("no payload was recorded for %s, so there is nothing to compare",
                        context.currentBookingId())
                .isNotNull();

        String original = context.flagged().originalText();
        if (context.flagged().original().isTextual()) {
            assertThat(original)
                    .as("the raw text of an unparseable message must survive verbatim")
                    .isEqualTo(published);
            return;
        }
        // Parsed and rejected on content: compare field by field, because key
        // order and formatting are not part of the contract.
        assertThat(readTree(original))
                .as("the original booking carried through unchanged")
                .isEqualTo(readTree(published));
    }

    private static com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError("Not JSON: " + json, e);
        }
    }

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
