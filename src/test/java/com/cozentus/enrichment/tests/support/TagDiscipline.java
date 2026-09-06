package com.cozentus.enrichment.tests.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fails the build when a scenario is not fully tagged (capability F-05).
 *
 * <p>Tags are the mechanism behind selective execution, priority reporting,
 * release gating and traceability — one taxonomy serving four purposes. An
 * untagged scenario cannot be sliced, cannot be reported against a priority,
 * cannot gate a release and cannot be traced to a requirement, so it is a build
 * failure rather than a warning.
 *
 * <p>Checking this by hand or by an ad-hoc script means it is checked when
 * someone remembers. Tag discipline decays within a sprint without enforcement.
 */
public final class TagDiscipline {

    /**
     * A-3. @smoke and @regression were in the type axis, which requires exactly
     * one tag. Every scenario already carries @functional, @contract, @resilience
     * or @volume, so neither could ever be added and the smoke profile selected
     * nothing while six documents promised it did.
     *
     * They describe when a scenario runs, not what kind of test it is, so they
     * belong on the selection axis alongside @fast, @slow and @nightly.
     */
    private static final Set<String> TYPES = Set.of(
            "@functional", "@contract", "@resilience", "@volume");

    /** Selection axis: optional, and a scenario may carry more than one. */
    private static final Set<String> SELECTION = Set.of(
            "@fast", "@slow", "@nightly", "@smoke", "@regression", "@quarantine");
    private static final Set<String> PRIORITIES = Set.of(
            "@critical", "@high", "@medium", "@low");
    private static final Set<String> AREAS = Set.of(
            "@city-correction", "@routing", "@flagging", "@passthrough",
            "@message-contract", "@schema", "@encoding", "@dates");

    /**
     * E-2. Environment axis: what a scenario needs of the environment it is
     * pointed at, as distinct from what kind of test it is or when it runs.
     *
     * <p>Its own axis rather than another selection tag, because a selection tag
     * says when someone chooses to run a scenario and this says where it is
     * capable of running at all. Optional.
     *
     * <p>Enumerated, and enforced as enumerated, because {@code Hooks} acts on
     * these exact strings. A scenario tagged {@code @requires-extended-cities}
     * reads to a person as excluded from a shared deployment and is not: it runs
     * there and fails, every run, and the tag is the reason nobody looks.
     */
    private static final Set<String> ENVIRONMENTS = Set.of("@requires-service-config");

    /** Anything shaped like an environment requirement has to be one of the above. */
    private static final String ENVIRONMENT_PREFIX = "@requires-";

    private static final Pattern TRACEABILITY = Pattern.compile("@TC-\\d+");

    /** One scenario that broke a rule, named so the fix is obvious. */
    public record Violation(String file, String scenario, String problem) {

        @Override
        public String toString() {
            return "%s :: %s%n      %s".formatted(file, scenario, problem);
        }
    }

    private TagDiscipline() {
    }

    public static void main(String[] args) {
        Path root = Path.of(args.length > 0 ? args[0] : "target/test-classes/features");
        List<Violation> violations = check(root);

        if (violations.isEmpty()) {
            System.out.printf("Tag discipline: every scenario in %s is fully tagged.%n", root);
            return;
        }
        System.err.printf("Tag discipline: %d scenario(s) are not fully tagged.%n%n", violations.size());
        violations.forEach(violation -> System.err.println("  " + violation));
        System.err.println("""

                Every scenario needs exactly one type tag, exactly one priority tag,
                at least one feature-area tag and exactly one @TC-nn traceability tag.
                See docs/TAGGING_GUIDELINE.md.""");
        System.exit(1);
    }

    public static List<Violation> check(Path featuresRoot) {
        if (!Files.exists(featuresRoot)) {
            throw new IllegalStateException(
                    "No features found at " + featuresRoot
                            + ". Run mvn process-test-classes first, so the generated feature exists too.");
        }
        List<Violation> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(featuresRoot)) {
            files.filter(path -> path.toString().endsWith(".feature"))
                    .sorted()
                    .forEach(path -> violations.addAll(checkFile(path)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scan " + featuresRoot, e);
        }
        return violations;
    }

    static List<Violation> checkFile(Path file) {
        try {
            return checkLines(file.getFileName().toString(),
                    Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    /** Package-private so the rules can be tested without touching the filesystem. */
    static List<Violation> checkLines(String fileName, List<String> lines) {
        List<Violation> violations = new ArrayList<>();
        List<String> pending = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("@")) {
                pending.addAll(List.of(line.split("\\s+")));
            } else if (line.startsWith("Scenario:") || line.startsWith("Scenario Outline:")) {
                violations.addAll(judge(fileName, line, pending));
                pending.clear();
            } else if (line.startsWith("Feature:")) {
                // Feature-level tags apply to every scenario beneath, so they carry over.
                pending.clear();
            }
        }
        return violations;
    }

    private static List<Violation> judge(String file, String scenario, List<String> tags) {
        List<Violation> violations = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>(tags);

        long types = unique.stream().filter(TYPES::contains).count();
        long priorities = unique.stream().filter(PRIORITIES::contains).count();
        long areas = unique.stream().filter(AREAS::contains).count();
        long traceability = unique.stream().filter(tag -> TRACEABILITY.matcher(tag).matches()).count();
        List<String> unknownEnvironment = unique.stream()
                .filter(tag -> tag.startsWith(ENVIRONMENT_PREFIX))
                .filter(tag -> !ENVIRONMENTS.contains(tag))
                .toList();

        if (types != 1) {
            violations.add(new Violation(file, scenario,
                    "needs exactly one type tag, has " + types + " of " + TYPES));
        }
        if (priorities != 1) {
            violations.add(new Violation(file, scenario,
                    "needs exactly one priority tag, has " + priorities + " of " + PRIORITIES));
        }
        if (areas < 1) {
            violations.add(new Violation(file, scenario,
                    "needs at least one feature-area tag from " + AREAS));
        }
        if (!unknownEnvironment.isEmpty()) {
            violations.add(new Violation(file, scenario,
                    "carries unrecognised environment tag(s) " + unknownEnvironment
                            + "; the suite acts only on " + ENVIRONMENTS
                            + ", so a scenario tagged anything else is never excluded"));
        }
        if (traceability != 1) {
            violations.add(new Violation(file, scenario,
                    "needs exactly one @TC-nn traceability tag, has " + traceability));
        }
        return violations;
    }
}
