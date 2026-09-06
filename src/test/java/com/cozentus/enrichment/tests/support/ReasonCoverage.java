package com.cozentus.enrichment.tests.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * B-4. Fails the build when the contract allows a flag reason that no scenario
 * asserts.
 *
 * <p>Three of the seven reasons were unasserted, and nothing said so. Coverage
 * measured as "scenarios that pass" cannot show this: the missing scenarios do
 * not fail, they are simply absent, and an absence is invisible unless something
 * enumerates what should have been there. The list of what should be there is
 * read from the published schema rather than kept in the suite, so a reason
 * added to the contract shows up here instead of quietly staying uncovered.
 */
public final class ReasonCoverage {

    private static final String SCHEMA = "/contracts/booking-flagged-v1.schema.json";

    /** Where the feature text lives once the build has generated and copied it. */
    private static final List<Path> FEATURE_ROOTS = List.of(
            Path.of("target/test-classes/features"),
            Path.of("target/generated-features"),
            Path.of("src/test/resources/features"));

    private ReasonCoverage() {
    }

    public record Report(Set<String> inContract, Set<String> uncovered) {

        public boolean allCovered() {
            return uncovered.isEmpty();
        }

        public String describe() {
            return uncovered.isEmpty()
                    ? "All %d flag reasons are asserted by at least one scenario."
                            .formatted(inContract.size())
                    : "Uncovered flag reasons (%d of %d): %s"
                            .formatted(uncovered.size(), inContract.size(), uncovered);
        }
    }

    public static Report run() {
        Set<String> reasons = reasonsInContract();
        return new Report(reasons, uncoveredIn(reasons, featureText()));
    }

    /** The reasons the contract permits, read from the schema's own enum. */
    public static Set<String> reasonsInContract() {
        try (InputStream in = ReasonCoverage.class.getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("Missing contract: " + SCHEMA);
            }
            Set<String> found = new TreeSet<>();
            collectFlagReasons(new ObjectMapper().readTree(in), found);
            if (found.isEmpty()) {
                throw new IllegalStateException(
                        "No flagReason enum found in " + SCHEMA + "; the check would pass vacuously");
            }
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + SCHEMA, e);
        }
    }

    /**
     * An enum is recognised by its values, not by its position, so restructuring
     * the schema does not silently empty this set.
     */
    private static void collectFlagReasons(JsonNode node, Set<String> into) {
        if (node.isObject()) {
            JsonNode values = node.get("enum");
            if (values != null && values.isArray()) {
                for (JsonNode value : values) {
                    String text = value.asText();
                    if (text.endsWith("_CITY") || text.equals("MALFORMED_MESSAGE")) {
                        into.add(text);
                    }
                }
            }
            node.forEach(child -> collectFlagReasons(child, into));
        } else if (node.isArray()) {
            node.forEach(child -> collectFlagReasons(child, into));
        }
    }

    /**
     * @param reasons what the contract allows
     * @param featureText every line of every feature file
     * @return the reasons no line asserts
     */
    public static Set<String> uncoveredIn(Set<String> reasons, List<String> featureText) {
        if (featureText.isEmpty()) {
            throw new IllegalStateException(
                    "Found no feature text to check, so every reason would look uncovered. "
                            + "Run the check after the features are generated and copied.");
        }
        Set<String> uncovered = new LinkedHashSet<>();
        for (String reason : reasons) {
            boolean asserted = featureText.stream().anyMatch(line -> line.contains(reason));
            if (!asserted) {
                uncovered.add(reason);
            }
        }
        return uncovered;
    }

    /** Every line of every feature file the build can see. */
    static List<String> featureText() {
        for (Path root : FEATURE_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> features = files.filter(p -> p.toString().endsWith(".feature")).toList();
                if (!features.isEmpty()) {
                    List<String> lines = new java.util.ArrayList<>();
                    for (Path feature : features) {
                        lines.addAll(Files.readAllLines(feature, StandardCharsets.UTF_8));
                    }
                    return lines;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read features under " + root, e);
            }
        }
        return List.of();
    }

    /** Build entry point: prints the report and fails the build if anything is uncovered. */
    public static void main(String[] args) {
        Report report = run();
        System.out.println("Flag reason coverage: " + report.describe());
        if (!report.allCovered()) {
            throw new IllegalStateException(report.describe());
        }
    }
}
