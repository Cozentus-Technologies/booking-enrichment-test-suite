package com.cozentus.enrichment.tests.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.docs.DocumentedCommands.Documented;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-3. Build-verifies the documentation.
 *
 * <p>Every claim checked here is one that was wrong at some point in this
 * repository's history, and in each case nothing failed. A tag filter keeps
 * running after its tag is renamed and reports success having executed nothing;
 * a promised output path stays promised long after the thing that wrote it
 * stopped being bound to any phase.
 */
class DocumentationTest {

    private static final List<Path> DOCS = List.of(
            Path.of("README.md"),
            Path.of("docs/TAGGING_GUIDELINE.md"),
            Path.of("docs/TEST_STRATEGY.md"));

    private static final Path SCRIPT = Path.of("run-tests.sh");

    private static final List<Set<String>> SCENARIOS = DocumentedCommands.scenarioTags();

    @Test
    @DisplayName("the suite has scenarios to check the documentation against")
    void thereAreScenariosToSelect() {
        // Without this the whole class passes vacuously: every "selects nothing"
        // assertion below would be comparing against an empty suite.
        assertThat(SCENARIOS)
                .as("no scenarios were scanned, so every check below would be meaningless")
                .hasSizeGreaterThan(50);
    }

    @Test
    @DisplayName("every tag expression printed in the docs selects at least one scenario")
    void documentedTagFiltersSelectSomething() {
        List<Documented> filters = DocumentedCommands.tagExpressionsIn(DOCS);

        assertThat(filters).as("the docs show no tag filters at all, which is itself suspect")
                .isNotEmpty();

        for (Documented filter : filters) {
            assertThat(DocumentedCommands.selectionSize(filter.value(), SCENARIOS))
                    .as("%s selects no scenario. A documented command that runs nothing "
                            + "still reports success, which reads as a passing suite.", filter)
                    .isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("every run-tests.sh profile named in the docs exists in the script")
    void documentedProfilesExist() {
        Map<String, String> implemented = DocumentedCommands.profilesInScript(SCRIPT);

        assertThat(implemented).as("no profiles parsed out of %s", SCRIPT).isNotEmpty();

        for (Documented call : DocumentedCommands.profileCallsIn(DOCS)) {
            assertThat(implemented)
                    .as("%s names a profile the script does not implement; it implements %s",
                            call, implemented.keySet())
                    .containsKey(call.value());
        }
    }

    @Test
    @DisplayName("every run-tests.sh profile selects at least one scenario")
    void everyProfileSelectsSomething() {
        // This is the check that would have caught `run-tests.sh smoke` running
        // zero scenarios and exiting 0, which it did for the life of the script
        // until work package A.
        Map<String, String> profiles = DocumentedCommands.profilesInScript(SCRIPT);

        profiles.forEach((name, expression) -> {
            if (expression.isBlank()) {
                return;
            }
            assertThat(DocumentedCommands.selectionSize(expression, SCENARIOS))
                    .as("run-tests.sh %s filters on '%s' and selects no scenario", name, expression)
                    .isGreaterThan(0);
        });
    }

    @Test
    @DisplayName("every target/ artefact the docs promise is produced by something in the build")
    void promisedArtefactsAreProduced() {
        // Where the build declares what it WRITES. junit-platform.properties is
        // included because the Cucumber plugins that emit the HTML and XML
        // reports are configured there rather than in the pom.
        //
        // The CI workflow is deliberately excluded. It only collects files, and
        // an upload list naming an artefact says nothing about whether anything
        // still produces it - target/scenario-coverage.md was uploaded by CI for
        // months while CoverageReporter was bound to no phase at all.
        String build = read(Path.of("pom.xml"))
                + read(Path.of("src/test/resources/junit-platform.properties"))
                + read(SCRIPT);

        String documentation = DOCS.stream().map(DocumentationTest::read)
                .reduce("", (a, b) -> a + "\n" + b);

        for (String artefact : DocumentedCommands.artefactPathsIn(DOCS)) {
            String basename = artefact.substring(artefact.lastIndexOf('/') + 1);
            boolean producedByBuild = build.contains(artefact) || build.contains(basename)
                    || writtenByCodeThatRuns(basename, build);
            // Some artefacts are produced by a manual step rather than the build -
            // the Allure HTML report needs the Allure CLI. That is fine as long as
            // the docs show the command, which is what "-o <path>" here means.
            boolean commandShown = documentation.contains("-o " + artefact)
                    || documentation.contains("--output " + artefact)
                    || documentation.contains("> " + artefact);

            // Deliberately not "the file exists": output left over from an earlier
            // run satisfies that trivially, so unbinding the generator that wrote
            // it would leave this check green - which is the exact failure this
            // test exists to catch.
            assertThat(producedByBuild || commandShown)
                    .as("the docs promise %s, but nothing in pom.xml, junit-platform.properties "
                            + "or run-tests.sh produces it, and no documented command writes it. "
                            + "Being uploaded by CI does not count: scenario-coverage.md was "
                            + "collected by the workflow for months while CoverageReporter was "
                            + "bound to no phase at all.", artefact)
                    .isTrue();
        }
    }

    /**
     * True when some class writes this file AND that class actually runs.
     *
     * <p>Both halves are needed. "A class writes it" alone is not production:
     * {@code CoverageReporter} writes target/scenario-coverage.md and produced
     * nothing for months because it was bound to no phase. "The build names it"
     * alone misses the opposite case: target/environment-exclusions.txt is
     * written by {@code Hooks} during the run and appears in no build file.
     *
     * <p>A class runs if it participates in the test run - a Cucumber hook, a
     * step definition, a JUnit test - or if the build invokes it by name.
     * Matched on the basename because a path is often assembled from segments,
     * as {@code Path.of("target", "...")} is, so the joined string appears
     * nowhere in the source.
     */
    private static boolean writtenByCodeThatRuns(String basename, String build) {
        try (java.util.stream.Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                // A test that names an artefact in order to assert on it is not
                // what produces it. Counting CoverageReporterTest as a producer
                // made this check pass with CoverageReporter bound to nothing,
                // which is the exact bug it exists to catch.
                if (file.getFileName().toString().endsWith("Test.java")) {
                    continue;
                }
                String source = read(file);
                if (!source.contains(basename)) {
                    continue;
                }
                boolean runsWithTheSuite = source.contains("@Before") || source.contains("@After")
                        || source.contains("@Test");
                String className = file.getFileName().toString().replace(".java", "");
                if (runsWithTheSuite || build.contains(className)) {
                    return true;
                }
            }
            return false;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not scan src/test/java", e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not read " + path, e);
        }
    }
}
