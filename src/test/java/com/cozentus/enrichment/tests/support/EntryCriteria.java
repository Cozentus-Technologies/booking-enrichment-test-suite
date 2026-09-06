package com.cozentus.enrichment.tests.support;

import com.cozentus.enrichment.tests.data.TestDataLoader;
import com.cozentus.enrichment.tests.harness.TopicProvisioner;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Entry criteria (capability F-21): checks that must hold before any scenario
 * runs, so a misconfigured environment is reported as "environment not ready"
 * rather than surfacing as a confusing scenario failure.
 *
 * <p>Checks: the test data table parses, the message contracts resolve on the
 * classpath, the broker is reachable, and the service readiness endpoint
 * responds. Each check is independently runnable and never throws out of the
 * runner — a check whose underlying call fails is reported as a failed check
 * carrying the exception's message as its detail.
 *
 * <p>This class is deliberately not wired into any hook or the build: another
 * engineer owns that wiring. It only needs to be callable.
 */
public final class EntryCriteria {

    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(3);

    private static final List<String> CONTRACT_SCHEMAS = List.of(
            "contracts/booking-raw-v1.schema.json",
            "contracts/booking-enriched-v1.schema.json",
            "contracts/booking-flagged-v1.schema.json");

    /** Any topic name works here: reaching the broker at all is the check. */
    private static final String PROBE_TOPIC = "entry-criteria-probe";

    /** One check's name, pass/fail, and a human-readable detail. */
    public record CheckResult(String name, boolean passed, String detail) {
    }

    /** The outcome of running every entry criterion. */
    public record Report(List<CheckResult> checks) {

        public boolean allPassed() {
            return checks.stream().allMatch(CheckResult::passed);
        }

        /** Readable lines suitable for logging, one per check. */
        public List<String> lines() {
            return checks.stream()
                    .map(check -> (check.passed() ? "[PASS] " : "[FAIL] ")
                            + check.name() + " - " + check.detail())
                    .toList();
        }
    }

    private EntryCriteria() {
    }

    /** Runs all entry criteria against the given configuration. */
    public static Report run(TestConfig config) {
        return new Report(List.of(
                dataTableParses(),
                contractsAvailable(),
                brokerReachable(config),
                readinessResponds(config)));
    }

    /** Check 1: {@link TestDataLoader#load()} succeeds and returns a non-empty list. */
    public static CheckResult dataTableParses() {
        return safely("Data table parses", () -> {
            List<?> cases = TestDataLoader.load();
            if (cases.isEmpty()) {
                return new CheckResult("Data table parses", false,
                        "TestDataLoader.load() returned no rows");
            }
            return new CheckResult("Data table parses", true, cases.size() + " row(s) loaded");
        });
    }

    /** Check 2: the three message contract schemas resolve on the classpath. */
    public static CheckResult contractsAvailable() {
        return safely("Contracts available", () -> {
            List<String> missing = CONTRACT_SCHEMAS.stream()
                    .filter(resource -> !resourceExists(resource))
                    .toList();
            if (!missing.isEmpty()) {
                return new CheckResult("Contracts available", false,
                        "Missing schema(s) on classpath: " + missing);
            }
            return new CheckResult("Contracts available", true,
                    CONTRACT_SCHEMAS.size() + " schema(s) resolved: " + CONTRACT_SCHEMAS);
        });
    }

    /**
     * Check 3: the broker is reachable. Reaching it at all is the check, so a
     * {@code false} answer from {@link TopicProvisioner#exists(String)} still
     * counts as reachable — only an exception is a failure.
     */
    public static CheckResult brokerReachable(TestConfig config) {
        return safely("Broker reachable", () -> {
            try (TopicProvisioner provisioner = new TopicProvisioner(config.bootstrapServers())) {
                boolean exists = provisioner.exists(PROBE_TOPIC);
                return new CheckResult("Broker reachable", true,
                        "Connected to " + config.bootstrapServers()
                                + " (probe topic present=" + exists + ")");
            }
        });
    }

    /**
     * Check 4: the configured readiness endpoint responds. Bounded to a short
     * timeout so a missing service fails fast instead of hanging the check.
     */
    public static CheckResult readinessResponds(TestConfig config) {
        // Only meaningful where the suite does not start the service itself.
        // Under local and ci a service instance is started per scenario on an
        // ephemeral port, so nothing is listening at the configured URL before
        // the run and a failure here would be an artefact of the check rather
        // than a fact about the environment.
        if (!config.isExternal()) {
            return new CheckResult("Readiness endpoint responds", true,
                    "not applicable under the " + config.environment()
                            + " profile: the suite starts a service per scenario");
        }
        return safely("Readiness endpoint responds", () -> {
            String url = config.readinessUrl();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(READINESS_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(READINESS_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ready = response.statusCode() >= 200 && response.statusCode() < 300;
            return new CheckResult("Readiness endpoint responds", ready,
                    url + " -> HTTP " + response.statusCode());
        });
    }

    private static boolean resourceExists(String resource) {
        try (InputStream in = EntryCriteria.class.getClassLoader().getResourceAsStream(resource)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Runs one check, guaranteeing a {@link CheckResult} comes back rather than
     * an exception: any throwable escaping {@code check} is reported as a
     * failed check whose detail is the throwable's message.
     */
    static CheckResult safely(String name, Callable<CheckResult> check) {
        try {
            return check.call();
        } catch (Exception e) {
            String message = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            return new CheckResult(name, false, message);
        }
    }
}
