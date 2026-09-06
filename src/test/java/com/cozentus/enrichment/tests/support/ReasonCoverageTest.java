package com.cozentus.enrichment.tests.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * B-4. A flag reason the service can emit and no scenario asserts is a hole the
 * suite cannot see. Three of the seven were in that state.
 */
class ReasonCoverageTest {

    @Test
    @DisplayName("every reason the contract allows is asserted by at least one scenario")
    void everyReasonIsCovered() {
        ReasonCoverage.Report report = ReasonCoverage.run();

        assertThat(report.uncovered())
                .as("reasons in the schema that no scenario asserts:%n%s", report.describe())
                .isEmpty();
    }

    @Test
    @DisplayName("the reasons are read from the published schema, not a list kept in the suite")
    void reasonsComeFromTheSchema() {
        // A hand-maintained copy would drift: a reason added to the contract
        // would not appear here, so the check would keep passing while the gap
        // it exists to catch reopened.
        assertThat(ReasonCoverage.reasonsInContract())
                .contains("UNMATCHED_ORIGIN_CITY", "AMBIGUOUS_ORIGIN_CITY",
                        "MISSING_ORIGIN_CITY", "MALFORMED_MESSAGE")
                .hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("a reason present in the schema but absent from the text is reported uncovered")
    void anAbsentReasonIsReported() {
        Set<String> uncovered = ReasonCoverage.uncoveredIn(
                Set.of("UNMATCHED_ORIGIN_CITY", "AMBIGUOUS_ORIGIN_CITY"),
                List.of("And the reason is \"UNMATCHED_ORIGIN_CITY\""));

        assertThat(uncovered).containsExactly("AMBIGUOUS_ORIGIN_CITY");
    }

    @Test
    @DisplayName("the check fails loudly rather than silently passing when it finds no scenarios")
    void noScenariosAtAllIsAFailure() {
        assertThatThrownBy(() -> ReasonCoverage.uncoveredIn(Set.of("X"), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no feature text");
    }
}
