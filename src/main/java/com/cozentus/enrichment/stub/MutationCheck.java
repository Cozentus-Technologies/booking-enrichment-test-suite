package com.cozentus.enrichment.stub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Plans the mutation check and writes its results table from the runs.
 *
 * <p>Two modes, because the plan and the report have to come from one source or
 * they drift: {@code plan} prints the runs for the shell script to execute, and
 * {@code report} reads the Cucumber report each of those runs left behind and
 * renders the table. Nothing in the table is typed by a person, which is the
 * whole difference between a mutation check and a claim that one was done.
 *
 * <p>The expected sets in {@code /stub/mutation-plan.json} are a prediction. A
 * fixture that turns a different set red is a finding about the suite, not a
 * reason to bend the fixture, so the report states observed against expected
 * and marks the difference rather than hiding it.
 */
public final class MutationCheck {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLAN_RESOURCE = "/stub/mutation-plan.json";

    private MutationCheck() {
    }

    /**
     * @param fixture     which fixture the stub replays for this run
     * @param tags        the Cucumber tag expression that selects the run's slice
     * @param expectRed   the scenarios this check expects to fail
     * @param specSaysRed what section 4's table predicted, kept for the report
     * @param note        why expected and predicted differ, when they do
     */
    public record Run(String fixture, String tags, Set<String> expectRed,
                      String specSaysRed, String note) {
    }

    /** One scenario's verdict, keyed by its TC id. */
    private record Outcome(Set<String> red, Set<String> green) {
    }

    public static void main(String[] args) throws IOException {
        String mode = args.length > 0 ? args[0] : "plan";
        switch (mode) {
            case "plan" -> plan().forEach(run ->
                    System.out.println(run.fixture() + "\t" + run.tags()));
            case "report" -> report(Path.of(args[1]), Path.of(args[2]));
            default -> throw new IllegalArgumentException(
                    "Usage: MutationCheck plan | MutationCheck report <resultsDir> <outFile>");
        }
    }

