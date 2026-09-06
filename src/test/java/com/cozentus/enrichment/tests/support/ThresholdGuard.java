package com.cozentus.enrichment.tests.support;

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
 * B-5. Fails the build if the suite states the service's confidence floor.
 *
 * <p>A suite that asserts {@code confidence >= 0.85} agrees with the service by
 * construction rather than by test: both sides read the same number, so the
 * assertion holds however wrong the matching is, and retuning the threshold —
 * a business decision the strategy document explicitly leaves to a named owner —
 * shows up as a suite failure rather than as a contract change.
 *
 * <p>What the suite asserts instead is {@code exactly 1.0} against {@code below
 * 1.0}, which distinguishes the exact path from the fuzzy path without knowing
 * where the floor sits. 1.0 is therefore the one confidence literal allowed:
 * it is not a threshold but the definition of an exact match.
 */
public final class ThresholdGuard {

    /**
     * A comparison against a decimal that is not 1.0. Matches the AssertJ forms
     * the suite uses, so a bare number in a comment or a measured value recorded
     * in a note is left alone — those document an observation, they do not make
     * the suite depend on it.
     */
    private static final Pattern COMPARISON = Pattern.compile(
            "is(?:GreaterThan|GreaterThanOrEqualTo|LessThan|LessThanOrEqualTo|CloseTo|EqualTo)"
                    + "\\(\\s*(0\\.\\d+)[dDfF]?\\s*[,)]");

    /** The floor as the contract states it, in any of the forms it could be written. */
    private static final Pattern FLOOR = Pattern.compile("\\b0\\.85\\b");

    private ThresholdGuard() {
    }

    public record Violation(Path file, int line, String text) {

        @Override
        public String toString() {
            return "%s:%d  %s".formatted(file, line, text.trim());
        }
    }

    /** @return every place the suite names the confidence floor */
    public static List<Violation> scan(Path root) {
        List<Violation> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(ThresholdGuard::isCheckable).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (statesTheFloor(lines.get(i))) {
                        violations.add(new Violation(file, i + 1, lines.get(i)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scan " + root, e);
        }
        return violations;
    }

    static boolean statesTheFloor(String line) {
        if (FLOOR.matcher(line).find()) {
            return true;
        }
        Matcher comparison = COMPARISON.matcher(line);
        while (comparison.find()) {
            if (Double.parseDouble(comparison.group(1)) != 1.0d) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCheckable(Path file) {
        String name = file.toString();
        // The guard's own source and test necessarily contain the pattern.
        return Files.isRegularFile(file)
                && (name.endsWith(".java") || name.endsWith(".feature") || name.endsWith(".csv"))
                && !name.endsWith("ThresholdGuard.java")
                && !name.endsWith("ThresholdGuardTest.java");
    }

    public static void main(String[] args) {
        List<Violation> violations = scan(Path.of(args.length > 0 ? args[0] : "src/test"));
        if (violations.isEmpty()) {
            System.out.println("Threshold guard: the suite states no confidence floor.");
            return;
        }
        violations.forEach(v -> System.out.println("  " + v));
        throw new IllegalStateException(
                "The suite states the service's confidence floor in " + violations.size()
                        + " place(s). Assert \"exactly 1.0\" or \"below 1.0\" instead.");
    }
}
