package com.cozentus.enrichment.tests.steps;

import com.cozentus.enrichment.tests.harness.KafkaServiceHarness;
import com.cozentus.enrichment.tests.harness.ServiceController;
import com.cozentus.enrichment.tests.harness.TopicProvisioner;
import com.cozentus.enrichment.tests.support.EntryCriteria;
import com.cozentus.enrichment.tests.support.ScenarioContext;
import com.cozentus.enrichment.tests.support.TestConfig;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Per-scenario setup and teardown: fresh topics, a fresh consumer group, and a
 * service instance owning them, so scenarios are order-independent and each is
 * individually runnable.
 */
public class Hooks {

    private static final String DEFAULT_CITIES = "classpath:/cities.json";

    /**
     * Scenarios needing a reference list other than the default.
     *
     * <p>The ambiguity case only exists when two candidates are equally valid, so
     * it needs "Delhi" alongside "New Delhi". A black-box suite cannot reach in
     * and add it, so it starts the service configured differently instead. Kept
     * in one place rather than scattered, and the scenario is tagged {@code @slow}
     * because of it.
     */
    private static final Map<String, String> CITIES_BY_TAG = Map.of(
            "@TC-29", "inline:Mumbai,New Delhi,Bangalore,Chennai,Kolkata,Pune,Hyderabad,Ahmedabad,Delhi");

    /**
     * F-21: entry criteria are verified once, before the first scenario runs.
     * A misconfigured environment otherwise surfaces as sixty-odd confusing
     * scenario failures instead of one clear statement of what is not ready.
     */
    private static volatile boolean entryCriteriaChecked = false;

    private final ScenarioContext context;

    public Hooks(ScenarioContext context) {
        this.context = context;
    }

    @Before
    public void startIsolatedService(Scenario scenario) {
        TestConfig config = TestConfig.load();
        verifyEntryCriteriaOnce(config);
        String scenarioId = TopicProvisioner.newScenarioId();

        try {
            KafkaServiceHarness harness = new KafkaServiceHarness(config, scenarioId);
            ServiceController service = ServiceController.start(config, scenarioId,
                    harness.rawTopic(), harness.enrichedTopic(), harness.flaggedTopic(),
                    citiesSourceFor(scenario));
            context.bind(config, harness, service, scenarioId);
        } catch (RuntimeException prerequisiteFailed) {
            // Spec 8.11: cucumber.json has no notion of blocked. Recording the
            // cause keeps "we could not run it" distinct from "we chose not to"
            // and from "it does not work" - three different facts a project
            // manager has to be able to tell apart.
            context.blocked(prerequisiteFailed.getMessage() == null
                    ? prerequisiteFailed.toString()
                    : prerequisiteFailed.getMessage());
            throw prerequisiteFailed;
        }
    }

    private static synchronized void verifyEntryCriteriaOnce(TestConfig config) {
        if (entryCriteriaChecked) {
            return;
        }
        entryCriteriaChecked = true;

        EntryCriteria.Report report = EntryCriteria.run(config);
        report.lines().forEach(line -> System.out.println("  " + line));
        if (!report.allPassed()) {
            throw new IllegalStateException(
                    "Entry criteria not met, so the run cannot be trusted:\n"
                            + String.join("\n", report.lines()));
        }
    }

    private static String citiesSourceFor(Scenario scenario) {
        return scenario.getSourceTagNames().stream()
                .filter(CITIES_BY_TAG::containsKey)
                .findFirst()
                .map(CITIES_BY_TAG::get)
                .orElse(DEFAULT_CITIES);
    }

    /**
     * Attaches what was published and what was observed to every scenario, so a
     * failure is diagnosable from the report without reproducing it locally.
     */
    @After
    public void attachEvidenceAndTearDown(Scenario scenario) {
        try {
            String published = context.publishedOrder().stream()
                    .map(id -> id + " -> " + context.publishedPayload(id))
                    .reduce((a, b) -> a + "\n" + b).orElse("(nothing published)");
            scenario.attach(published.getBytes(StandardCharsets.UTF_8), "text/plain", "published");

            String observed = context.observedPayload();
            if (observed != null) {
                scenario.attach(observed.getBytes(StandardCharsets.UTF_8), "application/json", "observed");
            }

            // Spec 8.4: the envelope as well as the body. Key, partition and
            // offset are what a defect report needs in order to locate the
            // message on the broker without reproducing the run.
            var message = context.observedMessage();
            if (message != null) {
                String envelope = "topic     " + message.topic()
                        + "\nkey       " + message.key()
                        + "\npartition " + message.partition()
                        + "\noffset    " + message.offset()
                        + "\nheaders   " + message.headers();
                scenario.attach(envelope.getBytes(StandardCharsets.UTF_8), "text/plain", "envelope");
            }
            if (context.absenceWindowUsed() != null) {
                scenario.attach(context.absenceWindowUsed().getBytes(StandardCharsets.UTF_8),
                        "text/plain", "absence window");
            }
            if (context.isBlocked()) {
                scenario.attach(context.blockedCause().getBytes(StandardCharsets.UTF_8),
                        "text/plain", "blocked");
            }
            if (scenario.isFailed()) {
                scenario.attach(context.service().readLog().getBytes(StandardCharsets.UTF_8),
                        "text/plain", "service log");
            }
        } catch (RuntimeException attachmentFailed) {
            // Never let reporting break a scenario's result.
        } finally {
            closeQuietly(context);
        }
    }

    private static void closeQuietly(ScenarioContext context) {
        try {
            context.service().close();
        } catch (RuntimeException ignored) {
            // teardown is best effort
        }
        try {
            context.harness().close();
        } catch (RuntimeException ignored) {
            // teardown is best effort
        }
    }
}