    public static List<Run> plan() {
        try (InputStream in = MutationCheck.class.getResourceAsStream(PLAN_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("No " + PLAN_RESOURCE + " on the classpath");
            }
            List<Run> runs = new ArrayList<>();
            for (JsonNode node : MAPPER.readTree(in).path("runs")) {
                Set<String> expectRed = new TreeSet<>(MutationCheck::compareCaseIds);
                node.path("expectRed").forEach(id -> expectRed.add(id.asText()));
                runs.add(new Run(node.path("fixture").asText(),
                        node.path("tags").asText(),
                        expectRed,
                        node.path("specSaysRed").asText(""),
                        node.path("note").asText("")));
            }
            return List.copyOf(runs);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + PLAN_RESOURCE, e);
        }
    }

    // --- reading a run --------------------------------------------------

    /**
     * A scenario counts as red if anything in it failed, hooks included: a
     * scenario that never got as far as its first step because the service
     * would not start is not a pass.
     *
     * <p>A TC id carrying more than one scenario (a Scenario Outline) is red if
     * any of its rows is red, which is how a reader would describe it.
     */
    static Outcome read(Path cucumberJson) {
        Set<String> red = new TreeSet<>(MutationCheck::compareCaseIds);
        Set<String> green = new TreeSet<>(MutationCheck::compareCaseIds);

        JsonNode report;
        try {
            report = MAPPER.readTree(Files.readString(cucumberJson, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + cucumberJson, e);
        }

        for (JsonNode feature : report) {
            for (JsonNode scenario : feature.path("elements")) {
                String caseId = caseIdOf(scenario);
                if (caseId == null) {
                    continue;
                }
                (failed(scenario) ? red : green).add(caseId);
            }
        }
        green.removeAll(red);
        return new Outcome(red, green);
    }

    private static boolean failed(JsonNode scenario) {
        for (String section : List.of("before", "steps", "after")) {
            for (JsonNode step : scenario.path(section)) {
                String status = step.path("result").path("status").asText("passed");
                if (!"passed".equals(status) && !"skipped".equals(status)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String caseIdOf(JsonNode scenario) {
        for (JsonNode tag : scenario.path("tags")) {
            String name = tag.path("name").asText();
            if (name.startsWith("@TC-")) {
                return name.substring(1);
            }
        }
        return null;
    }

    /** TC-9 before TC-80: string order would put TC-101 before TC-13 and read badly. */
    private static int compareCaseIds(String left, String right) {
        int byNumber = Integer.compare(number(left), number(right));
        return byNumber != 0 ? byNumber : left.compareTo(right);
    }

    private static int number(String caseId) {
        try {
            return Integer.parseInt(caseId.substring(3));
        } catch (RuntimeException notNumbered) {
            return Integer.MAX_VALUE;
        }
    }

    // --- rendering ------------------------------------------------------

    static void report(Path resultsDirectory, Path out) throws IOException {
        StringBuilder rows = new StringBuilder();
        StringBuilder findings = new StringBuilder();
        int matched = 0;
        int ran = 0;

        for (Run run : plan()) {
            Path json = resultsDirectory.resolve(run.fixture() + ".json");
            if (!Files.exists(json)) {
                rows.append("| `%s` | not run | - | - | - |%n".formatted(run.fixture()));
                continue;
            }
            ran++;
            Outcome outcome = read(json);
            boolean asExpected = outcome.red().equals(run.expectRed());
            matched += asExpected ? 1 : 0;

            rows.append("| `%s` | %s | %s | %s | %s |%n".formatted(
                    run.fixture(),
                    list(outcome.red()),
                    // A run with nothing red is the baseline over the whole
                    // suite; naming a hundred scenarios in a table cell hides
                    // the one fact that matters, which is that none failed.
                    outcome.red().isEmpty()
                            ? "all %d case(s) in the slice; nothing failed"
                                    .formatted(outcome.green().size())
                            : list(outcome.green()),
                    asExpected ? "as expected" : "**differs**",
                    LocalDate.now()));

            if (!asExpected) {
                findings.append(finding(run, outcome));
            }
        }

        Files.createDirectories(out.getParent());
        Files.writeString(out, render(rows.toString(), slices(), findings.toString(), ran, matched),
                StandardCharsets.UTF_8);
        System.out.printf("Wrote %s from %d run(s); %d matched their expected set.%n",
                out, ran, matched);
    }

    private static String finding(Run run, Outcome outcome) {
        Set<String> unexpectedRed = new LinkedHashSet<>(outcome.red());
        unexpectedRed.removeAll(run.expectRed());
        Set<String> unexpectedGreen = new LinkedHashSet<>(run.expectRed());
        unexpectedGreen.removeAll(outcome.red());

        return """
                ### `%s`

                - expected red: %s
                - observed red: %s
                - red but not expected: %s
                - expected but stayed green: %s

                %s

                """.formatted(run.fixture(), list(run.expectRed()), list(outcome.red()),
                list(unexpectedRed), list(unexpectedGreen),
                run.note().isBlank() ? "No note recorded for this run." : run.note());
    }

    private static String list(Set<String> ids) {
        return ids.isEmpty() ? "none" : String.join(", ", ids);
    }

    /**
     * What each run covered and why, from the plan.
     *
     * <p>A red set on its own does not say whether the check was precise. The
     * slice says what the mutation had the opportunity to break, and the note
     * says why the expected set is what it is - including where it departs from
     * the prediction in section 4 of the remediation spec.
     */
    private static String slices() {
        StringBuilder detail = new StringBuilder();
        for (Run run : plan()) {
            detail.append("""

                    ### `%s`

                    - slice: `%s`
                    - expected red: %s
                    - the remediation spec predicted: %s

                    %s
                    """.formatted(run.fixture(), run.tags(), list(run.expectRed()),
                    run.specSaysRed().isBlank() ? "nothing recorded" : run.specSaysRed(),
                    run.note()));
        }
        return detail.toString();
    }

    private static String render(String rows, String slices, String findings, int ran, int matched) {
        String header = """
                # Mutation Check

                > **Generated file.** Written by `bash run-mutation-check.sh`, which runs the
                > suite once per fixture and reads the Cucumber report each run leaves behind.
                > Editing it by hand is pointless: the next run overwrites it.

                ## 1. Why this exists

                A black-box suite asserts on behaviour it never derived the rules for. That is
                the point of the design, and it also means a green run proves nothing on its
                own: a suite whose assertions are too weak passes against a broken service just
                as happily as against a correct one.

                **A suite that cannot fail proves nothing.** The only way to know this one can
                fail is to break a rule on purpose, one at a time, and confirm that the exact
                scenarios protecting that rule go red - no more, no fewer. An expected scenario
                that stays green means the suite is not checking what it claims to. A scenario
                outside the expected set going red means the suite is coupled to something it
                should not know about. Both are findings, and both are recorded below rather
                than tidied away.

                ## 2. How the rule is broken, without touching the service

                The service is the system under test. Editing it is out of bounds here, and
                impossible against a service somebody else operates - which is the situation
                this suite is written for. So the check runs against a **contract stub**
                (`src/main/java/com/cozentus/enrichment/stub/ContractStub.java`) that takes the
                service's place: same three topics, same command-line flags, same `GET /ready`
                endpoint, and no matching logic of its own. It replays a fixture of exact
                input-to-output mappings taken from the published schemas in
                `src/test/resources/contracts/`.

                The suite is not aware the stub exists. It is selected purely by
                `-Dservice.jar.path=target/contract-stub.jar`, and which fixture it replays
                comes from the `STUB_FIXTURE` environment variable, so `ServiceController`
                needs no knowledge of the substitution.

                Breaking one rule is then a few lines of JSON on top of the baseline fixture.
                The baseline is the control: a run against it is fully green, which is what
                makes a red result under any other fixture attributable to that fixture alone.

                ## 3. Running it

                ```bash
                bash run-mutation-check.sh          # baseline plus every mutation fixture
                bash run-mutation-check.sh mut-silent   # one fixture, for a re-check
                ```

                It needs a broker on the address in `test-local.properties` and nothing else.
                The real service jar is never read, so the check runs on a machine that has
                never had the service repository on it.

                ## 4. Results

                """;

        String footer = """

                ### What the columns mean

                - **Turned red** - every scenario in that run's slice that failed, hooks
                  included. A scenario that could not start is not a pass.
                - **Stayed green** - the controls: scenarios in the same slice that the
                  mutation must not disturb. A mutation that turns its slice entirely red has
                  proved nothing about precision.
                - **Verdict** - whether the red set is exactly the expected set. Anything else
                  is written up in section 5.

                A `@TC-nn` covering a Scenario Outline is listed red if any of its rows is red.

                """;

        String summary = "%d of %d run(s) turned exactly their expected set red.%n%n"
                .formatted(matched, ran);

        String findingsSection = findings.isBlank()
                ? """
                  ## 5. Findings

                  None: every fixture turned exactly the set expected of it.
                  """
                : "## 5. Findings\n\nWhere observed and expected differ, the observation wins. "
                        + "The expected set is a prediction about the suite; a difference is "
                        + "what the check is for.\n\n" + findings;

        return header
                + "| Fixture | Turned red | Stayed green | Verdict | Run date |\n"
                + "|---|---|---|---|---|\n"
                + rows
                + "\n" + summary
                + footer
                + findingsSection
                + "\n## 6. What each run covered\n" + slices
                + ARCHIVE;
    }

    /**
     * Kept because three other documents cite it.
     *
     * <p>{@code defects.csv} (DEF-111), {@code spec/risks.yaml} and
     * {@code docs/ai-review-log.md} all point at this file for the seven
     * mutations that were applied to the real service on 2026-09-06. Dropping
     * the record when the table above replaced it would leave those three
     * citing something that no longer exists. It is an archive of a different
     * exercise, not a result of the run above, and says so.
     */
    private static final String ARCHIVE = """

            ---

            ## 7. Archive: seven mutations applied to the real service, 2026-09-06

            Recorded before the contract stub existed, when the only way to break a
            rule was to edit the service. Kept because `defects.csv` (DEF-111),
            `spec/risks.yaml` and `docs/ai-review-log.md` all cite it. It is not a
            result of the run above, and it is not regenerated: it is a dated record
            of a different exercise, against `city-enrichment` at commit 9860876.

            | # | Mutation | Expected to go red | Observed |
            |---|---|---|---|
            | 1 | Jaro-Winkler floor raised to 0.99 | TC-06 to TC-12 | Exactly TC-06 to TC-12; TC-01 stayed green |
            | 2 | Distance cap forced to 0 | all fuzzy correction cases | TC-06 to TC-12 red; TC-01 to TC-05 green |
            | 3 | Flagged bookings also published to enriched | TC-32, TC-34, TC-42 | TC-32, TC-34 red. TC-42 did **not** fire |
            | 4 | Message key set to a constant | TC-35, TC-36 | TC-35, TC-36 and also TC-30, TC-31 |
            | 5 | Inbound headers dropped | TC-37 | Exactly TC-37 |
            | 6 | Canonical casing not applied | TC-02, TC-03 | TC-02, TC-03 and also TC-06, TC-07 |
            | 7 | Original values omitted from metadata | TC-25, TC-44 | TC-25, TC-44 and also TC-40 |

            Mutation 3 falsified a claim in the specification. TEST_SUITE_SPEC section
            7.5 states that TC-42 is a second, independent mechanism catching a flagged
            booking that reaches the enriched topic. It is not, as built: TC-42
            validates a flagged payload against the enriched schema in isolation, so
            where that payload was published does not affect its outcome. Routing
            exclusivity rests on TC-32 and TC-34 alone. Raised as DEF-111. The
            `mut-double-publish` row above re-tests the same claim against the stub.

            Mutation 4's spread onto TC-30 and TC-31 is the same coupling the
            `mut-const-key` row above runs into: the harness correlates an awaited
            message by its key, so a wrong key reads as a missing message.
            """;
}
