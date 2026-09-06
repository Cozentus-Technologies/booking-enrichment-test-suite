package com.cozentus.enrichment.tests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.model.ConsumedMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E-1. Proves the two things the run scope has to get right.
 *
 * <p>On a profile that owns its topics it must be invisible: the local path is
 * the one 101 scenarios already depend on, and a namespace nobody needs is a
 * new way for it to break.
 *
 * <p>On a shared deployment it must be symmetric. The harness qualifies a
 * booking id on the way out and removes the qualification on the way back, so
 * every step definition above it keeps comparing the ids the feature file
 * wrote. An asymmetry here would not fail loudly; it would quietly correlate
 * nothing and every scenario would time out.
 */
class RunScopeTest {

    private static ConsumedMessage message(String key, String payload) {
        return new ConsumedMessage("booking.enriched", key, payload, 0, 0L, Instant.EPOCH, Map.of());
    }

    // ---- a profile that owns its topics needs no namespace ----------------

    @Test
    void unscopedLeavesKeysPayloadsAndMessagesExactlyAsTheyWere() {
        RunScope scope = RunScope.unscoped();

        assertThat(scope.prefix()).isEmpty();
        assertThat(scope.qualify("BKG-3001")).isEqualTo("BKG-3001");
        assertThat(scope.qualifyPayload("BKG-3001", "{\"bookingId\":\"BKG-3001\"}".getBytes(StandardCharsets.UTF_8)))
                .asString().isEqualTo("{\"bookingId\":\"BKG-3001\"}");

        ConsumedMessage observed = message("BKG-3001", "{\"bookingId\":\"BKG-3001\"}");
        assertThat(scope.published(observed)).isTrue();
        assertThat(scope.localise(observed)).isEqualTo(observed);
    }

    // ---- a shared deployment needs one, and it has to round-trip ----------

    @Test
    void qualifyingThenLocalisingReturnsTheIdTheFeatureFileWrote() {
        RunScope scope = RunScope.withPrefix("RUN-abcd1234-");

        String published = scope.qualify("BKG-3001");
        assertThat(published).isEqualTo("RUN-abcd1234-BKG-3001");

        ConsumedMessage echoed = message(published,
                "{\"bookingId\":\"RUN-abcd1234-BKG-3001\",\"origin\":\"Mumbai\"}");
        ConsumedMessage localised = scope.localise(echoed);

        assertThat(localised.key()).isEqualTo("BKG-3001");
        assertThat(localised.payload()).isEqualTo("{\"bookingId\":\"BKG-3001\",\"origin\":\"Mumbai\"}");
    }

    @Test
    void thePayloadCarriesTheSameQualifiedIdAsTheKey() {
        RunScope scope = RunScope.withPrefix("RUN-abcd1234-");

        byte[] qualified = scope.qualifyPayload("BKG-3001",
                "{\"bookingId\":\"BKG-3001\",\"origin\":\"Mumbai\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(qualified, StandardCharsets.UTF_8))
                .isEqualTo("{\"bookingId\":\"RUN-abcd1234-BKG-3001\",\"origin\":\"Mumbai\"}");
    }

    /**
     * A truncated payload is exactly what TC-50 publishes, and it still has to
     * carry this run's id or the flagged message it provokes cannot be told
     * from another run's.
     */
    @Test
    void aPayloadThatIsNotValidJsonIsStillQualified() {
        RunScope scope = RunScope.withPrefix("RUN-abcd1234-");

        byte[] qualified = scope.qualifyPayload("BKG-9001",
                "{\"bookingId\":\"BKG-9001\",\"shipper\":\"ABC".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(qualified, StandardCharsets.UTF_8))
                .isEqualTo("{\"bookingId\":\"RUN-abcd1234-BKG-9001\",\"shipper\":\"ABC");
    }

    @Test
    void aPayloadThatIsNotTextAtAllSurvivesByteForByte() {
        RunScope scope = RunScope.withPrefix("RUN-abcd1234-");
        byte[] notUtf8 = {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, 0x00, (byte) 0xFF};

        assertThat(scope.qualifyPayload("BKG-9001", notUtf8)).isEqualTo(notUtf8);
    }

    @Test
    void aKeylessPublishStaysKeyless() {
        RunScope scope = RunScope.withPrefix("RUN-abcd1234-");

        assertThat(scope.qualify(null)).isNull();
        assertThat(scope.qualifyPayload(null, "{}".getBytes(StandardCharsets.UTF_8)))
                .asString().isEqualTo("{}");
    }

    // ---- correlate only on ids this run published -------------------------

    @Test
    void anotherRunsMessageOnTheSameTopicIsNotThisRunsBusiness() {
        RunScope scope = RunScope.withPrefix("RUN-abcd1234-");

        assertThat(scope.published(message("RUN-99999999-BKG-3001",
                "{\"bookingId\":\"RUN-99999999-BKG-3001\"}"))).isFalse();
        assertThat(scope.published(message("BKG-3001", "{\"bookingId\":\"BKG-3001\"}"))).isFalse();
    }

    /**
     * TC-52 publishes without a key and the service keys the result UNKNOWN, so
     * the key cannot say whose it is. The payload still carries the qualified
     * id, and that is what has to be looked at.
     */
    @Test
    void aMessageKeyedUnknownIsClaimedFromItsPayload() {
        RunScope scope = RunScope.withPrefix("RUN-abcd1234-");

        assertThat(scope.published(message("UNKNOWN",
                "{\"bookingId\":\"UNKNOWN\",\"original\":\"RUN-abcd1234-BKG-9001\"}"))).isTrue();
        assertThat(scope.published(message("UNKNOWN",
                "{\"bookingId\":\"UNKNOWN\",\"original\":\"RUN-99999999-BKG-9001\"}"))).isFalse();
    }

    @Test
    void everyRunGetsItsOwnPrefixInTheDocumentedShape() {
        assertThat(RunScope.unique().prefix()).matches("RUN-[0-9a-f]{8}-");
        assertThat(RunScope.unique().prefix()).isNotEqualTo(RunScope.unique().prefix());
    }
}
