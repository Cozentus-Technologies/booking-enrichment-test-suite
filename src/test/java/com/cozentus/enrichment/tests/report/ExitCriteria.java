package com.cozentus.enrichment.tests.report;

import java.util.List;

/**
 * Gate targets agreed before execution, evaluated against computed metrics.
 *
 * <p>Adding a criterion is a configuration change, not a code change (spec 8.7):
 * the criterion names a metric from 8.9 and this class looks it up.
 */
public final class ExitCriteria {

    public record Criterion(String description, String metric, String operator,
                            double target, boolean blocking) {
    }

    /** A criterion with its outcome, in the shape the report template expects. */
    public record Evaluated(String description, String target, String actual,
                            boolean met, boolean blocking) {
    }

    public enum Recommendation {
        PROCEED("Proceed"),
        PROCEED_WITH_CONDITIONS("Proceed with conditions"),
        HOLD("Hold release");

        private final String label;

        Recommendation(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The token the template maps to its own wording. */
        public String token() {
            return switch (this) {
                case PROCEED -> "go";
                case PROCEED_WITH_CONDITIONS -> "hold";
                case HOLD -> "stop";
            };
        }
    }

    private ExitCriteria() {
    }

    public static List<Evaluated> evaluate(List<Criterion> criteria, Metrics metrics) {
        return criteria.stream().map(criterion -> {
            Metrics.Metric metric = metrics.metric(criterion.metric());
            double actual = metric == null ? Double.NaN : metric.value();
            boolean met = !Double.isNaN(actual) && compare(actual, criterion.operator(), criterion.target());

            // A metric the run could not measure reads "not measured" and
            // counts as unmet. Formatting NaN as a percentage would put a
            // number on a gate nothing had evaluated.
            String actualText = metric == null || !metric.measured()
                    ? "not measured"
                    : "%s · %.1f%%".formatted(metric.detail(), actual);
            String targetText = "%s %.0f%%".formatted(symbol(criterion.operator()), criterion.target());

            return new Evaluated(criterion.description(), targetText, actualText,
                    met, criterion.blocking());
        }).toList();
    }

    /**
     * Spec 8.8. Proceed only when everything is met and no blocking defect is
     * open; hold when a blocking criterion fails or a blocking defect is open;
     * otherwise proceed with conditions, which needs a named owner, a fix date
     * and product owner sign-off.
     */
    public static Recommendation recommend(List<Evaluated> evaluated, Defects defects,
                                           List<RunResult> results) {
        boolean blockingCriterionUnmet = evaluated.stream()
                .anyMatch(criterion -> criterion.blocking() && !criterion.met());
        if (blockingCriterionUnmet || defects.anyBlocking(results)) {
            return Recommendation.HOLD;
        }
        boolean anyUnmet = evaluated.stream().anyMatch(criterion -> !criterion.met());
        if (anyUnmet || defects.anyConditional(results)) {
            return Recommendation.PROCEED_WITH_CONDITIONS;
        }
        return Recommendation.PROCEED;
    }

    static boolean compare(double actual, String operator, double target) {
        return switch (operator == null ? "gte" : operator.toLowerCase()) {
            case "eq" -> Math.abs(actual - target) < 0.0001;
            case "gt" -> actual > target;
            case "lt" -> actual < target;
            case "lte" -> actual <= target;
            case "ne" -> Math.abs(actual - target) >= 0.0001;
            default -> actual >= target - 0.0001;
        };
    }

    static String symbol(String operator) {
        return switch (operator == null ? "gte" : operator.toLowerCase()) {
            case "eq" -> "=";
            case "gt" -> ">";
            case "lt" -> "<";
            case "lte" -> "≤";
            case "ne" -> "≠";
            default -> "≥";
        };
    }
}
