package com.cozentus.enrichment.tests.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * D-3. Fails the build after the reports have been written.
 *
 * <p>Surefire aborts the build the moment a test fails, so every execution
 * bound after it is skipped. That included the Test Execution Summary Report
 * and the scenario coverage file - which meant the reports existed only for
 * runs that had nothing to report, and a failing run, the one a reader
 * actually needs a report for, produced none.
 *
 * <p>Surefire is therefore configured not to fail the build itself. The
 * reports run, then this guard reads what surefire recorded and fails. The
 * build result is unchanged; only its timing moves.
 */
public final class BuildResultGuard {

    private static final Path REPORTS = Path.of("target/surefire-reports");

    private static final Pattern SUITE = Pattern.compile(
            "<testsuite[^>]*?tests=\"(\\d+)\"[^>]*?>");
    private static final Pattern FAILURES = Pattern.compile("failures=\"(\\d+)\"");
    private static final Pattern ERRORS = Pattern.compile("errors=\"(\\d+)\"");
    private static final Pattern NAME = Pattern.compile("name=\"([^\"]+)\"");

    private BuildResultGuard() {
    }

    public record Result(int tests, int failures, int errors, List<String> failingSuites) {

        public boolean passed() {
            return failures == 0 && errors == 0;
        }

        public String describe() {
            return passed()
                    ? "Build result guard: %d test(s), no failures.".formatted(tests)
                    : "Build result guard: %d failure(s) and %d error(s) across %d test(s) in %s"
                            .formatted(failures, errors, tests, failingSuites);
        }
    }

    /** Reads what surefire recorded, rather than re-running anything. */
    public static Result read(Path reportsDir) {
        if (!Files.isDirectory(reportsDir)) {
            // No reports at all is not a pass: it means the tests never ran.
            // ExecutionGuard covers the empty-run case with a better message,
            // so this stays silent rather than duplicating it.
            return new Result(0, 0, 0, List.of());
        }
        int tests = 0;
        int failures = 0;
        int errors = 0;
        List<String> failing = new ArrayList<>();

        try (Stream<Path> files = Files.list(reportsDir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".xml")).toList()) {
                String xml = Files.readString(file, StandardCharsets.UTF_8);
                Matcher suite = SUITE.matcher(xml);
                if (!suite.find()) {
                    continue;
                }
                String header = suite.group();
                int suiteFailures = intIn(FAILURES, header);
                int suiteErrors = intIn(ERRORS, header);

                tests += Integer.parseInt(suite.group(1));
                failures += suiteFailures;
                errors += suiteErrors;
                if (suiteFailures + suiteErrors > 0) {
                    Matcher name = NAME.matcher(header);
                    failing.add(name.find() ? name.group(1) : file.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + reportsDir, e);
        }
        return new Result(tests, failures, errors, List.copyOf(failing));
    }

    private static int intIn(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    public static void main(String[] args) {
        Result result = read(args.length > 0 ? Path.of(args[0]) : REPORTS);
        System.out.println(result.describe());
        if (!result.passed()) {
            throw new IllegalStateException(result.describe());
        }
    }
}
