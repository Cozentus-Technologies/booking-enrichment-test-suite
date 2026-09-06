package com.cozentus.enrichment.tests.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cozentus.enrichment.tests.data.TestDataLoader;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Proves {@link EntryCriteria}'s shape without requiring a live broker or
 * readiness endpoint: every check must be independently runnable, must never
 * let an exception escape, and a check whose underlying call throws must be
 * reported as failed with the exception's message as its detail.
 */
class EntryCriteriaTest {

    // ---- the runner never lets an exception escape ------------------------

    @Test
    void safelyReportsAThrowingCheckAsFailedWithTheExceptionMessage() {
        EntryCriteria.CheckResult result = EntryCriteria.safely("Some check", () -> {
            throw new IllegalStateException("boom: broker unreachable");
        });

        assertThat(result.name()).isEqualTo("Some check");
        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("boom: broker unreachable");
    }

    @Test
    void safelyFallsBackToTheExceptionClassNameWhenTheMessageIsBlank() {
        EntryCriteria.CheckResult result = EntryCriteria.safely("Some check", () -> {
            throw new IllegalStateException();
        });

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("IllegalStateException");
    }

    @Test
    void safelyPassesThroughASuccessfulResultUnchanged() {
        EntryCriteria.CheckResult expected = new EntryCriteria.CheckResult("Some check", true, "all good");
        Callable<EntryCriteria.CheckResult> check = () -> expected;

        EntryCriteria.CheckResult result = EntryCriteria.safely("Some check", check);

        assertThat(result).isEqualTo(expected);
    }

    // ---- check 1: data table parses ----------------------------------------

    @Test
    void dataTableParsesPassesAgainstTheRealCsvAndNamesTheRowCount() {
        EntryCriteria.CheckResult result = EntryCriteria.dataTableParses();

        assertThat(result.name()).isEqualTo("Data table parses");
        assertThat(result.passed()).isTrue();
        // Derived, not literal: a hard-coded count turns every legitimate
        // addition to the table into a failure in an unrelated test.
        assertThat(result.detail())
                .contains(String.valueOf(TestDataLoader.load().size()))
                .contains("row(s) loaded");
    }

    // ---- check 2: contracts available --------------------------------------

    @Test
    void contractsAvailablePassesWhenAllThreeSchemasResolveOnTheClasspath() {
        EntryCriteria.CheckResult result = EntryCriteria.contractsAvailable();

        assertThat(result.name()).isEqualTo("Contracts available");
        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).contains("3 schema(s) resolved");
    }

    // ---- checks 3 & 4: environment-dependent, assert on shape only --------

    @Test
    void brokerReachableNeverThrowsAndAlwaysCarriesADetail() {
        TestConfig config = TestConfig.load();

        assertThatCode(() -> {
            EntryCriteria.CheckResult result = EntryCriteria.brokerReachable(config);
            assertThat(result.name()).isEqualTo("Broker reachable");
            assertThat(result.detail()).isNotBlank();
        }).doesNotThrowAnyException();
    }

    @Test
    void readinessRespondsNeverThrowsAndAlwaysCarriesADetail() {
        TestConfig config = TestConfig.load();

        assertThatCode(() -> {
            EntryCriteria.CheckResult result = EntryCriteria.readinessResponds(config);
            assertThat(result.name()).isEqualTo("Readiness endpoint responds");
            assertThat(result.detail()).isNotBlank();
        }).doesNotThrowAnyException();
    }

    // ---- run() and the report shape ----------------------------------------

    @Test
    void runProducesOneResultPerCheckInOrderAndNeverThrows() {
        TestConfig config = TestConfig.load();

        EntryCriteria.Report report = EntryCriteria.run(config);

        assertThat(report.checks()).hasSize(4);
        assertThat(report.checks()).extracting(EntryCriteria.CheckResult::name).containsExactly(
                "Data table parses",
                "Contracts available",
                "Broker reachable",
                "Readiness endpoint responds");
    }

    @Test
    void allPassedIsFalseWhenAnyCheckFailed() {
        EntryCriteria.Report report = new EntryCriteria.Report(List.of(
                new EntryCriteria.CheckResult("A", true, "fine"),
                new EntryCriteria.CheckResult("B", false, "broken")));

        assertThat(report.allPassed()).isFalse();
    }

    @Test
    void allPassedIsTrueWhenEveryCheckPassed() {
        EntryCriteria.Report report = new EntryCriteria.Report(List.of(
                new EntryCriteria.CheckResult("A", true, "fine"),
                new EntryCriteria.CheckResult("B", true, "also fine")));

        assertThat(report.allPassed()).isTrue();
    }

    @Test
    void linesRendersOneReadableLinePerCheckNamingItAndItsDetail() {
        EntryCriteria.Report report = new EntryCriteria.Report(List.of(
                new EntryCriteria.CheckResult("A", true, "fine"),
                new EntryCriteria.CheckResult("B", false, "broken: timed out")));

        List<String> lines = report.lines();

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("A").contains("fine").contains("PASS");
        assertThat(lines.get(1)).contains("B").contains("broken: timed out").contains("FAIL");
    }

    // ---- the shared, cached gate -------------------------------------------

    @org.junit.jupiter.api.BeforeEach
    void forgetPreviousVerification() {
        EntryCriteria.resetVerification();
    }

    @Test
    @DisplayName("the environment is probed once, however many callers ask")
    void theCheckRunsOnce() {
        java.util.concurrent.atomic.AtomicInteger probes =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<EntryCriteria.Report> passing = () -> {
            probes.incrementAndGet();
            return new EntryCriteria.Report(List.of(
                    new EntryCriteria.CheckResult("Broker reachable", true, "ok")));
        };

        EntryCriteria.verifyOnce(passing);
        EntryCriteria.verifyOnce(passing);
        EntryCriteria.verifyOnce(passing);

        assertThat(probes.get())
                .as("every caller re-probing a broker is what made a dead-broker run take 88 seconds")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a failure is cached and rethrown without probing again")
    void aFailureIsRethrownImmediately() {
        java.util.concurrent.atomic.AtomicInteger probes =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<EntryCriteria.Report> failing = () -> {
            probes.incrementAndGet();
            return new EntryCriteria.Report(List.of(
                    new EntryCriteria.CheckResult("Broker reachable", false, "Could not list topics")));
        };

        assertThatThrownBy(() -> EntryCriteria.verifyOnce(failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be trusted")
                .hasMessageContaining("Could not list topics");

        // The second caller must fail on the cached answer, not by asking again.
        assertThatThrownBy(() -> EntryCriteria.verifyOnce(failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not list topics");

        assertThat(probes.get()).as("the broker must be probed once, not once per caller").isEqualTo(1);
    }
}
