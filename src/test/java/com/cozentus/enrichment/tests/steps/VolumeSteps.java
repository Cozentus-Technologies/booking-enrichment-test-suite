package com.cozentus.enrichment.tests.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.data.VolumeDataGenerator;
import com.cozentus.enrichment.tests.model.ConsumedMessage;
import com.cozentus.enrichment.tests.support.Json;
import com.cozentus.enrichment.tests.support.PollUntil;
import com.cozentus.enrichment.tests.support.ScenarioContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Correctness at scale. Nothing here measures throughput; the assertions are
 * that nothing is lost, nothing is double-counted, and nothing crosses onto the
 * wrong topic once the volume is past what a human would eyeball.
 */
public class VolumeSteps {

    private static final long SEED = 42L;

    private final ScenarioContext context;

    private List<VolumeDataGenerator.Row> generated = List.of();
    private List<String> sampleRows = List.of();
    private JsonNode oracle;

    public VolumeSteps(ScenarioContext context) {
        this.context = context;
    }

    // --- Given ----------------------------------------------------------

    @Given("{int} bookings where {int} contain unmatchable cities")
    public void bookingsWhereSomeAreUnmatchable(int total, int invalid) {
        generated = VolumeDataGenerator.generate(total, invalid, SEED);
    }

    /**
     * Reused from the service repository rather than generated afresh: that
     * oracle is labelled by running the real matcher over each row, so it cannot
     * drift from the matching contract the way a hand-maintained table would.
     */
    @Given("the bulk sample data set and its labelled oracle")
    public void theBulkSampleAndItsOracle() {
        Path data = Path.of(context.config().required("sample.data.path"));
        Path oraclePath = Path.of(context.config().required("sample.oracle.path"));

        assertThat(data).as("committed sample; build the service repo first").exists();
        assertThat(oraclePath).as("committed oracle").exists();

        try {
            sampleRows = Files.readAllLines(data, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .toList();
            oracle = Json.parse(Files.readString(oraclePath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the sample data set", e);
        }
    }

    // --- When -----------------------------------------------------------

    @When("they are published to the raw topic")
    public void theyArePublished() {
        generated.forEach(row -> context.harness()
                .publish(context.harness().rawTopic(), row.bookingId(), row.payload(), Map.of()));
    }

    @When("the full data set is published to the raw topic")
    public void theFullDataSetIsPublished() {
        sampleRows.forEach(line -> context.harness().publishRaw(
                context.harness().rawTopic(), bookingIdOf(line), line.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Closer to a real ingestion profile than sequential publishing, and where
     * message loss actually shows up.
     */
    @When("the full data set is published to the raw topic from several publishers at once")
    public void publishedConcurrently() {
        int publishers = 4;
        try (ExecutorService pool = Executors.newFixedThreadPool(publishers)) {
            for (int p = 0; p < publishers; p++) {
                int offset = p;
                pool.submit(() -> {
                    for (int i = offset; i < sampleRows.size(); i += publishers) {
                        String line = sampleRows.get(i);
                        context.harness().publishRaw(context.harness().rawTopic(),
                                bookingIdOf(line), line.getBytes(StandardCharsets.UTF_8));
                    }
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination(context.config().volumeTimeout().toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Publishers did not finish within the volume timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing concurrently", e);
        }
    }

    // --- Then -----------------------------------------------------------

    @Then("{int} bookings arrive on the enriched topic")
    public void bookingsArriveOnEnriched(int expected) {
        awaitCount(context.harness().enrichedTopic(), expected, "enriched");
    }

    @Then("{int} bookings arrive on the flagged topic")
    public void bookingsArriveOnFlagged(int expected) {
        awaitCount(context.harness().flaggedTopic(), expected, "flagged");
    }

    @Then("no booking is lost")
    public void noBookingIsLost() {
        int expected = generated.isEmpty() ? sampleRows.size() : generated.size();

        assertThat(observed(context.harness().enrichedTopic()).size()
                + observed(context.harness().flaggedTopic()).size())
                .as("every booking must reach exactly one output topic")
                .isEqualTo(expected);
    }

    @Then("every booking in the data set is accounted for across both output topics")
    public void everyBookingAccountedFor() {
        awaitTotal(sampleRows.size());
        noBookingIsLost();
    }

    @Then("the count of messages on the enriched topic matches the oracle's enriched count")
    public void enrichedCountMatchesOracle() {
        int expected = oracle.path("totals").path("enriched").asInt();
        awaitCount(context.harness().enrichedTopic(), expected, "enriched");
    }

    @Then("the count of messages on the flagged topic matches the oracle's flagged count")
    public void flaggedCountMatchesOracle() {
        int expected = oracle.path("totals").path("flagged").asInt();
        awaitCount(context.harness().flaggedTopic(), expected, "flagged");
    }

    @Then("the count of flagged messages for each reason matches the oracle's count for that reason")
    public void reasonCountsMatchOracle() {
        awaitTotal(sampleRows.size());

        Map<String, Integer> actual = new java.util.LinkedHashMap<>();
        for (ConsumedMessage message : observed(context.harness().flaggedTopic())) {
            Json.parse(message.payload()).path("reasons")
                    .forEach(reason -> actual.merge(reason.asText(), 1, Integer::sum));
        }

        JsonNode byReason = oracle.path("totals").path("byReason");
        byReason.fieldNames().forEachRemaining(reason ->
                assertThat(actual.getOrDefault(reason, 0))
                        .as("count of %s", reason)
                        .isEqualTo(byReason.path(reason).asInt()));
    }

    @Then("no booking id present on the enriched topic also appears on the flagged topic")
    public void noBookingIdOnBothTopics() {
        awaitTotal(sampleRows.isEmpty() ? generated.size() : sampleRows.size());

        Set<String> enriched = keys(context.harness().enrichedTopic());
        Set<String> flagged = keys(context.harness().flaggedTopic());

        assertThat(enriched)
                .as("a bookingId must never appear on both output topics")
                .doesNotContainAnyElementsOf(flagged);
    }

    // --- helpers --------------------------------------------------------

    private void awaitCount(String topic, int expected, String label) {
        boolean reached = PollUntil.isTrue(
                () -> observed(topic).size() >= expected, context.config().volumeTimeout());

        assertThat(reached)
                .as("expected %d %s messages, saw %d after %s",
                        expected, label, observed(topic).size(), context.config().volumeTimeout())
                .isTrue();
        assertThat(observed(topic)).as("%s count", label).hasSize(expected);
    }

    private void awaitTotal(int expected) {
        PollUntil.isTrue(() -> observed(context.harness().enrichedTopic()).size()
                        + observed(context.harness().flaggedTopic()).size() >= expected,
                context.config().volumeTimeout());
    }

    private List<ConsumedMessage> observed(String topic) {
        return context.harness().drain(topic);
    }

    private Set<String> keys(String topic) {
        return new HashSet<>(new ArrayList<>(observed(topic).stream()
                .map(ConsumedMessage::key).toList()));
    }

    /** Recovered textually, because a deliberately malformed row will not parse. */
    private static String bookingIdOf(String line) {
        var matcher = java.util.regex.Pattern
                .compile("\"bookingId\"\\s*:\\s*\"([^\"]+)\"").matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }
}
