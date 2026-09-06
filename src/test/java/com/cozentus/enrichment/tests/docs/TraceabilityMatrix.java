package com.cozentus.enrichment.tests.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * F-2. Generates the traceability matrix from the feature files instead of
 * trusting a hand-maintained copy of them.
 *
 * <p>The committed matrix claimed in its own header to have been "derived by
 * scanning the feature files" and was thirty-seven cases behind them: every case
 * work package B added was missing, and nothing failed. That is the failure mode
 * this class exists to make impossible - a document that describes the suite is
 * worth less than nothing when it is confidently wrong, because a reader has no
 * way to tell which half is stale.
 *
 * <p>One column cannot be scanned: which specification clause a case exists to
 * prove. That is judgement, not fact, so it lives in
 * {@code docs/traceability-clauses.tsv} and is joined in by id. A case with no
 * clause fails the build rather than rendering an empty cell, because an
 * untraceable case is precisely what a traceability matrix is meant to surface.
 */
public final class TraceabilityMatrix {

    private static final Path CLAUSES = Path.of("docs/traceability-clauses.tsv");
    private static final Path OUTPUT = Path.of("docs/traceability-matrix.md");

    /** Everything above this line is prose and is preserved verbatim. */
    private static final String MARKER = "<!-- GENERATED BELOW THIS LINE - see TraceabilityMatrix.java -->";

    private static final List<Path> FEATURE_ROOTS = List.of(
            Path.of("target/test-classes/features"),
            Path.of("src/test/resources/features"));

    private static final Pattern CASE_TAG = Pattern.compile("@(TC-\\d+)\\b");
    private static final Pattern SCENARIO = Pattern.compile("^\\s*Scenario(?: Outline)?:\\s*(.+?)\\s*$");

    private static final Set<String> PRIORITIES =
            Set.of("@critical", "@high", "@medium", "@low");
    private static final Set<String> TYPES =
            Set.of("@functional", "@contract", "@resilience", "@volume");

    private TraceabilityMatrix() {
    }

    /** One row: everything but {@code clause} is scanned. */
    public record Row(String id, String title, String feature,
                      String priority, String types, String clause) {
    }

    public static void main(String[] args) {
        List<Row> rows = build();
        String rendered = render(rows);
        String existing = read(OUTPUT);
        String preamble = preambleOf(existing);

        String document = preamble + MARKER + "\n" + rendered;

        if (args.length > 0 && args[0].equals("--check")) {
            if (!document.equals(existing)) {
                // A count is not a diagnosis: a renamed or retitled case leaves the
                // totals equal and tells the reader nothing about what moved.
                Set<String> committed = caseIds(existing);
                Set<String> scanned = rows.stream().map(Row::id)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                Set<String> onlyScanned = new TreeSet<>(scanned);
                onlyScanned.removeAll(committed);
                Set<String> onlyCommitted = new TreeSet<>(committed);
                onlyCommitted.removeAll(scanned);

                throw new IllegalStateException("""
                        The traceability matrix is out of date.
                          in the features but not the matrix  %s
                          in the matrix but not the features  %s
                          %s
                        Regenerate it with: mvn -q process-test-classes"""
                        .formatted(
                                onlyScanned.isEmpty() ? "none" : onlyScanned,
                                onlyCommitted.isEmpty() ? "none" : onlyCommitted,
                                onlyScanned.isEmpty() && onlyCommitted.isEmpty()
                                        ? "The same cases are listed, so a title, priority, type or "
                                                + "clause has changed."
                                        : ""));
            }
            System.out.println("Traceability matrix: current, " + rows.size() + " case(s).");
            return;
        }
        write(OUTPUT, document);
        System.out.println("Traceability matrix: wrote " + rows.size() + " case(s) to " + OUTPUT);
    }

