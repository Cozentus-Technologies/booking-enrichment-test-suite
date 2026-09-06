package com.cozentus.enrichment.tests.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A-1. Fails the build when a run reports success having executed no scenarios.
 *
 * <p>Cucumber exits successfully when a tag expression matches nothing, and the
 * suite's own helper tests keep surefire green, so a mistyped filter produces a
 * confident green build that verified nothing about the service. That is worse
 * than a red one: a red build gets investigated.
 */
public final class ExecutionGuard {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILTER_PROPERTY = "cucumber.filter.tags";

    /** What the guard found, so it can be asserted without exiting the JVM. */
    public record Result(boolean shouldFail, int executed, String filter, String message) {
    }

    private ExecutionGuard() {
    }

    public static void main(String[] args) {
        Path report = Path.of(args.length > 0 ? args[0] : "target/cucumber-reports/cucumber.json");
        Result result = evaluate(report, System.getProperty(FILTER_PROPERTY));

        System.out.println(result.message());
        if (result.shouldFail()) {
            System.exit(1);
        }
    }

    public static Result evaluate(Path cucumberJson, String filter) {
        // No report at all means the Cucumber suite did not run in this invocation
        // — a helper-only `mvn test`, for instance. That is a legitimate mode and
        // not something to fail; the guard speaks only about runs that did engage
        // the Cucumber engine.
        if (!Files.exists(cucumberJson)) {
            return new Result(false, 0, filter,
                    "Execution guard: no Cucumber report at " + cucumberJson
                            + "; this invocation did not run the scenario suite.");
        }

        int executed = countScenarios(cucumberJson);
        if (executed > 0) {
            return new Result(false, executed, filter,
                    "Execution guard: %d scenario(s) executed.".formatted(executed));
        }

        String describedFilter = filter == null || filter.isBlank()
                ? "(none supplied)" : filter;
        return new Result(true, 0, filter,
                """
                Execution guard: the run reported success having executed NO scenarios.

                  tag filter   %s
                  report       %s

                A filter that matches nothing, or a profile that excludes the whole
                Cucumber suite, produces a green build that verified nothing about the
                service. Check the tag expression, and remember that the scenario suite
                needs the kafka profile."""
                        .formatted(describedFilter, cucumberJson));
    }

    static int countScenarios(Path cucumberJson) {
        try {
            JsonNode root = MAPPER.readTree(
                    Files.readString(cucumberJson, StandardCharsets.UTF_8));
            int executed = 0;
            for (JsonNode feature : root) {
                for (JsonNode element : feature.path("elements")) {
                    if (!"scenario".equals(element.path("type").asText())) {
                        continue;
                    }
                    // A scenario every one of whose steps was skipped did not run.
                    boolean ranSomething = false;
                    for (JsonNode step : element.path("steps")) {
                        String status = step.path("result").path("status").asText();
                        if (!"skipped".equals(status)) {
                            ranSomething = true;
                            break;
                        }
                    }
                    if (ranSomething) {
                        executed++;
                    }
                }
            }
            return executed;
        } catch (IOException unreadable) {
            return 0;
        }
    }
}
