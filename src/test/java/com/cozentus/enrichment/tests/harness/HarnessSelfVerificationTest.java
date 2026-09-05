package com.cozentus.enrichment.tests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.support.TestConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The suite testing itself.
 *
 * <p>A negative assertion that cannot fail is worse than no assertion: it reads
 * as coverage and provides none. TC-32 — "a flagged booking never reaches the
 * enriched topic" — is the scenario the whole harness exists to support, and it
 * rests entirely on {@link ServiceHarness#nothingArrivedFor}. So that method is
 * proven here to return false when a message really is present.
 *
 * <p>No service is started: these tests exercise the harness against the broker
 * directly, publishing to the output topics themselves.
 */
@Tag("kafka")
class HarnessSelfVerificationTest {

    private TestConfig config;
    private KafkaServiceHarness harness;
    private Duration window;

    @BeforeEach
    void setUp() {
        config = TestConfig.load("local");
        harness = new KafkaServiceHarness(config, TopicProvisioner.newScenarioId());
        window = Duration.ofSeconds(3);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("nothingArrivedFor reports true when the topic really is empty")
    void reportsTrueWhenNothingIsThere() {
        assertThat(harness.nothingArrivedFor("BKG-ABSENT", harness.enrichedTopic(), window))
                .as("nothing was published, so nothing should have arrived")
                .isTrue();
    }

    @Test
    @DisplayName("nothingArrivedFor reports FALSE when a message is present - the check that proves it works")
    void reportsFalseWhenAMessageIsPresent() {
        harness.publish(harness.enrichedTopic(), "BKG-PRESENT",
                "{\"bookingId\":\"BKG-PRESENT\"}", Map.of());

        assertThat(harness.nothingArrivedFor("BKG-PRESENT", harness.enrichedTopic(), window))
                .as("a message WAS published; an absence check that still passes is worthless")
                .isFalse();
    }

    @Test
    @DisplayName("absence is judged per booking id, not per topic")
    void absenceIsPerBookingId() {
        harness.publish(harness.enrichedTopic(), "BKG-OTHER",
                "{\"bookingId\":\"BKG-OTHER\"}", Map.of());

        assertThat(harness.nothingArrivedFor("BKG-OTHER", harness.enrichedTopic(), window)).isFalse();
        assertThat(harness.nothingArrivedFor("BKG-MINE", harness.enrichedTopic(), window))
                .as("another booking's message must not mask this one's absence")
                .isTrue();
    }

    @Test
    @DisplayName("a message published after the check begins is still detected")
    void detectsAMessageThatArrivesDuringTheWindow() throws Exception {
        Thread late = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);
                harness.publish(harness.flaggedTopic(), "BKG-LATE",
                        "{\"bookingId\":\"BKG-LATE\"}", Map.of());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        boolean nothing = harness.nothingArrivedFor("BKG-LATE", harness.flaggedTopic(),
                Duration.ofSeconds(6));
        late.join();

        assertThat(nothing)
                .as("the window must be watched throughout, not sampled once at the start")
                .isFalse();
    }

    @Test
    @DisplayName("awaitEnriched correlates by key rather than arrival order")
    void correlatesByKeyNotOrder() {
        harness.publish(harness.enrichedTopic(), "BKG-1", "{\"bookingId\":\"BKG-1\",\"origin\":\"Mumbai\"}", Map.of());
        harness.publish(harness.enrichedTopic(), "BKG-2", "{\"bookingId\":\"BKG-2\",\"origin\":\"Pune\"}", Map.of());
        harness.publish(harness.enrichedTopic(), "BKG-3", "{\"bookingId\":\"BKG-3\",\"origin\":\"Chennai\"}", Map.of());

        assertThat(harness.awaitEnriched("BKG-2", config.awaitTimeout()))
                .get().extracting(v -> v.origin()).isEqualTo("Pune");
    }

    @Test
    @DisplayName("awaitEnriched gives up rather than hanging when nothing arrives")
    void awaitIsBounded() {
        long start = System.nanoTime();

        assertThat(harness.awaitEnriched("BKG-NEVER", Duration.ofSeconds(2))).isEmpty();

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMillis).as("bounded wait").isBetween(1_800L, 6_000L);
    }

    @Test
    @DisplayName("each harness owns its own topics, so scenarios cannot contaminate each other")
    void topicsAreUniquePerScenario() {
        try (KafkaServiceHarness other =
                     new KafkaServiceHarness(config, TopicProvisioner.newScenarioId())) {

            assertThat(other.rawTopic()).isNotEqualTo(harness.rawTopic());
            assertThat(other.enrichedTopic()).isNotEqualTo(harness.enrichedTopic());

            harness.publish(harness.enrichedTopic(), "BKG-X", "{\"bookingId\":\"BKG-X\"}", Map.of());

            assertThat(other.nothingArrivedFor("BKG-X", other.enrichedTopic(), window))
                    .as("a message on one scenario's topic must be invisible to another's")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("topics are created with three partitions, so ordering by key is a real property")
    void topicsHaveThreePartitions() {
        try (TopicProvisioner provisioner = new TopicProvisioner(config.bootstrapServers())) {
            assertThat(provisioner.partitionCount(harness.rawTopic())).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("publishRaw sends bytes untouched, so a non-JSON payload can be tested")
    void publishRawSendsArbitraryBytes() {
        harness.publishRaw(harness.flaggedTopic(), "BKG-RAW", "{not json".getBytes());

        assertThat(harness.nothingArrivedFor("BKG-RAW", harness.flaggedTopic(), window)).isFalse();
        assertThat(harness.drain(harness.flaggedTopic()))
                .anySatisfy(m -> assertThat(m.payload()).isEqualTo("{not json"));
    }
}
