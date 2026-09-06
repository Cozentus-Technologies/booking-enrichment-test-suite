package com.cozentus.enrichment.tests.harness;

import com.cozentus.enrichment.tests.support.PollUntil;
import com.cozentus.enrichment.tests.support.TestConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts and stops a service instance configured for one scenario's topics, or,
 * against a deployment the suite did not provision, starts nothing and waits.
 *
 * <p>The suite drives the service entirely through its documented external
 * configuration — broker address, topic names, consumer group, city source — and
 * waits on its readiness endpoint. It never reaches inside.
 *
 * <p>E-1: {@code start} used to call {@code launch} whatever the profile said,
 * so the {@code external} profile could never actually be run - it always needed
 * a jar on disk and always started a second service beside the deployment it was
 * supposed to be testing. The branch below is the whole difference.
 */
public final class ServiceController implements AutoCloseable {

    /** Bounded so a host that is not listening fails fast rather than hanging a poll. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT)
            .build();

    /** One readiness answer: the status code, or -1 with the reason it did not arrive. */
    public record Readiness(int status, String detail) {

        public boolean ready() {
            return status >= 200 && status < 300;
        }
    }

    private final TestConfig config;
    private final URI readinessUri;
    private Process process;
    private Path logFile;

    private ServiceController(TestConfig config, URI readinessUri) {
        this.config = config;
        this.readinessUri = readinessUri;
    }

    /**
     * Starts an instance owning the given topics, or attaches to a running
     * deployment when the suite did not provision one.
     *
     * @param citiesSource the reference list, e.g. {@code classpath:/cities.json}
     *                     or {@code inline:Mumbai,New Delhi,Delhi} for ambiguity
     */
    public static ServiceController start(TestConfig config,
                                          String scenarioId,
                                          String rawTopic,
                                          String enrichedTopic,
                                          String flaggedTopic,
                                          String citiesSource) {
        if (config.isExternal()) {
            return attach(config);
        }
        ServiceController controller = new ServiceController(
                config, URI.create("http://localhost:" + freePort() + "/ready"));
        controller.launch(scenarioId, rawTopic, enrichedTopic, flaggedTopic, citiesSource);
        return controller;
    }

    /**
     * E-1. Starts no process and reads no {@code service.jar.path}: that is what
     * lets the suite run on a machine where the jar does not exist, which is the
     * acceptance criterion for this work and the ordinary case for a deployment
     * somebody else owns.
     *
     * <p>The readiness poll still happens, on the profile's own longer timeout.
     * Subscribe does not replay history, so publishing before the deployment is
     * serving loses the message and the scenario then fails for a reason that
     * has nothing to do with the behaviour under test.
     */
    private static ServiceController attach(TestConfig config) {
        ServiceController controller =
                new ServiceController(config, URI.create(config.readinessUrl()));

        if (!controller.awaitReady(config.readinessTimeout())) {
            throw new IllegalStateException("""
                    The deployment never answered ready at %s within %s.
                      suite.env       %s
                      broker          %s
                    Nothing is started under suite.env=external - the deployment
                    has to be running already. `bash run-tests.sh preflight` says
                    which of broker, topics and readiness is missing."""
                    .formatted(config.readinessUrl(), config.readinessTimeout(),
                            config.environment(), config.bootstrapServers()));
        }
        return controller;
    }

    private void launch(String scenarioId, String rawTopic, String enrichedTopic,
                        String flaggedTopic, String citiesSource) {
        Path jar = config.resolvePath("service.jar.path", "Service jar");

        List<String> command = new ArrayList<>(List.of(
                javaBinary(), "-jar", jar.toString(),
                "--kafka.bootstrap.servers=" + config.bootstrapServers(),
                "--kafka.consumer.group=service." + scenarioId,
                "--topic.raw=" + rawTopic,
                "--topic.enriched=" + enrichedTopic,
                "--topic.flagged=" + flaggedTopic,
                "--cities.source=" + citiesSource,
                "--readiness.port=" + readinessUri.getPort()));

        try {
            logFile = Files.createTempFile("service-" + scenarioId + "-", ".log");
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the service: " + command, e);
        }

        if (!awaitReady(config.readinessTimeout())) {
            String log = readLog();
            close();
            throw new IllegalStateException(
                    "Service never reported ready on port " + readinessUri.getPort()
                            + " within " + config.readinessTimeout() + ".\nService log:\n" + log);
        }
    }

    /**
     * The suite must not publish before the service has subscribed: subscribe does
     * not replay history, so an early message is lost and the scenario fails for a
     * reason that has nothing to do with the behaviour under test.
     */
    public boolean awaitReady(Duration timeout) {
        return PollUntil.isTrue(() -> {
            // A-5: a process that has already exited will never become ready, so
            // waiting out the remaining timeout only delays the diagnosis. Fail
            // immediately with its own log, which says why it died.
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException(
                        "The service exited before reporting ready (exit code "
                                + process.exitValue() + ").\nService log:\n" + readLog());
            }
            return probe(readinessUri).ready();
        }, timeout);
    }

    /**
     * One bounded GET against a readiness URL.
     *
     * <p>Shared with {@link Preflight} so the suite has a single definition of
     * "the deployment is answering". Two definitions drift, and the one that
     * drifts is the one only the preflight uses.
     */
    public static Readiness probe(URI uri) {
        try {
            HttpResponse<Void> response = HTTP.send(
                    HttpRequest.newBuilder(uri).timeout(PROBE_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return new Readiness(response.statusCode(), "HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Readiness(-1, "interrupted");
        } catch (Exception notAnswering) {
            return new Readiness(-1, notAnswering.getClass().getSimpleName()
                    + (notAnswering.getMessage() == null ? "" : ": " + notAnswering.getMessage()));
        }
    }

    public URI readinessUri() {
        return readinessUri;
    }

    /** The service's own log, so a failure is diagnosable without a rerun. */
    public String readLog() {
        if (logFile == null) {
            // Says so, rather than returning an empty string: a reader looking at
            // a failed scenario has to be able to tell "the suite never had this
            // process" from "the service logged nothing".
            return config.isExternal()
                    ? "(no service log: under suite.env=" + config.environment()
                            + " the deployment was started by someone else)"
                    : "";
        }
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            return "(could not read " + logFile + ": " + e.getMessage() + ")";
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * Asks the OS for a free port and releases it. There is a small race between
     * releasing and binding; it is preferable to a fixed port, which collides
     * every time two scenarios run at once rather than occasionally.
     */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not allocate a port for the service", e);
        }
    }

    @Override
    public void close() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }
}
