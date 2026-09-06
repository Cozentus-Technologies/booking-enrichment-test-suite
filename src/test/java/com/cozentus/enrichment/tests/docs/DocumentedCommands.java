package com.cozentus.enrichment.tests.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * F-3. Reads the commands and artefact paths the documentation promises, so
 * they can be checked against what the build actually does.
 *
 * <p>Documented commands rot silently. A tag filter that once selected a slice
 * keeps running after the tag is renamed, reports success having executed
 * nothing, and reads as a passing suite - which is exactly what happened to
 * {@code run-tests.sh smoke}, green for months while selecting zero scenarios.
 * A promised output path rots the same way: six documents named
 * {@code target/scenario-coverage.md} as the release gate while nothing in the
 * build produced it.
 */
public final class DocumentedCommands {

    /** {@code "..."} and friends are placeholders in prose, not commands to run. */
    private static final Set<String> PLACEHOLDERS = Set.of("...", "<tag>", "<expression>");

    /**
     * A quoted expression runs to its closing quote, not to the first space:
     * {@code "not @nightly"} is one expression, and truncating it at the space
     * yields {@code not}, which is not a parseable tag expression at all.
     */
    private static final Pattern TAG_FILTER = Pattern.compile(
            "-Dcucumber\\.filter\\.tags=(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'`]+))");
    private static final Pattern PROFILE_CALL =
            Pattern.compile("(?:bash |\\./)run-tests\\.sh\\s+([a-z]+)");
    private static final Pattern ARTEFACT =
            Pattern.compile("target/[A-Za-z0-9._/-]+");
    private static final Pattern SHELL_PROFILE =
            Pattern.compile("^\\s*([a-z]+)\\)\\s*$");

    private DocumentedCommands() {
    }

    /** One documented instruction, with the file and line it came from. */
    public record Documented(Path file, int line, String kind, String value) {

        @Override
        public String toString() {
            return "%s:%d  %s %s".formatted(file, line, kind, value);
        }
    }

    public static List<Documented> tagExpressionsIn(List<Path> docs) {
        List<Documented> found = new ArrayList<>();
        for (Path doc : docs) {
            List<String> lines = readLines(doc);
            for (int i = 0; i < lines.size(); i++) {
                Matcher filter = TAG_FILTER.matcher(lines.get(i));
                while (filter.find()) {
                    String expression = captured(filter);
                    if (!PLACEHOLDERS.contains(expression)) {
                        found.add(new Documented(doc, i + 1, "tags", expression));
                    }
                }
            }
        }
        return found;
    }

    public static List<Documented> profileCallsIn(List<Path> docs) {
        List<Documented> found = new ArrayList<>();
        for (Path doc : docs) {
            List<String> lines = readLines(doc);
            for (int i = 0; i < lines.size(); i++) {
                Matcher call = PROFILE_CALL.matcher(lines.get(i));
                while (call.find()) {
                    found.add(new Documented(doc, i + 1, "profile", call.group(1)));
                }
            }
        }
        return found;
    }

    public static Set<String> artefactPathsIn(List<Path> docs) {
        Set<String> paths = new LinkedHashSet<>();
        for (Path doc : docs) {
            Matcher matcher = ARTEFACT.matcher(String.join("\n", readLines(doc)));
            while (matcher.find()) {
                paths.add(matcher.group().replaceAll("/$", ""));
            }
        }
        return paths;
    }

    /**
     * The profiles {@code run-tests.sh} actually implements, mapped to the tag
     * expression each one passes. Parsed from the script rather than copied, so
     * a profile renamed there is a documentation failure rather than a silent
     * divergence.
     */
    public static Map<String, String> profilesInScript(Path script) {
        Map<String, String> profiles = new LinkedHashMap<>();
        List<String> lines = readLines(script);
        String current = null;

        for (String line : lines) {
            Matcher label = SHELL_PROFILE.matcher(line);
            if (label.find()) {
                current = label.group(1);
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher filter = TAG_FILTER.matcher(line);
            if (filter.find()) {
                profiles.put(current, captured(filter));
                current = null;
            } else if (line.trim().equals(";;")) {
                // A profile that runs something other than a tag-filtered suite
                // (preflight, for example) is still a real profile.
                profiles.putIfAbsent(current, "");
                current = null;
            }
        }
        return profiles;
    }

    /** Every scenario in the built features, as its set of tags. */
    public static List<Set<String>> scenarioTags() {
        Path root = Path.of("target/test-classes/features");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "No built features at " + root + "; run after process-test-classes, or every "
                            + "documented command would look like it selects nothing.");
        }
        List<Set<String>> scenarios = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path feature : files.filter(p -> p.toString().endsWith(".feature")).toList()) {
                Set<String> featureTags = new LinkedHashSet<>();
                Set<String> pending = new LinkedHashSet<>();
                boolean beforeFeature = true;

                for (String line : readLines(feature)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("@")) {
                        pending.addAll(List.of(trimmed.split("\\s+")));
                    } else if (trimmed.startsWith("Feature:")) {
                        featureTags.addAll(pending);
                        pending.clear();
                        beforeFeature = false;
                    } else if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
                        Set<String> tags = new LinkedHashSet<>(featureTags);
                        tags.addAll(pending);
                        scenarios.add(tags);
                        pending.clear();
                    } else if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !beforeFeature) {
                        pending.clear();
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scan " + root, e);
        }
        return scenarios;
    }

    /** @return how many scenarios a Cucumber tag expression selects */
    public static long selectionSize(String expression, List<Set<String>> scenarios) {
        var parsed = io.cucumber.tagexpressions.TagExpressionParser.parse(expression);
        return scenarios.stream().filter(tags -> parsed.evaluate(List.copyOf(tags))).count();
    }

    /** Whichever of the three quoting alternatives matched. */
    private static String captured(Matcher matcher) {
        for (int group = 1; group <= 3; group++) {
            if (matcher.group(group) != null) {
                return matcher.group(group).trim();
            }
        }
        return "";
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
