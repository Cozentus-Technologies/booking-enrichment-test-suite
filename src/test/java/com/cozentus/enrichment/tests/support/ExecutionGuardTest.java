package com.cozentus.enrichment.tests.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionGuardTest {

    @TempDir
    Path dir;

    private Path report(String json) throws IOException {
        Path file = dir.resolve("cucumber.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static String scenario(String... stepStatuses) {
        StringBuilder steps = new StringBuilder();
        for (String status : stepStatuses) {
            if (!steps.isEmpty()) {
                steps.append(',');
            }
            steps.append("{\"result\":{\"status\":\"%s\",\"duration\":1}}".formatted(status));
        }
        return "{\"type\":\"scenario\",\"name\":\"s\",\"steps\":[%s]}".formatted(steps);
    }

    @Test
    @DisplayName("a filter that matched nothing fails the build")
    void emptyRunFails() throws IOException {
        Path file = report("[{\"elements\":[%s]}]".formatted(scenario("skipped", "skipped")));

        ExecutionGuard.Result result = ExecutionGuard.evaluate(file, "@TC-9999");

        assertThat(result.shouldFail()).isTrue();
        assertThat(result.executed()).isZero();
        assertThat(result.message())
                .contains("@TC-9999")
                .contains("executed NO scenarios");
    }

    @Test
    @DisplayName("a run that executed something passes and reports the count")
    void executedRunPasses() throws IOException {
        Path file = report("[{\"elements\":[%s,%s]}]"
                .formatted(scenario("passed", "passed"), scenario("passed", "failed")));

        ExecutionGuard.Result result = ExecutionGuard.evaluate(file, "@critical");

        assertThat(result.shouldFail()).isFalse();
        assertThat(result.executed()).isEqualTo(2);
        assertThat(result.message()).contains("2 scenario(s) executed");
    }

    @Test
    @DisplayName("a failing scenario still counts as executed")
    void failedScenarioCounts() throws IOException {
        Path file = report("[{\"elements\":[%s]}]".formatted(scenario("failed")));

        assertThat(ExecutionGuard.evaluate(file, null).executed()).isEqualTo(1);
    }

    @Test
    @DisplayName("no report at all is not a failure, since a helper-only run writes none")
    void absentReportIsNotAFailure() {
        ExecutionGuard.Result result =
                ExecutionGuard.evaluate(dir.resolve("nothing-here.json"), null);

        assertThat(result.shouldFail()).isFalse();
        assertThat(result.message()).contains("did not run the scenario suite");
    }

    @Test
    @DisplayName("an unfiltered run that executed nothing also fails, and says so")
    void unfilteredEmptyRunFails() throws IOException {
        Path file = report("[{\"elements\":[%s]}]".formatted(scenario("skipped")));

        ExecutionGuard.Result result = ExecutionGuard.evaluate(file, null);

        assertThat(result.shouldFail()).isTrue();
        assertThat(result.message()).contains("(none supplied)");
    }

    @Test
    @DisplayName("an empty report is an empty run")
    void emptyReportFails() throws IOException {
        assertThat(ExecutionGuard.evaluate(report("[]"), "@smoke").shouldFail()).isTrue();
    }

    @Test
    @DisplayName("a malformed report is treated as having executed nothing")
    void malformedReportFails() throws IOException {
        assertThat(ExecutionGuard.evaluate(report("{not json"), null).shouldFail()).isTrue();
    }
}
