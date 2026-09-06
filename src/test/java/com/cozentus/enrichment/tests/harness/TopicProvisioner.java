package com.cozentus.enrichment.tests.harness;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

/**
 * Creates a fresh set of topics per scenario and removes them afterwards.
 *
 * <p>Without this, scenarios contaminate each other: a message left on a shared
 * topic by one scenario is indistinguishable from one produced by the next, and
 * the suite cannot run in parallel or in a different order.
 */
public final class TopicProvisioner implements AutoCloseable {

    /** Three, matching the service's own topics, so ordering-by-key is a real property. */
    private static final int PARTITIONS = 3;
    private static final short REPLICATION = 1;
    /** Short on purpose: this client only probes, so a hang is worse than a retry. */
    /**
     * How long to wait on the admin client before calling the broker unreachable.
     *
     * <p>Three seconds, not the client default of sixty. This is the deadline a
     * dead broker is discovered on, and it is paid by every check that probes
     * one, so it sets the floor on how quickly a run can abort and say so. A
     * broker that is up answers a metadata request in single-digit
     * milliseconds; one that needs three seconds is not one the suite should be
     * trusting a timing-sensitive assertion against.
     */
    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(3);

    private final Admin admin;

    public TopicProvisioner(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) ADMIN_TIMEOUT.toMillis());
        // A-5: request.timeout.ms alone is not enough. default.api.timeout.ms
        // governs how long the admin client retries across requests, and at its
        // 60s default an unreachable broker took a minute to report, which is
        // long enough that a person assumes the run has hung.
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) ADMIN_TIMEOUT.toMillis());
        properties.put(AdminClientConfig.RETRIES_CONFIG, 1);
        this.admin = Admin.create(properties);
    }

    /** A short, unique suffix shared by one scenario's topics and consumer group. */
    public static String newScenarioId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public void create(Collection<String> topics) {
        try {
            admin.createTopics(topics.stream()
                    .map(name -> new NewTopic(name, PARTITIONS, REPLICATION))
                    .toList()).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted creating topics " + topics, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not create topics " + topics, e.getCause());
        }
    }

    /** Best effort: a leftover topic is untidy, not a test failure. */
    public void delete(Collection<String> topics) {
        if (topics.isEmpty()) {
            return;
        }
        try {
            admin.deleteTopics(List.copyOf(topics)).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof UnknownTopicOrPartitionException)) {
                System.err.println("Could not delete topics " + topics + ": " + e.getCause());
            }
        }
    }

    public boolean exists(String topic) {
        return topicNames().contains(topic);
    }

    /**
     * Every topic the broker will admit to.
     *
     * <p>E-3: the preflight reports on three topics at once and has to say which
     * of them is missing. Three {@link #exists} calls would be three round trips
     * and, against a broker that goes away between them, three different answers.
     */
    public Set<String> topicNames() {
        try {
            return admin.listTopics().names().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Set.of();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not list topics", e.getCause());
        }
    }

    public int partitionCount(String topic) {
        try {
            return admin.describeTopics(List.of(topic)).allTopicNames().get()
                    .get(topic).partitions().size();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not describe " + topic, e.getCause());
        }
    }

    @Override
    public void close() {
        admin.close(ADMIN_TIMEOUT);
    }
}
