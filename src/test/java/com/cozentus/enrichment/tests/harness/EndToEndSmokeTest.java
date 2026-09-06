package com.cozentus.enrichment.tests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.support.TestConfig;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves the whole stack before any step definition depends on it: the harness
 * provisions topics, starts a real service instance against them, publishes, and
 * observes the outcome — entirely through the external interface.
 */
@Tag("kafka")
class EndToEndSmokeTest {

    private static final String BOOKING = """
            {"bookingId":"%s","shipper":"ABC Logistics","origin":"%s",
             "destination":"%s","mode":"ROAD","requestedDate":"2026-09-05"}""";

    private TestConfig config;
    private KafkaServiceHarness harness;
    private ServiceController service;

    private void startWith(String citiesSource) {
        config = TestConfig.load();
        String scenarioId = TopicProvisioner.newScenarioId();
        harness = new KafkaServiceHarness(config, scenarioId);
        service = ServiceController.start(config, scenarioId,
                harness.rawTopic(), harness.enrichedTopic(), harness.flaggedTopic(), citiesSource);
    }

    private void publish(String id, String origin, String destination) {
        harness.publish(harness.rawTopic(), id, BOOKING.formatted(id, origin, destination), Map.of());
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("a misspelled booking comes back corrected on the enriched topic")
    void enrichesAMisspelledBooking() {
        startWith("classpath:/cities.json");

        publish("BKG-1", "Mumbi", "now delhi");

        assertThat(harness.awaitEnriched("BKG-1", config.awaitTimeout()))
                .as("enriched message, service log:%n%s", service.readLog())
                .get().satisfies(view -> {
                    assertThat(view.origin()).isEqualTo("Mumbai");
                    assertThat(view.destination()).isEqualTo("New Delhi");
                    assertThat(view.originalOrigin()).isEqualTo("Mumbi");
                    assertThat(view.hasOriginConfidence()).isTrue();
                });
    }

    @Test
    @DisplayName("an unmatchable city is flagged and confirmed absent from the enriched topic")
    void flagsAndDoesNotForward() {
        startWith("classpath:/cities.json");

        publish("BKG-9", "Warsaw", "Mumbai");

        assertThat(harness.awaitFlagged("BKG-9", config.awaitTimeout()))
                .get().satisfies(view -> {
                    assertThat(view.reasons()).containsExactly("UNMATCHED_ORIGIN_CITY");
                    assertThat(view.valueOf("origin")).isEqualTo("Warsaw");
                    assertThat(view.candidatesFor("origin")).isEmpty();
                });

        // TC-32, the assertion the harness exists for.
        assertThat(harness.nothingArrivedFor("BKG-9", harness.enrichedTopic(), config.absenceWindow()))
                .as("a flagged booking must never reach the enriched topic")
                .isTrue();
    }

    @Test
    @DisplayName("a correlation-id set by the publisher survives to the output message")
    void correlationIdSurvives() {
        startWith("classpath:/cities.json");

        harness.publish(harness.rawTopic(), "BKG-7",
                BOOKING.formatted("BKG-7", "Mumbi", "Pune"),
                Map.of("correlation-id", "trace-abc-123"));

        assertThat(harness.awaitEnriched("BKG-7", config.awaitTimeout()))
                .get().satisfies(view -> {
                    assertThat(view.message().header("correlation-id")).isEqualTo("trace-abc-123");
                    assertThat(view.message().header("x-enrichment-status")).isEqualTo("ENRICHED");
                });
    }

    @Test
    @DisplayName("a malformed payload is flagged and the next valid booking still processes")
    void malformedDoesNotStopThePipeline() {
        startWith("classpath:/cities.json");

        harness.publishRaw(harness.rawTopic(), "BKG-16", "{not json".getBytes());
        publish("BKG-17", "Mumbai", "Pune");

        assertThat(harness.awaitFlagged("BKG-16", config.awaitTimeout()))
                .get().satisfies(view -> {
                    assertThat(view.isMalformed()).isTrue();
                    assertThat(view.bookingId()).isNull();
                });
        assertThat(harness.awaitEnriched("BKG-17", config.awaitTimeout())).isPresent();
    }

    @Test
    @DisplayName("an instance started with an extended city list reports Delh as ambiguous")
    void ambiguityViaConfiguredCityList() {
        startWith("inline:Mumbai,New Delhi,Bangalore,Chennai,Kolkata,Pune,Hyderabad,Ahmedabad,Delhi");

        publish("BKG-13", "Delh", "Mumbai");

        assertThat(harness.awaitFlagged("BKG-13", config.awaitTimeout()))
                .as("service log:%n%s", service.readLog())
                .get().satisfies(view -> {
                    assertThat(view.reasons()).containsExactly("AMBIGUOUS_ORIGIN_CITY");
                    assertThat(view.candidatesFor("origin")).containsExactly("New Delhi", "Delhi");
                });
    }
}
