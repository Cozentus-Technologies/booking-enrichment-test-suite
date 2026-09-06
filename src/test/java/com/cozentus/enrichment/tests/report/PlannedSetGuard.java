package com.cozentus.enrichment.tests.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * D-4. Fails the build when the planned set and the features disagree.
 *
 * <p>{@code spec/test-cases.yaml} had drifted thirty-seven cases behind the
 * feature files and nothing said so. Every figure with "planned" in its
 * denominator went quietly wrong: execution completeness rendered 139%, and
 * the critical execution rate - a blocking exit criterion - was measured
 * against a critical set that was missing half its rows. A stale catalogue
 * cannot be detected by running the tests, because the missing cases do not
 * fail; they are simply not counted. Something has to enumerate both sides and
 * compare them, and that is all this does.
 *
 * <p>Modelled on {@link com.cozentus.enrichment.tests.support.ReasonCoverage},
 * which does the same job for the flag reasons the contract allows.
 */
public final class PlannedSetGuard {

    /** Where the feature text lives once the build has generated and copied it. */
    private static final List<Path> FEATURE_ROOTS = List.of(
            Path.of("target/test-classes/features"),
            Path.of("target/generated-features"),
            Path.of("src/test/resources/features"));

    private static final Pattern TC_TAG = Pattern.compile("@(TC-\\d+)\\b");
    private static final Pattern CATALOGUE_ID = Pattern.compile("^- id:\\s*(TC-\\d+)\\s*$");

    private PlannedSetGuard() {
    }

    public record Report(Set<String> inFeatures, Set<String> inCatalogue) {

        /** Scenarios that exist and the plan does not know about. */
        public Set<String> uncatalogued() {
            Set<String> missing = new TreeSet<>(inFeatures);
            missing.removeAll(inCatalogue);
            return missing;
        }

        /** Plan rows with no scenario behind them, which inflate every denominator. */
        public Set<String> unimplemented() {
            Set<String> extra = new TreeSet<>(inCatalogue);
            extra.removeAll(inFeatures);
            return extra;
        }

        public boolean aligned() {
            return uncatalogued().isEmpty() && unimplemented().isEmpty();
        }

        public String describe() {
            if (aligned()) {
                return "Planned set aligned: %d cases in spec/test-cases.yaml and in the features."
                        .formatted(inCatalogue.size());
            }
            StringBuilder message = new StringBuilder(
                    "spec/test-cases.yaml and the feature files disagree.");
            if (!uncatalogued().isEmpty()) {
                message.append("%n  in the features, absent from the catalogue (%d): %s"
                        .formatted(uncatalogued().size(), uncatalogued()));
            }
            if (!unimplemented().isEmpty()) {
                message.append("%n  in the catalogue, absent from the features (%d): %s"
                        .formatted(unimplemented().size(), unimplemented()));
            }
            message.append(String.format("%n  Every 'planned' denominator in the report is "
                    + "computed from the catalogue, so the two have to match."));
            return message.toString().formatted();
        }
    }

    public static Report run(Path catalogue) {
        return new Report(inFeatures(), inCatalogue(catalogue));
    }

    static Set<String> inCatalogue(Path catalogue) {
        Set<String> ids = new TreeSet<>();
        if (!Files.exists(catalogue)) {
            return ids;
        }
        try {
            for (String line : Files.readAllLines(catalogue, StandardCharsets.UTF_8)) {
                Matcher matcher = CATALOGUE_ID.matcher(line.trim());
                if (matcher.matches()) {
                    ids.add(matcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + catalogue, e);
        }
        return ids;
    }

    static Set<String> inFeatures() {
        Set<String> ids = new TreeSet<>();
        for (Path root : FEATURE_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".feature")).toList()) {
                    Matcher matcher = TC_TAG.matcher(
                            Files.readString(file, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        ids.add(matcher.group(1));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not scan " + root, e);
            }
        }
        return ids;
    }

    public static void main(String[] args) {
        Report report = run(Path.of(args.length > 0 ? args[0] : "spec/test-cases.yaml"));
        System.out.println(report.describe());
        if (!report.aligned()) {
            throw new IllegalStateException(report.describe());
        }
    }
}
