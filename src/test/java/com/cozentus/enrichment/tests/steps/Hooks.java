package com.cozentus.enrichment.tests.steps;

import com.cozentus.enrichment.tests.harness.KafkaServiceHarness;
import com.cozentus.enrichment.tests.harness.ServiceController;
import com.cozentus.enrichment.tests.harness.TopicProvisioner;
import com.cozentus.enrichment.tests.support.EntryCriteria;
import com.cozentus.enrichment.tests.support.ScenarioContext;
import com.cozentus.enrichment.tests.support.TestConfig;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.opentest4j.TestAbortedException;

/**
 * Per-scenario setup and teardown: fresh topics, a fresh consumer group, and a
 * service instance owning them, so scenarios are order-independent and each is
 * individually runnable.
 *
 * <p>E-1/E-2: against a deployment the suite did not provision, none of that is
 * available. Nothing is started, the deployment's own topics are used as they
 * are, and the scenarios whose arrangement needs a differently configured
 * service instance are excluded rather than run and reported as failures the
 * deployment did not cause.
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
    private static final String EXTENDED_CITIES =
            "inline:Mumbai,New Delhi,Bangalore,Chennai,Kolkata,Pune,Hyderabad,Ahmedabad,Delhi";

    private static final Map<String, String> CITIES_BY_TAG = Map.of(
            "@TC-29", EXTENDED_CITIES,
            "@TC-46", EXTENDED_CITIES,
            "@TC-47", EXTENDED_CITIES,
            "@TC-48", EXTENDED_CITIES);

    /**
     * E-2. The tag a scenario carries when what it needs is a differently
     * configured service instance rather than different input.
     *
     * <p>The scenarios carrying it are the four in {@link #CITIES_BY_TAG}:
     * ambiguity only exists when the reference list holds two equally valid
     * candidates, and the only way a black-box suite can arrange that is to
     * start the service against a different list. Against a deployment it did
     * not start, it cannot - and running them anyway would report a service
     * defect where there is only an environment this suite cannot reconfigure.
     */
    private static final String REQUIRES_SERVICE_CONFIG = "@requires-service-config";

    /** Where the run-end summary is left, so a report can read it rather than infer it. */
    private static final Path EXCLUSION_REPORT = Path.of("target", "environment-exclusions.txt");

    /**
     * E-2. What this environment could not run, kept apart from what a tag
     * expression left out. Both end as scenarios that did not execute, and
     * reading them as the same thing is how a run against a shared deployment
     * comes to look like fuller coverage than it was.
     */
    private static final List<String> ENVIRONMENT_EXCLUSIONS =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * F-21: entry criteria are verified once, before the first scenario runs.
     * A misconfigured environment otherwise surfaces as sixty-odd confusing
     * scenario failures instead of one clear statement of what is not ready.
     */
    private static volatile boolean entryCriteriaChecked = false;
    private static volatile String entryCriteriaFailure = null;

    private final ScenarioContext context;

    public Hooks(ScenarioContext context) {
        this.context = context;
    }

    /**
     * E-2. Runs before anything is provisioned, hence {@code order = 0}: a
     * scenario this environment cannot arrange must not first create topics and
     * wait on a readiness endpoint before being abandoned.
     *
     * <p>{@link TestAbortedException} rather than a failure or a silent pass.
     * Cucumber reports it as skipped, which is the honest word for it: the
     * scenario did not run, and the reason has nothing to do with the service.
     */
    @Before(order = 0)
    public void excludeWhatThisEnvironmentCannotArrange(Scenario scenario) {
        TestConfig config = TestConfig.load();
        if (!config.isExternal()
                || !scenario.getSourceTagNames().contains(REQUIRES_SERVICE_CONFIG)) {
            return;
        }

        String reason = """
                Excluded by environment, not by tag filter.

                  scenario   %s
                  tag        %s
                  suite.env  %s

                This scenario needs the service started against an extended
                reference list, which is only possible when the suite starts the
                service. Under suite.env=%s it starts nothing. It is not a
                failure and it is not a gap in the tag expression: run it under
                suite.env=local to execute it."""
                .formatted(scenario.getName(), REQUIRES_SERVICE_CONFIG,
                        config.environment(), config.environment());

        scenario.attach(reason.getBytes(StandardCharsets.UTF_8), "text/plain",
                "excluded by environment");
        ENVIRONMENT_EXCLUSIONS.add("%s  %s".formatted(
                traceabilityTag(scenario), scenario.getName()));
        throw new TestAbortedException(
                "Excluded by environment (suite.env=" + config.environment() + "): "
                        + scenario.getName() + " needs " + REQUIRES_SERVICE_CONFIG);
    }

    private static String traceabilityTag(Scenario scenario) {
        return scenario.getSourceTagNames().stream()
                .filter(tag -> tag.startsWith("@TC-"))
                .findFirst()
                .orElse("(untagged)");
    }

    /**
     * E-2. Stated once at the end of the run and written to a file, so the
     * distinction survives past the console. Silence when nothing was excluded:
     * on local and ci this hook has nothing to say and should not say it.
     */
    @AfterAll
    public static void reportEnvironmentExclusions() {
        if (ENVIRONMENT_EXCLUSIONS.isEmpty()) {
            return;
        }
        String summary = """
                %d scenario(s) excluded by environment under suite.env=%s.
                These were selected by the tag filter and could not be run here;
                they were not filtered out, and they did not fail:

                %s"""
                .formatted(ENVIRONMENT_EXCLUSIONS.size(),
                        System.getProperty("suite.env", "local"),
                        String.join(System.lineSeparator(), ENVIRONMENT_EXCLUSIONS));

        System.out.println(summary);
        try {
            Files.createDirectories(EXCLUSION_REPORT.getParent());
            Files.writeString(EXCLUSION_REPORT, summary);
        } catch (IOException couldNotWrite) {
            // The console already carries it; failing the run over a report file
            // would turn a reporting problem into a test result.
            System.err.println("Could not write " + EXCLUSION_REPORT + ": " + couldNotWrite);
        }
    }

    @Before
    public void startIsolatedService(Scenario scenario) {
        TestConfig config = TestConfig.load();
        verifyEntryCriteriaOnce(config);
        String scenarioId = TopicProvisioner.newScenarioId();

        KafkaServiceHarness harness = null;
        try {
            harness = new KafkaServiceHarness(config, scenarioId);
            ServiceController service = ServiceController.start(config, scenarioId,
                    harness.rawTopic(), harness.enrichedTopic(), harness.flaggedTopic(),
                    citiesSourceFor(scenario));
            context.bind(config, harness, service, scenarioId);
        } catch (RuntimeException prerequisiteFailed) {
            // A-6: the harness owns three topics and two consumer groups from the
            // moment it is constructed. If the service then fails to start it was
            // never bound, so teardown could not find it and the topics survived
            // the run. Close it here instead.
            if (harness != null) {
                try {
                    harness.close();
                } catch (RuntimeException ignored) {
                    // teardown is best effort
                }
            }
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

    /**
     * A-5. The failure is cached and rethrown from every scenario. Setting the
     * flag before running the check meant only the first scenario reported a
     * dead broker, and every one after it went on to create topics against a
     * broker that was not there.
     */
    private static synchronized void verifyEntryCriteriaOnce(TestConfig config) {
        if (entryCriteriaFailure != null) {
            throw new IllegalStateException(entryCriteriaFailure);
        }
        if (entryCriteriaChecked) {
            return;
        }

        EntryCriteria.Report report = EntryCriteria.run(config);
        report.lines().forEach(line -> System.out.println("  " + line));

        if (!report.allPassed()) {
            entryCriteriaFailure = "Entry criteria not met, so the run cannot be trusted:\n"
                    + String.join("\n", report.lines());
            throw new IllegalStateException(entryCriteriaFailure);
        }
        entryCriteriaChecked = true;
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
