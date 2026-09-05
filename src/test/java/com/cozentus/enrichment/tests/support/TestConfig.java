package com.cozentus.enrichment.tests.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Properties;

/**
 * Layer 4 of the architecture: the only coupling to the system under test.
 *
 * <p>Pointing the suite at a different deployment is a configuration change and
 * nothing else — which is the whole argument for the layering.
 *
 * <p>Resolution order: system property, then the profile's properties file.
 * The profile comes from {@code -Dsuite.env}, defaulting to {@code local}.
 */
public final class TestConfig {

    private static final String DEFAULT_ENV = "local";

    private final Properties properties;
    private final String environment;

    private TestConfig(String environment, Properties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    public static TestConfig load() {
        return load(System.getProperty("suite.env", DEFAULT_ENV));
    }

    public static TestConfig load(String environment) {
        String resource = "/config/test-" + environment + ".properties";
        try (InputStream in = TestConfig.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "No configuration for environment '" + environment + "': expected " + resource);
            }
            Properties loaded = new Properties();
            loaded.load(in);
            return new TestConfig(environment, loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    public String environment() {
        return environment;
    }

    public String bootstrapServers() {
        return required("kafka.bootstrap.servers");
    }

    public String rawTopicPrefix() {
        return required("topic.raw.prefix");
    }

    public String enrichedTopicPrefix() {
        return required("topic.enriched.prefix");
    }

    public String flaggedTopicPrefix() {
        return required("topic.flagged.prefix");
    }

    public String consumerGroupPrefix() {
        return required("consumer.group.prefix");
    }

    public String readinessUrl() {
        return required("service.readiness.url");
    }

    public Duration awaitTimeout() {
        return seconds("await.timeout.seconds");
    }

    /**
     * How long an absence claim observes for. It is a bounded observation, not a
     * proof: the message could still arrive a second later. The window is stated
     * in configuration so that trade-off is visible rather than buried.
     */
    public Duration absenceWindow() {
        return seconds("absence.window.seconds");
    }

    public Duration readinessTimeout() {
        return seconds("readiness.timeout.seconds");
    }

    public Duration volumeTimeout() {
        return seconds("volume.timeout.seconds");
    }

    /** System property wins, so a single run can be redirected without editing a file. */
    public String required(String key) {
        String value = System.getProperty(key, properties.getProperty(key));
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing configuration key '" + key + "' for environment '" + environment + "'");
        }
        return value.trim();
    }

    private Duration seconds(String key) {
        String value = required(key);
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " must be a whole number of seconds, got: " + value);
        }
    }
}
