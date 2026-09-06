package com.cozentus.enrichment.tests.tools;

import com.cozentus.enrichment.tests.data.BookingBuilder;
import com.cozentus.enrichment.tests.harness.KafkaServiceHarness;
import com.cozentus.enrichment.tests.harness.ServiceController;
import com.cozentus.enrichment.tests.harness.TopicProvisioner;
import com.cozentus.enrichment.tests.model.EnrichedView;
import com.cozentus.enrichment.tests.model.FlaggedView;
import com.cozentus.enrichment.tests.support.TestConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B-3. Measures what the service actually does with a candidate boundary input,
 * so a row can be written from an observation instead of an assumption.
 *
 * <p>A boundary row asserted from a threshold the author believed was in force
 * is worthless: if the belief is wrong the row passes for the wrong reason, and
 * the boundary it claims to pin is not pinned at all. This publishes each input
 * through the same black-box interface the suite uses and prints the outcome,
 * the canonical value and the confidence the service reported.
 *
 * <p>Run: {@code mvn test-compile exec:java -Pprobe -Dexec.args="Punr Oune ..."}
 */
public final class BoundaryProbe {

    private static final Duration WAIT = Duration.ofSeconds(20);

    /** The default reference list, the same one the ordinary scenarios run against. */
    private static final String CITIES = "classpath:/cities.json";

    private BoundaryProbe() {
    }

    public static void main(String[] args) {
        List<String> inputs = args.length > 0 ? List.of(args)
                : List.of("Punr", "Oune", "Bangalr", "Bangaloreee", "  New   Delhi ");

        TestConfig config = TestConfig.load();
        System.out.printf("%-16s %-10s %-12s %-10s %s%n",
                "INPUT", "OUTCOME", "CANONICAL", "CONFIDENCE", "REASON");

        for (String input : inputs) {
            String scenarioId = TopicProvisioner.newScenarioId();
            try (KafkaServiceHarness harness = new KafkaServiceHarness(config, scenarioId)) {
                ServiceController.start(config, scenarioId, harness.rawTopic(),
                        harness.enrichedTopic(), harness.flaggedTopic(), CITIES);
                probeOne(harness, input);
            } catch (RuntimeException e) {
                System.out.printf("%-16s ERROR %s%n", display(input), e.getMessage());
            }
        }
    }

    private static void probeOne(KafkaServiceHarness harness, String input) {
        String id = "PROBE-" + Math.abs(input.hashCode());
        harness.publish(harness.rawTopic(), id,
                BookingBuilder.booking(id).origin(input).destination("Mumbai").build(),
                Map.of());

        Optional<EnrichedView> enriched = harness.awaitEnriched(id, WAIT);
        if (enriched.isPresent()) {
            EnrichedView view = enriched.get();
            System.out.printf("%-16s %-10s %-12s %-10s %s%n", display(input), "ENRICHED",
                    view.origin(), format(view.payload()), "-");
            return;
        }

        Optional<FlaggedView> flagged = harness.awaitFlagged(id, WAIT);
        if (flagged.isPresent()) {
            System.out.printf("%-16s %-10s %-12s %-10s %s%n", display(input), "FLAGGED",
                    "-", "-", flagged.get().reasons());
            return;
        }
        System.out.printf("%-16s %-10s%n", display(input), "NOTHING");
    }

    /** The confidence as the service reported it, or "-" when absent. */
    private static String format(com.fasterxml.jackson.databind.JsonNode node) {
        var confidence = node.path("enrichment").path("originConfidence");
        return confidence.isNumber() ? confidence.asText() : "-";
    }

    /** Keeps padding visible, which is the whole point of one of the rows. */
    private static String display(String input) {
        return "[" + input.replace(" ", "·") + "]";
    }
}
