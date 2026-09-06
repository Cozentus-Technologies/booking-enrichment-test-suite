package com.cozentus.enrichment.tests.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The enforcer has to be able to fail, or it is decoration. Each case below
 * removes exactly one required tag and asserts the omission is caught.
 */
class TagDisciplineTest {

    private static List<String> feature(String tagLine) {
        return List.of(
                "Feature: A feature",
                "",
                "  " + tagLine,
                "  Scenario: Something happens",
                "    Given a booking",
                "    When it is published",
                "    Then it lands");
    }

    @Test
    @DisplayName("a fully tagged scenario passes")
    void fullyTaggedPasses() {
        assertThat(TagDiscipline.checkLines("f.feature",
                feature("@functional @critical @routing @fast @TC-01"))).isEmpty();
    }

    @Test
    @DisplayName("a missing type tag is caught")
    void missingTypeIsCaught() {
        assertThat(TagDiscipline.checkLines("f.feature",
                feature("@critical @routing @TC-01")))
                .singleElement().extracting(TagDiscipline.Violation::problem)
                .asString().contains("type tag");
    }

    @Test
    @DisplayName("two type tags are caught, because exactly one is required")
    void twoTypeTagsAreCaught() {
        assertThat(TagDiscipline.checkLines("f.feature",
                feature("@functional @resilience @critical @routing @TC-01")))
                .singleElement().extracting(TagDiscipline.Violation::problem)
                .asString().contains("exactly one type tag, has 2");
    }

    @Test
    @DisplayName("a missing priority tag is caught")
    void missingPriorityIsCaught() {
        assertThat(TagDiscipline.checkLines("f.feature",
                feature("@functional @routing @TC-01")))
                .singleElement().extracting(TagDiscipline.Violation::problem)
                .asString().contains("priority tag");
    }

    @Test
    @DisplayName("a missing feature-area tag is caught")
    void missingAreaIsCaught() {
        assertThat(TagDiscipline.checkLines("f.feature",
                feature("@functional @critical @TC-01")))
                .singleElement().extracting(TagDiscipline.Violation::problem)
                .asString().contains("feature-area tag");
    }

    @Test
    @DisplayName("a missing traceability tag is caught - the one that breaks the report")
    void missingTraceabilityIsCaught() {
        assertThat(TagDiscipline.checkLines("f.feature",
                feature("@functional @critical @routing")))
                .singleElement().extracting(TagDiscipline.Violation::problem)
                .asString().contains("@TC-nn");
    }

    @Test
    @DisplayName("two traceability tags are caught, since a case cannot trace to two ids")
    void twoTraceabilityTagsAreCaught() {
        assertThat(TagDiscipline.checkLines("f.feature",
                feature("@functional @critical @routing @TC-01 @TC-02")))
                .singleElement().extracting(TagDiscipline.Violation::problem)
                .asString().contains("has 2");
    }

    @Test
    @DisplayName("a Scenario Outline is held to the same rules")
    void outlinesAreChecked() {
        List<String> lines = List.of(
                "Feature: A feature",
                "  @functional @critical @routing",
                "  Scenario Outline: Something happens",
                "    Given <a>");

        assertThat(TagDiscipline.checkLines("f.feature", lines))
                .singleElement().extracting(TagDiscipline.Violation::problem)
                .asString().contains("@TC-nn");
    }

    /**
     * E-2. The environment axis arrived after the other four, and the way that
     * goes wrong is that the new tag lands inside one of the exclusive axes and
     * every scenario carrying it becomes a build failure.
     */
    @Test
    @DisplayName("an environment tag is an extra axis, not an extra type or priority")
    void anEnvironmentTagDoesNotBreakAFullyTaggedScenario() {
        assertThat(TagDiscipline.checkLines("f.feature",
                feature("@functional @critical @routing @slow @requires-service-config @TC-01")))
                .isEmpty();
    }

    @Test
    @DisplayName("an environment tag the suite does not act on is caught")
    void anInventedEnvironmentTagIsCaught() {
        assertThat(TagDiscipline.checkLines("f.feature",
                feature("@functional @critical @routing @requires-extended-cities @TC-01")))
                .singleElement().extracting(TagDiscipline.Violation::problem)
                .asString().contains("@requires-extended-cities", "never excluded");
    }

    @Test
    @DisplayName("every committed scenario, generated one included, is fully tagged")
    void theRealSuiteIsClean() {
        List<TagDiscipline.Violation> violations =
                TagDiscipline.check(Path.of("target/test-classes/features"));

        assertThat(violations)
                .as("run mvn process-test-classes first so the generated feature is present")
                .isEmpty();
    }
}
