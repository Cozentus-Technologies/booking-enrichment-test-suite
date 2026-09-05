package com.cozentus.enrichment.tests.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reconciles the scenario catalogue in TEST_SUITE_SPEC.md section 7 against a
 * Cucumber run and writes {@code target/scenario-coverage.md}, in the shape
 * described in section 8.3.
 *
 * <p>The catalogue ("defined") is never hand-maintained here: it is derived by
 * scanning the {@code .feature} files on the classpath for {@code @TC-nn}
 * tags, so a scenario added to a feature file is picked up the next time this
 * runs, with nothing in this class to update. What actually happened
 * ("executed") comes from {@code cucumber.json} alone. The two are reconciled
 * by TC id.
 *
 * <p>A Scenario Outline counts once in the catalogue no matter how many
 * Examples rows it expands into at run time: all rows carry the same
 * {@code @TC-nn} tag (TEST_SUITE_SPEC.md section 6), and this class collapses
 * them back to one entry, passed only if every row passed.
 */
public final class CoverageReporter {

    private static final Pattern TAG_LINE = Pattern.compile("^@\\S.*$");
    private static final Pattern TC_TAG = Pattern.compile("^@TC-(\\S+)$");
    private static final Pattern SCENARIO_LINE =
            Pattern.compile("^(Scenario|Scenario Outline|Scenario Template):\\s*(.*)$");
    private static final Pattern FEATURE_LINE = Pattern.compile("^Feature:\\s*(.*)$");

    /** Feature-area tags reported "By category" (task section 1), in reporting order. */
    private static final List<String> CATEGORY_TAGS = List.of(
            "@city-correction", "@routing", "@flagging", "@passthrough",
            "@message-contract", "@schema", "@encoding", "@dates", "@volume");

    /** Priority axis, TEST_SUITE_SPEC.md section 6.1. Always reported, even at 0/0. */
    private static final List<String> PRIORITY_TAGS = List.of("@critical", "@high", "@medium", "@low");

    private static final String DEFAULT_CUCUMBER_JSON = "target/cucumber-reports/cucumber.json";
    private static final String DEFAULT_OUTPUT = "target/scenario-coverage.md";

    private CoverageReporter() {
    }

    // ---- data model --------------------------------------------------------

    /** One {@code @TC-nn} scenario found in the feature files, whether or not it ran. */
    public record DefinedScenario(String tcId, String name, String featureFile, Set<String> tags) {
        public DefinedScenario {
            Objects.requireNonNull(tcId, "tcId");
            tags = Set.copyOf(tags);
        }
    }

    /** One {@code @TC-nn} scenario's outcome from cucumber.json, collapsed across any Examples rows. */
    public record ExecutedScenario(String tcId, String name, Set<String> tags, boolean passed,
                                    String failureMessage) {
        public ExecutedScenario {
            Objects.requireNonNull(tcId, "tcId");
            tags = Set.copyOf(tags);
        }
    }

    /** passed/total for one tag line — one priority, or one category. */
    public record TagCount(String tag, int passed, int total) {
        public int percent() {
            return pct(passed, total);
        }
    }

