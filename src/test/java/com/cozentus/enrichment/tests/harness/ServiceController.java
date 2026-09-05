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
import java.util.Map;

/**
 * Starts and stops a service instance configured for one scenario's topics.
 *
 * <p>The suite drives the service entirely through its documented external
 * configuration — broker address, topic names, consumer group, city source — and
 * waits on its readiness endpoint. It never reaches inside.
 *
 * <p>Under the {@code external} profile nothing is started: the service is
 * already running somewhere and the suite only waits for it. That is the point
 * of the design, and it is three configuration values away.
 */
public final class ServiceController implements AutoCloseable {

    private final TestConfig config;
    private final int readinessPort;
    private Process process;
    private Path logFile;

    private ServiceController(TestConfig config, int readinessPort) {
        this.config = config;
        this.readinessPort = readinessPort;
    }

    /**
     * Starts an instance owning the given topics.
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
        ServiceController controller = new ServiceController(config, freePort());
        controller.launch(scenarioId, rawTopic, enrichedTopic, flaggedTopic, citiesSource);
        return controller;
    }

    private void launch(String scenarioId, String rawTopic, String enrichedTopic,
                        String flaggedTopic, String citiesSource) {
        Path jar = Path.of(config.required("service.jar.path"));
        if (!Files.exists(jar)) {
            throw new IllegalStateException(
                    "Service jar not found at " + jar + ". Build it with "
                            + "`mvn package -DskipTests` in the service repository, "
                            + "or set service.jar.path.");
        }

        List<String> command = new ArrayList<>(List.of(
                javaBinary(), "-jar", jar.toString(),
                "--kafka.bootstrap.servers=" + config.bootstrapServers(),
                "--kafka.consumer.group=service." + scenarioId,
                "--topic.raw=" + rawTopic,
                "--topic.enriched=" + enrichedTopic,
                "--topic.flagged=" + flaggedTopic,
                "--cities.source=" + citiesSource,
                "--readiness.port=" + readinessPort));

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
                    "Service never reported ready on port " + readinessPort
                            + " within " + config.readinessTimeout() + ".\nService log:\n" + log);
        }
    }

    /**
     * The suite must not publish before the service has subscribed: subscribe does
     * not replay history, so an early message is lost and the scenario fails for a
     * reason that has nothing to do with the behaviour under test.
     */
    public boolean awaitReady(Duration timeout) {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + readinessPort + "/ready"))
                .timeout(Duration.ofSeconds(2))
                .GET().build();

        return PollUntil.isTrue(() -> {
            if (process != null && !process.isAlive()) {
                return false;
            }
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
            } catch (Exception notYet) {
                return false;
            }
        }, timeout);
    }

    public int readinessPort() {
        return readinessPort;
    }

    /** The service's own log, so a failure is diagnosable without a rerun. */
    public String readLog() {
        try {
            return logFile == null ? "" : Files.readString(logFile);
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

    /** Configuration the controller needs, kept here so the failure names it. */
    public static Map<String, String> requiredKeys() {
        return Map.of("service.jar.path", "path to the built city-enrichment.jar");
    }
}
