package com.cozentus.enrichment.tests.harness;

import com.cozentus.enrichment.tests.support.TestConfig;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * E-3. Answers, before a run rather than during one, whether the environment the
 * suite is about to be pointed at is actually there.
 *
 * <p>Against a deployment the suite did not provision, the broker, the three
 * topics and the running service all belong to someone else. When one of them is
 * missing the suite finds out as a scenario waiting out its timeout for a
 * message that was never going to arrive - which reads as a service defect, gets
 * raised as one, and costs somebody an afternoon. This states which of the three
 * is missing, and the value it used to decide, in about a second.
 *
 * <p>Invoked as {@code bash run-tests.sh preflight}. Non-zero exit on any
 * failure, so it can gate a pipeline step as well as inform a person.
 */
public final class Preflight {

    /** One check: what was checked, the value it was checked against, and the verdict. */
    public record Check(String name, String valueUsed, boolean passed, String detail) {
    }

    private Preflight() {
    }

    public static void main(String[] args) {
        TestConfig config = TestConfig.load();
        System.out.println("Preflight - suite.env=" + config.environment());

        List<Check> checks = run(config);
        checks.forEach(check -> System.out.println(render(check)));

        int failures = failed(checks);
        if (failures > 0) {
            System.err.printf("%nPreflight failed: %d of %d check(s) did not pass. "
                            + "The environment is not ready, so a run against it would "
                            + "report service defects that are really missing prerequisites.%n",
                    failures, checks.size());
            System.exit(1);
        }
        System.out.printf("%nPreflight passed: %d check(s).%n", checks.size());
    }

    public static List<Check> run(TestConfig config) {
        List<Check> checks = new ArrayList<>();
        List<String> topics = List.of(config.rawTopicPrefix(),
                config.enrichedTopicPrefix(), config.flaggedTopicPrefix());

        Set<String> present;
        try (TopicProvisioner provisioner = new TopicProvisioner(config.bootstrapServers())) {
            present = provisioner.topicNames();
            checks.add(new Check("Broker reachable", config.bootstrapServers(), true,
                    present.size() + " topic(s) visible"));
        } catch (RuntimeException unreachable) {
            // No broker means the topic check has nothing to say and would only
            // add a second, derived failure to the one that matters.
            checks.add(new Check("Broker reachable", config.bootstrapServers(), false,
                    describe(unreachable)));
            checks.add(new Check("Topics present", String.join(", ", topics), false,
                    "not checked: the broker did not answer"));
            checks.add(readinessCheck(config));
            return checks;
        }

        checks.add(config.isExternal()
                ? topicsPresent(topics, present)
                : new Check("Topics present", String.join(", ", topics) + ".<scenarioId>", true,
                        "not applicable under the " + config.environment()
                                + " profile: the suite creates a scenario-scoped topic per scenario"));
        checks.add(readinessCheck(config));
        return checks;
    }

    /**
     * Under external the configured values are the deployment's real topic names
     * and the suite creates none of them, so all three have to be there already.
     */
    static Check topicsPresent(List<String> configured, Set<String> onTheBroker) {
        List<String> missing = configured.stream().filter(topic -> !onTheBroker.contains(topic)).toList();
        return new Check("Topics present", String.join(", ", configured), missing.isEmpty(),
                missing.isEmpty()
                        ? "all three present"
                        : "missing: " + String.join(", ", missing)
                                + " - the suite creates no topic under this profile");
    }

    private static Check readinessCheck(TestConfig config) {
        if (!config.isExternal()) {
            return new Check("Readiness endpoint", config.readinessUrl(), true,
                    "not applicable under the " + config.environment()
                            + " profile: the suite starts a service per scenario on its own port");
        }
        return readiness(config.readinessUrl(), ServiceController.probe(URI.create(config.readinessUrl())));
    }

    /**
     * Anything but a 2xx is a failure, a 503 included: a deployment that is up
     * and not ready is precisely the state a preflight exists to catch before a
     * run mistakes it for the service losing messages.
     */
    static Check readiness(String url, ServiceController.Readiness answer) {
        return new Check("Readiness endpoint", url, answer.ready(), answer.detail());
    }

    static String render(Check check) {
        return "  [%s] %-20s %-46s %s".formatted(
                check.passed() ? "PASS" : "FAIL", check.name(), check.valueUsed(), check.detail());
    }

    static int failed(List<Check> checks) {
        return (int) checks.stream().filter(check -> !check.passed()).count();
    }

    private static String describe(RuntimeException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