    /**
     * The reconciled result: everything {@link #render(CoverageReport)} needs, and
     * nothing it has to compute for itself outside of formatting.
     */
    public record CoverageReport(String environment, Instant generatedAt,
                                  List<DefinedScenario> defined, List<ExecutedScenario> executed) {

        public CoverageReport {
            defined = List.copyOf(defined);
            executed = List.copyOf(executed);
        }

        public int definedCount() {
            return defined.size();
        }

        public int executedCount() {
            return executed.size();
        }

        public int executedPercent() {
            return pct(executedCount(), definedCount());
        }

        public int passedCount() {
            return (int) executed.stream().filter(ExecutedScenario::passed).count();
        }

        public int failedCount() {
            return executedCount() - passedCount();
        }

        /** One line per priority tag, in the fixed order of the axis, 0/0 included rather than omitted. */
        public List<TagCount> byPriority() {
            return PRIORITY_TAGS.stream().map(this::tagCount).toList();
        }

        /** One line per feature-area tag that at least one defined scenario actually carries. */
        public List<TagCount> byCategory() {
            Set<String> inUse = defined.stream()
                    .flatMap(d -> d.tags().stream())
                    .collect(Collectors.toSet());
            return CATEGORY_TAGS.stream().filter(inUse::contains).map(this::tagCount).toList();
        }

        private TagCount tagCount(String tag) {
            Map<String, ExecutedScenario> byTc = executed.stream()
                    .collect(Collectors.toMap(ExecutedScenario::tcId, e -> e, (a, b) -> a));
            List<String> withTag = defined.stream()
                    .filter(d -> d.tags().contains(tag))
                    .map(DefinedScenario::tcId)
                    .toList();
            int total = withTag.size();
            long passed = withTag.stream()
                    .map(byTc::get)
                    .filter(Objects::nonNull)
                    .filter(ExecutedScenario::passed)
                    .count();
            return new TagCount(tag, (int) passed, total);
        }

        /** Executed TCs that did not pass, in TC order. */
        public List<ExecutedScenario> failed() {
            return executed.stream()
                    .filter(e -> !e.passed())
                    .sorted(Comparator.comparing(ExecutedScenario::tcId, CoverageReporter::byTcNumber))
                    .toList();
        }

        /**
         * Defined TCs absent from the run. Produced by a tag filter (or a run that
         * simply did not cover everything) — not evidence of a defect, but not
         * something to mistake for coverage either (section 8.4).
         */
        public List<DefinedScenario> definedButNotExecuted() {
            Set<String> executedIds = executed.stream().map(ExecutedScenario::tcId).collect(Collectors.toSet());
            return defined.stream()
                    .filter(d -> !executedIds.contains(d.tcId()))
                    .sorted(Comparator.comparing(DefinedScenario::tcId, CoverageReporter::byTcNumber))
                    .toList();
        }
    }

    private static int pct(int numerator, int denominator) {
        return denominator == 0 ? 0 : (int) Math.round(numerator * 100.0 / denominator);
    }

    private static int byTcNumber(String a, String b) {
        return Integer.compare(tcNumber(a), tcNumber(b));
    }