    static List<Row> build() {
        Map<String, String> clauses = readClauses();
        Map<String, List<Row>> byFeature = new TreeMap<>();
        Set<String> seen = new TreeSet<>();

        for (Path feature : featureFiles()) {
            String name = feature.getFileName().toString();
            List<String> lines = readLines(feature);
            List<String> pendingTags = new ArrayList<>();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("@")) {
                    pendingTags.addAll(List.of(trimmed.split("\\s+")));
                    continue;
                }
                Matcher scenario = SCENARIO.matcher(line);
                if (!scenario.find()) {
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        pendingTags.clear();
                    }
                    continue;
                }
                Matcher tag = CASE_TAG.matcher(String.join(" ", pendingTags));
                if (tag.find()) {
                    String id = tag.group(1);
                    seen.add(id);
                    byFeature.computeIfAbsent(name, key -> new ArrayList<>())
                            .add(new Row(id, scenario.group(1), name,
                                    oneOf(pendingTags, PRIORITIES),
                                    allOf(pendingTags, TYPES),
                                    clauses.getOrDefault(id, "")));
                }
                pendingTags.clear();
            }
        }

        List<String> untraceable = seen.stream().filter(id -> !clauses.containsKey(id)).toList();
        if (!untraceable.isEmpty()) {
            throw new IllegalStateException(
                    "These cases have no clause in " + CLAUSES + ", so they cannot be traced to "
                            + "anything: " + untraceable + ". Add a line for each, or delete the case.");
        }
        List<Row> all = new ArrayList<>();
        byFeature.values().forEach(all::addAll);
        return all;
    }

    static String render(List<Row> rows) {
        Map<String, List<Row>> byFeature = new LinkedHashMap<>();
        rows.stream().sorted(java.util.Comparator
                        .comparing(Row::feature)
                        .thenComparingInt(row -> Integer.parseInt(row.id().substring(3))))
                .forEach(row -> byFeature.computeIfAbsent(row.feature(), k -> new ArrayList<>()).add(row));

        StringBuilder out = new StringBuilder("""

                Generated by `TraceabilityMatrix` from the feature files, the test data
                table and `traceability-clauses.tsv`. Do not hand-edit: the next build
                overwrites it, and `mvn test` fails if the committed copy has drifted.
                To add a case, add the scenario and one line to the clause file.

                """);

        for (var entry : byFeature.entrySet()) {
            List<Row> group = entry.getValue();
            out.append("## ").append(entry.getKey()).append("\n\n")
               .append(group.size()).append(" case(s).\n\n")
               .append("| Case | Scenario | Priority | Type | Derived from |\n")
               .append("|---|---|---|---|---|\n");
            for (Row row : group) {
                out.append("| ").append(row.id())
                   .append(" | ").append(escape(row.title()))
                   .append(" | ").append(row.priority())
                   .append(" | ").append(row.types())
                   .append(" | ").append(escape(row.clause()))
                   .append(" |\n");
            }
            out.append('\n');
        }
        out.append("**Total: ").append(rows.size()).append(" traceable case(s).**\n");
        return out.toString();
    }

    private static Map<String, String> readClauses() {
        Map<String, String> clauses = new LinkedHashMap<>();
        for (String line : readLines(CLAUSES)) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            if (parts.length == 2) {
                clauses.put(parts[0].trim(), parts[1].trim());
            }
        }
        return clauses;
    }

    /** Prefers the generated features, which include the CSV-driven cases. */
    private static List<Path> featureFiles() {
        for (Path root : FEATURE_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> features = files.filter(p -> p.toString().endsWith(".feature")).sorted().toList();
                if (!features.isEmpty()) {
                    return features;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not scan " + root, e);
            }
        }
        throw new IllegalStateException(
                "No feature files found under " + FEATURE_ROOTS + ". Run after process-test-classes, "
                        + "or the matrix would be generated empty and look like a suite with no tests.");
    }

    private static String preambleOf(String existing) {
        int marker = existing.indexOf(MARKER);
        return marker < 0 ? existing.isBlank() ? "# Traceability Matrix\n\n" : existing + "\n\n"
                : existing.substring(0, marker);
    }

    private static Set<String> caseIds(String document) {
        Matcher matcher = Pattern.compile("^\\| (TC-\\d+) \\|", Pattern.MULTILINE).matcher(document);
        Set<String> ids = new TreeSet<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static String oneOf(List<String> tags, Set<String> allowed) {
        return tags.stream().filter(allowed::contains).findFirst().orElse("-").replace("@", "");
    }

    private static String allOf(List<String> tags, Set<String> allowed) {
        String found = tags.stream().filter(allowed::contains)
                .map(tag -> tag.replace("@", "")).distinct()
                .reduce((a, b) -> a + ", " + b).orElse("-");
        return found;
    }

    /** A pipe in a title would end the table cell early. */
    private static String escape(String text) {
        return text.replace("|", "\\|");
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + path, e);
        }
    }
}
