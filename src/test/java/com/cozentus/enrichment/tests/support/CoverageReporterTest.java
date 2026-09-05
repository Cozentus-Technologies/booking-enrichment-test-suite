package com.cozentus.enrichment.tests.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.cozentus.enrichment.tests.support.CoverageReporter.DefinedScenario;
import static com.cozentus.enrichment.tests.support.CoverageReporter.ExecutedScenario;
import static com.cozentus.enrichment.tests.support.CoverageReporter.TagCount;
import static com.cozentus.enrichment.tests.support.CoverageReporter.build;
import static com.cozentus.enrichment.tests.support.CoverageReporter.parseExecuted;
import static com.cozentus.enrichment.tests.support.CoverageReporter.render;
import static com.cozentus.enrichment.tests.support.CoverageReporter.scanDefinedScenarios;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link CoverageReporter} against small, inline fixtures rather than a
 * real Cucumber run, per TEST_SUITE_SPEC.md section 8.3: a passing scenario
 * counts as passed, a failing one counts as failed and is listed, a scenario
 * defined but never executed is called out separately, the critical pass rate
 * is computed from {@code @critical} scenarios alone, and nothing divides by
 * zero when a slice ran nothing at all.
 */
class CoverageReporterTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:15:00Z");

    // ---- cucumber.json parsing ------------------------------------------

    @Test
    void aPassingScenarioCountsAsPassed() {
        String json = feature("features/functional/x.feature", "X",
                scenario("TC-01", "A passing scenario", List.of("@functional", "@critical", "@city-correction"),
                        step("passed", null)));

        List<ExecutedScenario> executed = parseExecuted(json);

        assertThat(executed).hasSize(1);
        assertThat(executed.get(0).tcId()).isEqualTo("TC-01");
        assertThat(executed.get(0).passed()).isTrue();
        assertThat(executed.get(0).failureMessage()).isNull();
    }

    @Test
    void aFailingScenarioCountsAsFailedAndAppearsInTheFailedSection() {
        String json = feature("features/functional/x.feature", "X",
                scenario("TC-02", "A failing scenario", List.of("@functional", "@high", "@routing"),
                        step("passed", null),
                        step("failed", "org.opentest4j.AssertionFailedError: expected ENRICHED but was FLAGGED\n\tat Steps.java:42")));

        List<ExecutedScenario> executed = parseExecuted(json);
        assertThat(executed).hasSize(1);
        ExecutedScenario tc02 = executed.get(0);
        assertThat(tc02.passed()).isFalse();
        assertThat(tc02.failureMessage()).isEqualTo("org.opentest4j.AssertionFailedError: expected ENRICHED but was FLAGGED");

        DefinedScenario defined = new DefinedScenario("TC-02", "A failing scenario", "features/functional/x.feature",
                Set.of("@functional", "@high", "@routing"));
        CoverageReporter.CoverageReport report = build(List.of(defined), executed, "local", NOW);

        assertThat(report.failedCount()).isEqualTo(1);
        assertThat(report.failed()).extracting(ExecutedScenario::tcId).containsExactly("TC-02");

        String markdown = render(report);
        assertThat(markdown).contains("Failed").contains("TC-02")
                .contains("expected ENRICHED but was FLAGGED");
    }

    @Test
    void multipleExamplesRowsForOneOutlineCollapseToOneExecutedScenario() {
        // A Scenario Outline expands to one "elements" entry per Examples row,
        // all sharing the same @TC tag (TEST_SUITE_SPEC.md section 6). One row
        // failing must fail the whole TC.
        String json = feature("features/functional/x.feature", "X",
                scenario("TC-34", "Every booking lands on exactly one output topic",
                        List.of("@functional", "@critical", "@routing"), step("passed", null)),
                scenario("TC-34", "Every booking lands on exactly one output topic",
                        List.of("@functional", "@critical", "@routing"),
                        step("failed", "AssertionError: two topics saw the message")));

        List<ExecutedScenario> executed = parseExecuted(json);

        assertThat(executed).hasSize(1);
        assertThat(executed.get(0).passed()).isFalse();
        assertThat(executed.get(0).failureMessage()).isEqualTo("AssertionError: two topics saw the message");
    }

    @Test
    void emptyOrBlankCucumberJsonParsesToNoExecutedScenarios() {
        assertThat(parseExecuted("")).isEmpty();
        assertThat(parseExecuted((String) null)).isEmpty();
    }

    @Test
    void backgroundBlocksAreIgnoredEvenThoughTheyHaveSteps() {
        String json = "[ { \"uri\": \"features/functional/x.feature\", \"elements\": ["
                + "{ \"type\": \"background\", \"name\": \"bg\", \"tags\": [], "
                + "\"steps\": [ { \"name\": \"a step\", \"result\": { \"status\": \"passed\" } } ] }"
                + "] } ]";

        assertThat(parseExecuted(json)).isEmpty();
    }

    // ---- scanning feature files for the defined catalogue ----------------

    @Test
    void scansTcTagsFromFeatureFilesOnDisk(@TempDir Path dir) throws IOException {
        Path featuresRoot = dir.resolve("features");
        Path functional = featuresRoot.resolve("functional");
        Files.createDirectories(functional);

        Files.writeString(functional.resolve("sample.feature"), """
                Feature: Sample feature

                  @functional @city-correction @critical @fast @TC-01
                  Scenario: First scenario
                    Given a booking
                    When it is published
                    Then it lands somewhere

                  @functional @routing @high @slow @TC-02
                  Scenario Outline: An outline scenario
                    Given <input>
                    Examples:
                      | input |
                      | a     |
                      | b     |
                """);

        List<DefinedScenario> defined = scanDefinedScenarios(featuresRoot);

        assertThat(defined).hasSize(2);
        assertThat(defined).extracting(DefinedScenario::tcId).containsExactlyInAnyOrder("TC-01", "TC-02");

        DefinedScenario tc01 = defined.stream().filter(d -> d.tcId().equals("TC-01")).findFirst().orElseThrow();
        assertThat(tc01.name()).isEqualTo("First scenario");
        assertThat(tc01.featureFile()).isEqualTo("functional/sample.feature");
        assertThat(tc01.tags()).contains("@critical", "@city-correction", "@functional", "@fast");

        DefinedScenario tc02 = defined.stream().filter(d -> d.tcId().equals("TC-02")).findFirst().orElseThrow();
        assertThat(tc02.tags()).contains("@high", "@routing");
    }

    @Test
    void scanningAMissingDirectoryYieldsNoDefinedScenarios(@TempDir Path dir) {
        assertThat(scanDefinedScenarios(dir.resolve("does-not-exist"))).isEmpty();
    }

    // ---- reconciliation: defined vs executed -------------------------------

    @Test
    void aScenarioDefinedButAbsentFromTheRunAppearsUnderDefinedButNotExecuted() {
        DefinedScenario tc01 = new DefinedScenario("TC-01", "Runs fine", "features/x.feature",
                Set.of("@functional", "@critical", "@city-correction"));
        DefinedScenario tc02 = new DefinedScenario("TC-02", "Never selected by the tag filter", "features/x.feature",
                Set.of("@functional", "@high", "@routing"));
        ExecutedScenario executedTc01 = new ExecutedScenario("TC-01", "Runs fine",
                Set.of("@functional", "@critical", "@city-correction"), true, null);

        CoverageReporter.CoverageReport report = build(List.of(tc01, tc02), List.of(executedTc01), "local", NOW);

        assertThat(report.definedCount()).isEqualTo(2);
        assertThat(report.executedCount()).isEqualTo(1);
        assertThat(report.definedButNotExecuted()).extracting(DefinedScenario::tcId).containsExactly("TC-02");

        String markdown = render(report);
        assertThat(markdown).contains("Defined but not executed").contains("TC-02");
        // TC-02 must not silently read as covered just because it is absent from "Failed".
        assertThat(report.failed()).isEmpty();
    }

    @Test
    void criticalPassRateIsComputedFromCriticalScenariosOnly() {
        DefinedScenario critical1 = new DefinedScenario("TC-01", "n1", "f.feature", Set.of("@critical"));
        DefinedScenario critical2 = new DefinedScenario("TC-02", "n2", "f.feature", Set.of("@critical"));
        DefinedScenario high1 = new DefinedScenario("TC-03", "n3", "f.feature", Set.of("@high"));

        ExecutedScenario critical1Passed = new ExecutedScenario("TC-01", "n1", Set.of("@critical"), true, null);
        ExecutedScenario critical2Failed = new ExecutedScenario("TC-02", "n2", Set.of("@critical"), false, "boom");
        ExecutedScenario high1Passed = new ExecutedScenario("TC-03", "n3", Set.of("@high"), true, null);

        CoverageReporter.CoverageReport report = build(List.of(critical1, critical2, high1),
                List.of(critical1Passed, critical2Failed, high1Passed), "local", NOW);

        List<TagCount> byPriority = report.byPriority();
        TagCount criticalCount = byPriority.stream().filter(t -> t.tag().equals("@critical")).findFirst().orElseThrow();
        TagCount highCount = byPriority.stream().filter(t -> t.tag().equals("@high")).findFirst().orElseThrow();
        TagCount mediumCount = byPriority.stream().filter(t -> t.tag().equals("@medium")).findFirst().orElseThrow();

        assertThat(criticalCount.passed()).isEqualTo(1);
        assertThat(criticalCount.total()).isEqualTo(2);
        assertThat(criticalCount.percent()).isEqualTo(50);

        assertThat(highCount.passed()).isEqualTo(1);
        assertThat(highCount.total()).isEqualTo(1);
        assertThat(highCount.percent()).isEqualTo(100);

        // A priority with no defined scenarios at all must still be listed, at 0/0.
        assertThat(mediumCount.total()).isEqualTo(0);
        assertThat(mediumCount.percent()).isEqualTo(0);
    }

    @Test
    void percentagesAreRightWhenNothingRan() {
        DefinedScenario tc01 = new DefinedScenario("TC-01", "n1", "f.feature", Set.of("@critical", "@routing"));
        DefinedScenario tc02 = new DefinedScenario("TC-02", "n2", "f.feature", Set.of("@high", "@routing"));

        CoverageReporter.CoverageReport report = build(List.of(tc01, tc02), List.of(), "local", NOW);

        assertThat(report.definedCount()).isEqualTo(2);
        assertThat(report.executedCount()).isEqualTo(0);
        assertThat(report.executedPercent()).isEqualTo(0);
        assertThat(report.passedCount()).isEqualTo(0);
        assertThat(report.failedCount()).isEqualTo(0);

        for (TagCount tc : report.byPriority()) {
            assertThat(tc.percent()).isEqualTo(0);
        }
        for (TagCount tc : report.byCategory()) {
            assertThat(tc.percent()).isEqualTo(0);
        }

        // Renders without throwing, and does not claim anything passed.
        String markdown = render(report);
        assertThat(markdown).contains("Executed             0   (0%)").contains("Passed               0");
    }

    @Test
    void categoryLinesOnlyListFeatureAreaTagsActuallyInUse() {
        DefinedScenario tc01 = new DefinedScenario("TC-01", "n1", "f.feature",
                Set.of("@functional", "@critical", "@city-correction"));

        CoverageReporter.CoverageReport report = build(List.of(tc01), List.of(), "local", NOW);

        assertThat(report.byCategory()).extracting(TagCount::tag).containsExactly("@city-correction");
    }

    // ---- fixture builders ---------------------------------------------------

    private static String feature(String uri, String name, String... elements) {
        return "[ { \"uri\": \"" + uri + "\", \"name\": \"" + name + "\", \"elements\": ["
                + String.join(",", elements) + "] } ]";
    }

    private static String scenario(String tcId, String name, List<String> extraTags, String... steps) {
        StringBuilder tags = new StringBuilder();
        tags.append("{ \"name\": \"@").append(tcId).append("\" }");
        for (String tag : extraTags) {
            tags.append(", { \"name\": \"").append(tag).append("\" }");
        }
        return "{ \"type\": \"scenario\", \"name\": \"" + name + "\", "
                + "\"tags\": [" + tags + "], "
                + "\"steps\": [" + String.join(",", steps) + "] }";
    }

    private static String step(String status, String errorMessage) {
        String result = errorMessage == null
                ? "{ \"status\": \"" + status + "\" }"
                : "{ \"status\": \"" + status + "\", \"error_message\": \"" + escape(errorMessage) + "\" }";
        return "{ \"name\": \"a step\", \"result\": " + result + " }";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
    }
}