    private static int tcNumber(String tcId) {
        Matcher m = Pattern.compile("(\\d+)$").matcher(tcId);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    // ---- scanning feature files for the "defined" catalogue ---------------

    /** Scans the {@code features} directory on the current thread's classpath. */
    public static List<DefinedScenario> scanDefinedScenariosFromClasspath() {
        return scanDefinedScenariosFromClasspath(Thread.currentThread().getContextClassLoader());
    }

    public static List<DefinedScenario> scanDefinedScenariosFromClasspath(ClassLoader classLoader) {
        URL url = classLoader.getResource("features");
        if (url == null) {
            return List.of();
        }
        try {
            return scanDefinedScenarios(Paths.get(url.toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not resolve the features/ classpath root", e);
        }
    }

    /** Scans every {@code .feature} file under {@code featuresRoot} for {@code @TC-nn}-tagged scenarios. */
    public static List<DefinedScenario> scanDefinedScenarios(Path featuresRoot) {
        if (!Files.isDirectory(featuresRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(featuresRoot)) {
            List<Path> featureFiles = paths.filter(p -> p.toString().endsWith(".feature")).sorted().toList();
            List<DefinedScenario> found = new ArrayList<>();
            for (Path file : featureFiles) {
                found.addAll(scanFeatureFile(featuresRoot, file));
            }
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scan feature files under " + featuresRoot, e);
        }
    }

    private static List<DefinedScenario> scanFeatureFile(Path root, Path file) throws IOException {
        String relative = root.relativize(file).toString().replace('\\', '/');
        List<DefinedScenario> scenarios = new ArrayList<>();
        Set<String> pendingTags = new LinkedHashSet<>();

        for (String rawLine : Files.readAllLines(file)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (TAG_LINE.matcher(line).matches()) {
                pendingTags.addAll(List.of(line.split("\\s+")));
                continue;
            }
            Matcher scenarioMatch = SCENARIO_LINE.matcher(line);
            if (scenarioMatch.matches()) {
                String name = scenarioMatch.group(2).strip();
                String tcId = extractTcId(pendingTags);
                if (tcId != null) {
                    scenarios.add(new DefinedScenario(tcId, name, relative, pendingTags));
                }
                pendingTags = new LinkedHashSet<>();
                continue;
            }
            if (FEATURE_LINE.matcher(line).matches()) {
                pendingTags = new LinkedHashSet<>();
            }
            // Anything else — a description line, a step, a comment, a table row —
            // carries no tag of its own and does not disturb tags already pending
            // for the next Scenario / Scenario Outline line.
        }
        return scenarios;
    }

    private static String extractTcId(Set<String> tags) {
        for (String tag : tags) {
            Matcher m = TC_TAG.matcher(tag);
            if (m.matches()) {
                return "TC-" + m.group(1);
            }
        }
        return null;
    }

    // ---- parsing cucumber.json for the "executed" outcomes -----------------

    public static List<ExecutedScenario> parseExecuted(Path cucumberJsonPath) {
        try {
            if (!Files.exists(cucumberJsonPath) || Files.size(cucumberJsonPath) == 0) {
                return List.of();
            }
            return parseExecuted(Files.readString(cucumberJsonPath));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + cucumberJsonPath, e);
        }
    }

    /** Parses the legacy Cucumber JSON formatter's output (an array of features). */
    public static List<ExecutedScenario> parseExecuted(String cucumberJson) {
        if (cucumberJson == null || cucumberJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(cucumberJson);
        } catch (IOException e) {
            throw new UncheckedIOException("cucumber.json did not parse as JSON", e);
        }

        Map<String, List<RawResult>> byTc = new LinkedHashMap<>();
        for (JsonNode feature : root) {
            for (JsonNode element : feature.path("elements")) {
                if (!"scenario".equals(element.path("type").asText())) {
                    continue; // background blocks carry no @TC tag and are not a scenario outcome
                }
                Set<String> tags = new LinkedHashSet<>();
                for (JsonNode tag : element.path("tags")) {
                    tags.add(tag.path("name").asText());
                }
                String tcId = extractTcId(tags);
                if (tcId == null) {
                    continue; // enforcement (section 6.4) means this should not happen in practice
                }
                Outcome outcome = evaluate(element);
                byTc.computeIfAbsent(tcId, k -> new ArrayList<>())
                        .add(new RawResult(element.path("name").asText(), tags, outcome.passed(), outcome.failure()));
            }
        }

        List<ExecutedScenario> results = new ArrayList<>();
        for (Map.Entry<String, List<RawResult>> entry : byTc.entrySet()) {
            List<RawResult> rows = entry.getValue();
            boolean allPassed = rows.stream().allMatch(RawResult::passed);
            String failure = rows.stream().map(RawResult::failure).filter(Objects::nonNull).findFirst().orElse(null);
            results.add(new ExecutedScenario(entry.getKey(), rows.get(0).name(), rows.get(0).tags(),
                    allPassed, failure));
        }
        return results;
    }

    private record RawResult(String name, Set<String> tags, boolean passed, String failure) {
    }

    private record Outcome(boolean passed, String failure) {
    }

    /** Walks before-hooks, steps and after-hooks in execution order; first non-pass wins the failure message. */
    private static Outcome evaluate(JsonNode element) {
        List<JsonNode> actions = new ArrayList<>();
        element.path("before").forEach(actions::add);
        element.path("steps").forEach(actions::add);
        element.path("after").forEach(actions::add);

        if (actions.isEmpty()) {
            return new Outcome(false, "scenario has no steps");
        }
        for (JsonNode action : actions) {
            JsonNode result = action.path("result");
            String status = result.path("status").asText("unknown");
            if (!"passed".equals(status)) {
                String errorMessage = result.hasNonNull("error_message") ? result.path("error_message").asText() : null;
                String label = action.has("name") ? action.path("name").asText() : "hook";
                String message = errorMessage != null && !errorMessage.isBlank()
                        ? firstLine(errorMessage)
                        : "step \"" + label + "\" was " + status;
                return new Outcome(false, message);
            }
        }
        return new Outcome(true, null);
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline >= 0 ? text.substring(0, newline) : text;
        return line.strip();
    }

    // ---- assembling and rendering the report --------------------------------

    public static CoverageReport build(List<DefinedScenario> defined, List<ExecutedScenario> executed,
                                        String environment, Instant generatedAt) {
        return new CoverageReport(environment, generatedAt, defined, executed);
    }

    public static String render(CoverageReport report) {
        StringBuilder out = new StringBuilder();
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(report.generatedAt().truncatedTo(ChronoUnit.SECONDS));

        out.append("Scenario Coverage — Booking Enrichment Test Suite\n");
        out.append("Run: ").append(timestamp).append("   Environment: ").append(report.environment()).append("\n\n");

        out.append(String.format("Defined in spec      %d%n", report.definedCount()));
        out.append(String.format("Executed             %d   (%d%%)%n", report.executedCount(), report.executedPercent()));
        out.append(String.format("Passed               %d%n", report.passedCount()));
        out.append(String.format("Failed               %d%n", report.failedCount()));
        out.append('\n');

        out.append("By priority\n");
        for (TagCount tc : report.byPriority()) {
            String label = capitalize(tc.tag().substring(1));
            String gate = "@critical".equals(tc.tag()) ? "    <- release gate" : "";
            out.append(String.format("  %-11s %2d/%-2d   %3d%%%s%n",
                    label, tc.passed(), tc.total(), tc.percent(), gate));
        }
        out.append('\n');

        out.append("By category\n");
        List<TagCount> categories = report.byCategory();
        if (categories.isEmpty()) {
            out.append("  (no feature-area tag is in use in the defined catalogue)\n");
        }
        for (TagCount tc : categories) {
            out.append(String.format("  %-18s %2d/%-2d   %3d%%%n", tc.tag(), tc.passed(), tc.total(), tc.percent()));
        }
        out.append('\n');

        out.append("Failed\n");
        List<ExecutedScenario> failed = report.failed();
        if (failed.isEmpty()) {
            out.append("  none\n");
        } else {
            for (ExecutedScenario ex : failed) {
                String tags = ex.tags().stream().sorted().collect(Collectors.joining(" "));
                out.append(String.format("  %-7s %s%n", ex.tcId(), tags));
                String message = ex.failureMessage() == null ? "(no failure message captured)" : ex.failureMessage();
                out.append("         ").append(message).append('\n');
            }
        }
        out.append('\n');

        out.append("Defined but not executed\n");
        out.append("  (produced by a tag filter; a TC listed here counts toward Defined above but not toward\n");
        out.append("   Executed, and must not be mistaken for coverage — TEST_SUITE_SPEC.md section 8.4)\n");
        List<DefinedScenario> notExecuted = report.definedButNotExecuted();
        if (notExecuted.isEmpty()) {
            out.append("  none\n");
        } else {
            for (DefinedScenario d : notExecuted) {
                String tags = d.tags().stream().sorted().collect(Collectors.joining(" "));
                out.append(String.format("  %-7s %-60s %s%n", d.tcId(), d.name(), tags));
            }
        }
        out.append('\n');

        out.append("---\n\n");
        out.append("Critical pass rate is reported separately above and is the release gate. The overall pass\n");
        out.append("rate (Passed / Executed) is informational only — TEST_SUITE_SPEC.md section 8.4.\n\n");
        out.append("Coverage position (TEST_SUITE_SPEC.md section 8.4): code coverage cannot be produced by a\n");
        out.append("black-box suite and is not attempted here. What this report gives is scenario coverage\n");
        out.append("against TEST_SUITE_SPEC.md section 7 — defined versus executed versus passed. Code coverage\n");
        out.append("is JaCoCo on the developer's own unit tests of the matcher and enricher, and belongs to\n");
        out.append("them, not to this suite.\n");

        return out.toString();
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    // ---- entry point ---------------------------------------------------------

    public static void main(String[] args) throws IOException {
        String environment = System.getProperty("suite.env", "local");
        Path cucumberJson = Path.of(args.length > 0 ? args[0] : DEFAULT_CUCUMBER_JSON);
        Path output = Path.of(args.length > 1 ? args[1] : DEFAULT_OUTPUT);

        List<DefinedScenario> defined = scanDefinedScenariosFromClasspath();
        List<ExecutedScenario> executed = parseExecuted(cucumberJson);
        CoverageReport report = build(defined, executed, environment, Instant.now());
        String markdown = render(report);

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, markdown);
        System.out.print(markdown);
    }
}
