package com.cozentus.enrichment.tests.harness;

import com.cozentus.enrichment.tests.model.ConsumedMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Subscribes to a topic and accumulates everything it sees.
 *
 * <p>Started before any publish in the scenario. A consumer that joins after a
 * message was produced may never see it, and the resulting failure looks like a
 * routing bug rather than a test-harness bug.
 *
 * <p>Everything observed is retained so the report can attach it: a failure
 * should be diagnosable from the report without reproducing it locally.
 */
public final class MessageCollector implements AutoCloseable {

    private static final Duration POLL = Duration.ofMillis(100);

    private final String topic;
    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumedMessage> received = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch assigned = new CountDownLatch(1);
    private final Thread poller;

    public MessageCollector(String bootstrapServers, String consumerGroup, String topic) {
        this.topic = topic;
        this.consumer = new KafkaConsumer<>(properties(bootstrapServers, consumerGroup));
        this.consumer.subscribe(List.of(topic));
        this.poller = Thread.ofVirtual().name("collector-" + topic).start(this::run);
    }

    private static Properties properties(String bootstrapServers, String consumerGroup) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // The topic is created fresh per scenario, so earliest is the whole of it.
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return properties;
    }

    private void run() {
        try {
            while (running.get()) {
                var records = consumer.poll(POLL);
                if (assigned.getCount() > 0 && !consumer.assignment().isEmpty()) {
                    assigned.countDown();
                }
                records.forEach(record -> received.add(toMessage(record)));
            }
        } catch (WakeupException expected) {
            // close() was called.
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    /**
     * Blocks until this consumer actually owns partitions.
     *
     * <p>Subscribing is not the same as being assigned. Publishing between the
     * two loses the message, and the scenario then fails for a reason that has
     * nothing to do with the service.
     *
     * @return true if partitions were assigned within the timeout
     */
    public boolean awaitAssignment(Duration timeout) {
        try {
            return assigned.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Everything seen so far. Correlate by key; arrival order across partitions is not meaningful. */
    public List<ConsumedMessage> received() {
        return List.copyOf(received);
    }

    public List<ConsumedMessage> receivedFor(String key) {
        return received.stream().filter(m -> key.equals(m.key())).toList();
    }

    public String topic() {
        return topic;
    }

    private static ConsumedMessage toMessage(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new HashMap<>();
        record.headers().forEach(header -> headers.put(header.key(),
                header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8)));

        return new ConsumedMessage(record.topic(), record.key(), record.value(),
                record.partition(), record.offset(),
                Instant.ofEpochMilli(record.timestamp()), headers);
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            consumer.wakeup();
            try {
                poller.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
