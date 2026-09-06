package com.cozentus.enrichment.tests.harness;

import com.cozentus.enrichment.tests.model.ConsumedMessage;
import com.cozentus.enrichment.tests.model.EnrichedView;
import com.cozentus.enrichment.tests.model.FlaggedView;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only thing in the suite that knows Kafka exists.
 *
 * <p>No step definition may call a Kafka API. Everything a scenario needs to do
 * to the service is expressed here in the service's own terms — publish a
 * booking, wait for an outcome, confirm an absence — so a client upgrade or a
 * change of transport is confined to one package.
 */
public interface ServiceHarness extends AutoCloseable {

    void publish(String topic, String key, String payload, Map<String, String> headers);

    /** For payloads that are not valid JSON, or not text at all. */
    void publishRaw(String topic, String key, byte[] payload);

    /**
     * Publishes with an explicit broker timestamp.
     *
     * <p>B-9 (TC-49) needs an inbound timestamp far enough in the past that a
     * copied-through timestamp and a freshly stamped one cannot be confused.
     */
    void publishAt(String topic, String key, String payload, java.time.Instant timestamp);

    String rawTopic();

    String enrichedTopic();

    String flaggedTopic();

    Optional<EnrichedView> awaitEnriched(String bookingId, Duration timeout);

    Optional<FlaggedView> awaitFlagged(String bookingId, Duration timeout);

    /**
     * Watches {@code topic} for the whole window and reports whether anything
     * keyed {@code bookingId} appeared.
     *
     * <p>This is a bounded observation, not a proof: the message could arrive a
     * second after the window closes. The window length is configuration, so the
     * trade-off between confidence and suite duration is visible rather than
     * buried in a constant.
     *
     * @return true if nothing appeared during the window
     */
    boolean nothingArrivedFor(String bookingId, String topic, Duration window);

    /** Everything observed on a topic so far. */
    List<ConsumedMessage> drain(String topic);

    @Override
    void close();
}
