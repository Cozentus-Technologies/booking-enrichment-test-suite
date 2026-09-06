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
 * <p>On a profile that owns its broker it provisions a unique set of topics and
 * a unique consumer group for the scenario on construction and removes the
 * topics on close, so scenarios are independent and individually runnable.
 *
 * <p>E-1: against a deployment the suite did not provision it has none of that
 * authority. The configured topic names are the deployment's own and are used
 * verbatim, nothing is created and nothing is deleted, consumers start at the
 * end of topics that already carry history, and isolation comes from
 * {@link RunScope} - a booking-id namespace unique to the run - instead of from
 * owning the channel.
 */
public final class KafkaServiceHarness implements ServiceHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TestConfig config;
    private final RunScope scope;
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
        this.scope = RunScope.forEnvironment(config);

        boolean ownsTopics = !config.isExternal();
        this.rawTopic = topic(config.rawTopicPrefix(), scenarioId, ownsTopics);
        this.enrichedTopic = topic(config.enrichedTopicPrefix(), scenarioId, ownsTopics);
        this.flaggedTopic = topic(config.flaggedTopicPrefix(), scenarioId, ownsTopics);

        if (ownsTopics) {
            this.provisioner = new TopicProvisioner(config.bootstrapServers());
            provisioner.create(List.of(rawTopic, enrichedTopic, flaggedTopic));
        } else {
            // No admin client at all, rather than one that is asked for nothing:
            // creating or deleting a topic on someone else's broker is the
            // failure this profile exists to avoid, and an idle Admin sitting
            // here is a way back into it.
            this.provisioner = null;
        }

        this.producer = new KafkaProducer<>(producerProperties(config.bootstrapServers()));

        // Subscribed before the scenario publishes anything, and waited on until
        // partitions are actually assigned.
        String group = config.consumerGroupPrefix() + "." + scenarioId;
        MessageCollector.StartPosition start = ownsTopics
                ? MessageCollector.StartPosition.EARLIEST
                : MessageCollector.StartPosition.END;
        this.enrichedCollector = collector(group + ".enriched", enrichedTopic, start);
        this.flaggedCollector = collector(group + ".flagged", flaggedTopic, start);
    }

    /**
     * The scenario suffix is what makes a topic this scenario's own. A
     * deployment the suite did not provision is already bound to fixed names it
     * did not get from the suite, so appending anything there names a topic that
     * does not exist and that nothing is listening to.
     */
    private static String topic(String configured, String scenarioId, boolean ownsTopics) {
        return ownsTopics ? configured + "." + scenarioId : configured;
    }

    private MessageCollector collector(String group, String topic,
                                       MessageCollector.StartPosition start) {
        MessageCollector collector =
                new MessageCollector(config.bootstrapServers(), group, topic, start);
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
        // The single point at which a booking id enters the deployment, so the
        // single point at which it is stamped with this run's namespace. Key and
        // payload together, because a step that reads the id back out of the
        // payload has to see the same id the message was keyed with.
        String scopedKey = scope.qualify(key);
        byte[] scopedPayload = scope.qualifyPayload(key, payload);
        try {
            producer.send(new ProducerRecord<>(topic, null, timestamp, scopedKey, scopedPayload,
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
        return PollUntil.present(() -> observedFor(collector, bookingId).stream().findFirst(), timeout);
    }

    @Override
    public boolean nothingArrivedFor(String bookingId, String topic, Duration window) {
        MessageCollector collector = collectorFor(topic);
        boolean appeared = PollUntil.everTrue(
                () -> !observedFor(collector, bookingId).isEmpty(), window);
        return !appeared;
    }

    @Override
    public List<ConsumedMessage> drain(String topic) {
        return observed(collectorFor(topic));
    }

    /**
     * Everything on the topic that this run put there, in the ids the feature
     * file wrote.
     *
     * <p>Both halves matter on a shared topic and neither costs anything on a
     * topic the suite owns. Without the filter a scenario correlates on another
     * run's booking of the same name; without the localisation every assertion
     * above the harness would have to know about a namespace that is purely a
     * transport concern.
     */
    private List<ConsumedMessage> observed(MessageCollector collector) {
        return collector.received().stream()
                .filter(scope::published)
                .map(scope::localise)
                .toList();
    }

    private List<ConsumedMessage> observedFor(MessageCollector collector, String bookingId) {
        return observed(collector).stream()
                .filter(message -> bookingId.equals(message.key()))
                .toList();
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
        // Null exactly when the topics were not this suite's to create, and so
        // are not its to delete either. Deleting the deployment's raw topic
        // because a scenario finished would be the worst thing this suite could
        // do to an environment it was only meant to observe.
        if (provisioner != null) {
            provisioner.delete(List.of(rawTopic, enrichedTopic, flaggedTopic));
            provisioner.close();
        }
    }
}
