package com.cozentus.enrichment.tests.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExitCriteriaTest {

    private static RunResult passing(String id, String priority) {
        return new RunResult(id, id, "City correction", "Functional", priority, "Fast",
                RunResult.Status.PASS, 1.0, null, null, Map.of());
    }

    private static Metrics metricsWith(List<RunResult> results) {
        return Metrics.compute(results, results.size(), Defects.empty(), Requirements.empty());
    }

    @Test
    @DisplayName("nothing gated and nothing broken is Proceed, not Hold")
    void emptyCriteriaProceed() {
        List<RunResult> results = List.of(passing("TC-01", "Critical"));

        var evaluated = ExitCriteria.evaluate(List.of(), metricsWith(results));
        var recommendation = ExitCriteria.recommend(evaluated, Defects.empty(), results);

        assertThat(recommendation).isEqualTo(ExitCriteria.Recommendation.PROCEED);
        assertThat(recommendation.token()).isEqualTo("go");
    }

    @Test
    @DisplayName("an unmet blocking criterion holds the release")
    void unmetBlockingCriterionHolds() {
        List<RunResult> results = List.of(passing("TC-01", "Critical"));
        var criteria = List.of(new ExitCriteria.Criterion(
                "Requirement coverage", "requirement_coverage", "eq", 100, true));

        var evaluated = ExitCriteria.evaluate(criteria, metricsWith(results));
        assertThat(ExitCriteria.recommend(evaluated, Defects.empty(), results))
                .isEqualTo(ExitCriteria.Recommendation.HOLD);
    }

    @Test
    @DisplayName("an unmet non-blocking criterion proceeds with conditions")
    void unmetNonBlockingProceedsWithConditions() {
        List<RunResult> results = List.of(passing("TC-01", "Critical"));
        var criteria = List.of(new ExitCriteria.Criterion(
                "High priority pass rate", "high_pass_rate", "gte", 95, false));

        var evaluated = ExitCriteria.evaluate(criteria, metricsWith(results));
        assertThat(ExitCriteria.recommend(evaluated, Defects.empty(), results))
                .isEqualTo(ExitCriteria.Recommendation.PROCEED_WITH_CONDITIONS);
    }

    @Test
    @DisplayName("a met criterion proceeds")
    void metCriterionProceeds() {
        List<RunResult> results = List.of(passing("TC-01", "Critical"));
        var criteria = List.of(new ExitCriteria.Criterion(
                "Critical pass rate", "critical_pass_rate", "eq", 100, true));

        var evaluated = ExitCriteria.evaluate(criteria, metricsWith(results));
        assertThat(evaluated).singleElement().extracting(ExitCriteria.Evaluated::met).isEqualTo(true);
        assertThat(ExitCriteria.recommend(evaluated, Defects.empty(), results))
                .isEqualTo(ExitCriteria.Recommendation.PROCEED);
    }

    @Test
    @DisplayName("a metric a criterion names but the run never produced is not silently met")
    void unknownMetricIsNotMet() {
        List<RunResult> results = List.of(passing("TC-01", "Critical"));
        var criteria = List.of(new ExitCriteria.Criterion(
                "Something not computed", "no_such_metric", "gte", 1, false));

        var evaluated = ExitCriteria.evaluate(criteria, metricsWith(results));

        assertThat(evaluated).singleElement().satisfies(c -> {
            assertThat(c.met()).isFalse();
            assertThat(c.actual()).contains("not measured");
        });
    }

    @Test
    @DisplayName("operators compare as documented")
    void operatorsBehave() {
        assertThat(ExitCriteria.compare(100, "eq", 100)).isTrue();
        assertThat(ExitCriteria.compare(99.9, "eq", 100)).isFalse();
        assertThat(ExitCriteria.compare(96, "gte", 95)).isTrue();
        assertThat(ExitCriteria.compare(94, "gte", 95)).isFalse();
        assertThat(ExitCriteria.compare(0, "lte", 0)).isTrue();
    }
}
