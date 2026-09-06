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
        Path data = context.config().resolvePath("sample.data.path", "Bulk sample data set");
        Path oraclePath = context.config().resolvePath("sample.oracle.path", "Bulk sample oracle");

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

        // B-8. Iterating the oracle's keys alone checks only the reasons the
        // oracle happens to mention: a reason the service emitted and the oracle
        // never expected is simply not looked at. The union of the oracle's keys
        // and the reasons the contract permits is what has to be checked, with an
        // absent oracle key meaning zero rather than meaning "skip".
        Set<String> permitted = com.cozentus.enrichment.tests.support.ReasonCoverage
                .reasonsInContract();
        Set<String> toCheck = new java.util.TreeSet<>(permitted);
        byReason.fieldNames().forEachRemaining(toCheck::add);

        assertThat(actual.keySet())
                .as("the service emitted a reason the contract does not permit")
                .isSubsetOf(permitted);

        for (String reason : toCheck) {
            assertThat(actual.getOrDefault(reason, 0))
                    .as("count of %s", reason)
                    .isEqualTo(byReason.path(reason).asInt());
        }
    }

    @Then("no booking id present on the enriched topic also appears on the flagged topic")
    public void noBookingIdOnBothTopics() {
        awaitTotal(totalExpected());

        Set<String> enriched = keys(context.harness().enrichedTopic());
        Set<String> flagged = keys(context.harness().flaggedTopic());

        // B-7: with the poll result now asserted, these sets are known to be
        // populated. Stated anyway, because a disjointness check over two empty
        // sets is exactly the failure this scenario used to hide.
        assertThat(enriched.size() + flagged.size())
                .as("both key sets must be populated before disjointness means anything")
                .isEqualTo(totalExpected());
        assertThat(enriched)
                .as("a bookingId must never appear on both output topics")
                .doesNotContainAnyElementsOf(flagged);
    }

    /**
     * B-8. The generator already knows which rows must flag, and nothing read it.
     * Totals alone cannot catch a swap: route one enriched booking to the flagged
     * topic and one flagged booking to the enriched topic and every count still
     * matches. Comparing the identities does catch it.
     */
    @Then("each booking lands on the topic its data says it should")
    public void routingMatchesTheGeneratedExpectation() {
        awaitTotal(totalExpected());

        Set<String> expectedFlagged = generated.stream()
                .filter(VolumeDataGenerator.Row::expectedFlagged)
                .map(VolumeDataGenerator.Row::bookingId)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(keys(context.harness().flaggedTopic()))
                .as("the set of flagged bookings must be exactly the set the data says "
                        + "cannot be matched, not merely the same size")
                .isEqualTo(expectedFlagged);
    }

    /** B-8. Exactly once across the two topics: neither lost nor duplicated. */
    @Then("every booking appears exactly once across the two output topics")
    public void everyBookingAppearsExactlyOnce() {
        awaitTotal(totalExpected());

        Map<String, Integer> occurrences = new java.util.LinkedHashMap<>();
        for (String topic : List.of(context.harness().enrichedTopic(),
                context.harness().flaggedTopic())) {
            observed(topic).forEach(message -> occurrences.merge(message.key(), 1, Integer::sum));
        }

        Set<String> expected = generated.isEmpty()
                ? sampleRows.stream().map(VolumeSteps::bookingIdOf)
                        .collect(java.util.stream.Collectors.toSet())
                : generated.stream().map(VolumeDataGenerator.Row::bookingId)
                        .collect(java.util.stream.Collectors.toSet());

        assertThat(occurrences.keySet())
                .as("every booking published must be accounted for, and no others")
                .isEqualTo(expected);
        assertThat(occurrences.entrySet().stream()
                .filter(entry -> entry.getValue() != 1)
                .toList())
                .as("a booking seen twice is a duplicate, not a success")
                .isEmpty();
    }

    // --- helpers --------------------------------------------------------

    /**
     * B-8. Waits for the combined total rather than one topic's share of it.
     *
     * <p>Waiting per topic burns the whole volume timeout whenever the split is
     * wrong: if the service routes an enriched booking to the flagged topic, the
     * enriched count never reaches its target, so the run sits for the full two
     * minutes before failing, and it fails saying only that one topic was short.
     * The combined total is reached as soon as processing finishes however the
     * messages were routed, so a misrouting is reported in seconds with both
     * counts in the message.
     */
    private void awaitCount(String topic, int expected, String label) {
        awaitTotal(totalExpected());

        assertThat(observed(topic))
                .as("%s count. enriched=%d, flagged=%d, %d expected in total",
                        label, observed(context.harness().enrichedTopic()).size(),
                        observed(context.harness().flaggedTopic()).size(), totalExpected())
                .hasSize(expected);
    }

    /**
     * B-7. The result of the poll is asserted, not discarded.
     *
     * <p>Discarding it meant every caller carried on with whatever had arrived so
     * far - usually nothing. TC-63 then compared two empty key sets and passed:
     * a suite that would have reported "no booking id appears on both topics"
     * against a service that had produced no output at all.
     */
    private void awaitTotal(int expected) {
        boolean reached = PollUntil.isTrue(
                () -> totalObserved() >= expected, context.config().volumeTimeout());

        assertThat(reached)
                .as("only %d of %d bookings arrived within %s (enriched=%d, flagged=%d)",
                        totalObserved(), expected, context.config().volumeTimeout(),
                        observed(context.harness().enrichedTopic()).size(),
                        observed(context.harness().flaggedTopic()).size())
                .isTrue();
    }

    private int totalObserved() {
        return observed(context.harness().enrichedTopic()).size()
                + observed(context.harness().flaggedTopic()).size();
    }

    /** However the data set was produced, this is how many bookings it holds. */
    private int totalExpected() {
        return generated.isEmpty() ? sampleRows.size() : generated.size();
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
