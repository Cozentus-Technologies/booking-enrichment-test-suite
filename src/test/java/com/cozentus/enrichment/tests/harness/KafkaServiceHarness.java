package com.cozentus.enrichment.tests.harness;

import com.cozentus.enrichment.tests.model.ConsumedMessage;
import com.cozentus.enrichment.tests.model.EnrichedView;
import com.cozentus.enrichment.tests.model.FlaggedView;
import com.cozentus.enrichment.tests.support.PollUntil;
import com.cozentus.enrichment.tests.support.TestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * The Kafka implementation of {@link ServiceHarness}.
 *
 * <p>Owns a unique set of topics and a unique consumer group for the scenario,
 * provisioned on construction and removed on close, so scenarios are independent
 * and individually runnable.
 */
public final class KafkaServiceHarness implements ServiceHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TestConfig config;
    private final TopicProvisioner provisioner;
    private final KafkaProducer<String, byte[]> producer;
    private final String rawTopic;
    private final String enrichedTopic;
    private final String flaggedTopic;
    private final MessageCollector enrichedCollector;
    private final MessageCollector flaggedCollector;
    private final List<MessageCollector> collectors = new ArrayList<>();

    public KafkaServiceHarness(TestConfig config, String scenarioId) {
        this.config = config;
        this.rawTopic = config.rawTopicPrefix() + "." + scenarioId;
        this.enrichedTopic = config.enrichedTopicPrefix() + "." + scenarioId;
        this.flaggedTopic = config.flaggedTopicPrefix() + "." + scenarioId;

        this.provisioner = new TopicProvisioner(config.bootstrapServers());
        provisioner.create(List.of(rawTopic, enrichedTopic, flaggedTopic));

        this.producer = new KafkaProducer<>(producerProperties(config.bootstrapServers()));

        // Subscribed before the scenario publishes anything, and waited on until
        // partitions are actually assigned.
        String group = config.consumerGroupPrefix() + "." + scenarioId;
        this.enrichedCollector = collector(group + ".enriched", enrichedTopic);
        this.flaggedCollector = collector(group + ".flagged", flaggedTopic);
    }

    private MessageCollector collector(String group, String topic) {
        MessageCollector collector =
                new MessageCollector(config.bootstrapServers(), group, topic);
        if (!collector.awaitAssignment(config.readinessTimeout())) {
            collector.close();
            throw new IllegalStateException(
                    "Consumer for " + topic + " was never assigned partitions; "
                            + "publishing now would lose the message");
        }
        collectors.add(collector);
        return collector;
    }

    private static Properties producerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class.getName());
        // Bytes, so a scenario can publish something that is not valid UTF-8 text.
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        return properties;
    }

    @Override
    public void publish(String topic, String key, String payload, Map<String, String> headers) {
        publishRaw(topic, key, payload == null ? null : payload.getBytes(StandardCharsets.UTF_8), headers);
    }

    @Override
    public void publishRaw(String topic, String key, byte[] payload) {
        publishRaw(topic, key, payload, Map.of());
    }

    @Override
    public void publishAt(String topic, String key, String payload, java.time.Instant timestamp) {
        publishRaw(topic, key, payload == null ? null : payload.getBytes(StandardCharsets.UTF_8),
                Map.of(), timestamp.toEpochMilli());
    }

    private void publishRaw(String topic, String key, byte[] payload, Map<String, String> headers) {
        publishRaw(topic, key, payload, headers, null);
    }

    private void publishRaw(String topic, String key, byte[] payload,
                            Map<String, String> headers, Long timestamp) {
        List<Header> recordHeaders = new ArrayList<>();
        headers.forEach((name, value) -> recordHeaders.add(new RecordHeader(name,
                value == null ? null : value.getBytes(StandardCharsets.UTF_8))));
        try {
            producer.send(new ProducerRecord<>(topic, null, timestamp, key, payload,
                    recordHeaders)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing to " + topic, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not publish to " + topic, e.getCause());
        }
    }

    @Override
    public String rawTopic() {
        return rawTopic;
    }

    @Override
    public String enrichedTopic() {
        return enrichedTopic;
    }

    @Override
    public String flaggedTopic() {
        return flaggedTopic;
    }

    @Override
    public Optional<EnrichedView> awaitEnriched(String bookingId, Duration timeout) {
        return await(enrichedCollector, bookingId, timeout)
                .map(message -> new EnrichedView(message, parse(message.payload())));
    }

    @Override
    public Optional<FlaggedView> awaitFlagged(String bookingId, Duration timeout) {
        return await(flaggedCollector, bookingId, timeout)
                .map(message -> new FlaggedView(message, parse(message.payload())));
    }

    /** Correlates by key, never by arrival order: partitioned delivery is unordered by design. */
    private Optional<ConsumedMessage> await(MessageCollector collector, String bookingId, Duration timeout) {
        return PollUntil.present(() -> collector.receivedFor(bookingId).stream().findFirst(), timeout);
    }

    @Override
    public boolean nothingArrivedFor(String bookingId, String topic, Duration window) {
        MessageCollector collector = collectorFor(topic);
        boolean appeared = PollUntil.everTrue(
                () -> !collector.receivedFor(bookingId).isEmpty(), window);
        return !appeared;
    }

    @Override
    public List<ConsumedMessage> drain(String topic) {
        return collectorFor(topic).received();
    }

    private MessageCollector collectorFor(String topic) {
        if (topic.equals(enrichedTopic)) {
            return enrichedCollector;
        }
        if (topic.equals(flaggedTopic)) {
            return flaggedCollector;
        }
        throw new IllegalArgumentException(
                "No collector for " + topic + "; the suite observes " + enrichedTopic
                        + " and " + flaggedTopic);
    }

    private static JsonNode parse(String payload) {
        try {
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            // A message that reached an output topic should be valid JSON; if it
            // is not, that is a finding, so surface the payload rather than null.
            throw new IllegalStateException("Output message was not valid JSON: " + payload, e);
        }
    }

    @Override
    public void close() {
        collectors.forEach(MessageCollector::close);
        producer.close(Duration.ofSeconds(5));
        provisioner.delete(List.of(rawTopic, enrichedTopic, flaggedTopic));
        provisioner.close();
    }
}
