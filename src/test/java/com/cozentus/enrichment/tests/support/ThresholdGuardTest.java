package com.cozentus.enrichment.tests.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ThresholdGuardTest {

    @Test
    @DisplayName("the committed suite states no confidence floor anywhere")
    void theSuiteIsClean() {
        assertThat(ThresholdGuard.scan(Path.of("src/test")))
                .as("a suite that reads the service's threshold agrees with it by construction")
                .isEmpty();
    }

    @Test
    @DisplayName("the floor is caught however it is written")
    void theFloorIsCaught() {
        assertThat(ThresholdGuard.statesTheFloor("assertThat(c).isGreaterThanOrEqualTo(0.85)")).isTrue();
        assertThat(ThresholdGuard.statesTheFloor("// the 0.85 floor applies here")).isTrue();
        assertThat(ThresholdGuard.statesTheFloor("assertThat(c).isGreaterThan(0.9d)")).isTrue();
    }

    @Test
    @DisplayName("1.0 is allowed, because it defines an exact match rather than a threshold")
    void exactMatchIsAllowed() {
        assertThat(ThresholdGuard.statesTheFloor("assertThat(c).isEqualTo(1.0d)")).isFalse();
        assertThat(ThresholdGuard.statesTheFloor("assertThat(c).isLessThan(1.0d)")).isFalse();
    }

    @Test
    @DisplayName("a measured value recorded in prose is not a violation")
    void observationsAreNotAssertions() {
        // B-3 requires boundary rows to record what was observed. Recording
        // 0.9555... documents a measurement; it does not make any assertion
        // depend on the number.
        assertThat(ThresholdGuard.statesTheFloor(
                "observed confidence 0.9555555555555556, above the floor")).isFalse();
    }
}
