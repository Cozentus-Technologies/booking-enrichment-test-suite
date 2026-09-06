package com.cozentus.enrichment.tests.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlannedSetGuardTest {

    @Test
    @DisplayName("the committed catalogue matches the committed features")
    void catalogueIsAligned() {
        PlannedSetGuard.Report report = PlannedSetGuard.run(Path.of("spec/test-cases.yaml"));

        assertThat(report.inFeatures()).isNotEmpty();
        assertThat(report.describe()).doesNotContain("disagree");
        assertThat(report.aligned()).isTrue();
    }

    @Test
    @DisplayName("a scenario the catalogue does not know about is named, in the right direction")
    void driftIsNamedInBothDirections() {
        PlannedSetGuard.Report drifted = new PlannedSetGuard.Report(
                Set.of("TC-01", "TC-02", "TC-99"), Set.of("TC-01", "TC-77"));

        assertThat(drifted.aligned()).isFalse();
        assertThat(drifted.uncatalogued()).containsExactly("TC-02", "TC-99");
        assertThat(drifted.unimplemented()).containsExactly("TC-77");
        assertThat(drifted.describe())
                .contains("absent from the catalogue")
                .contains("absent from the features");
    }

    @Test
    @DisplayName("a catalogue file that is not there is not silently aligned")
    void missingCatalogueIsDrift(@TempDir Path dir) throws IOException {
        assertThat(PlannedSetGuard.inCatalogue(dir.resolve("nothing.yaml"))).isEmpty();

        Files.writeString(dir.resolve("test-cases.yaml"), """
                - id: TC-01
                  title: something
                - id: TC-02
                  title: something else
                """);
        assertThat(PlannedSetGuard.inCatalogue(dir.resolve("test-cases.yaml")))
                .containsExactly("TC-01", "TC-02");
    }
}
