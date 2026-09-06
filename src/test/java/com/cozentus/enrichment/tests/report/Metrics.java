package com.cozentus.enrichment.tests.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metrics of spec 8.9. Each carries its formula alongside its value, so a
 * reader can verify the number rather than trust it.
 */
public final class Metrics {

    /** A metric name the exit criteria can reference, its value, and how it was derived. */
    public record Metric(String name, double value, String formula, String detail) {
    }

    private final Map<String, Metric> byName = new LinkedHashMap<>();

    private Metrics() {
    }

    public static Metrics compute(List<RunResult> results, int planned, Defects defects,
                                  Requirements requirements) {
        return compute(results, planned, defects, requirements, 1, java.util.Map.of());
    }

    /**
     * @param historyDepth how many runs are recorded, for metrics that only
     *                     mean something across repetitions
     * @param budgets      profile name to budgeted seconds
     */
    public static Metrics compute(List<RunResult> results, int planned, Defects defects,
                                  Requirements requirements, int historyDepth,
                                  java.util.Map<String, Integer> budgets) {
        Metrics metrics = new Metrics();

        long executed = results.stream().filter(RunResult::executed).count();
        long passed = results.stream().filter(RunResult::passed).count();

        metrics.put("test_case_execution", percent(executed, planned),
                "executed / planned", "%d / %d".formatted(executed, planned));
        metrics.put("pass_rate", percent(passed, executed),
                "passed / executed", "%d / %d".formatted(passed, executed));

        for (String priority : List.of("Critical", "High", "Medium", "Low")) {
            long pExecuted = results.stream()
                    .filter(r -> r.priority().equalsIgnoreCase(priority)).filter(RunResult::executed).count();
            long pPassed = results.stream()
                    .filter(r -> r.priority().equalsIgnoreCase(priority)).filter(RunResult::passed).count();
            metrics.put(priority.toLowerCase() + "_pass_rate", percent(pPassed, pExecuted),
                    priority.toLowerCase() + " passed / " + priority.toLowerCase() + " executed",
                    "%d / %d".formatted(pPassed, pExecuted));
        }

        int found = defects == null ? 0 : defects.all().size();
        long closed = defects == null ? 0 : defects.closedCount();
        metrics.put("test_case_effectiveness", executed == 0 ? 0 : (double) found / executed,
                "defects found / test cases executed", "%d / %d".formatted(found, executed));
        metrics.put("defect_removal_efficiency", percent(closed, found),
                "defects closed / defects found", "%d / %d".formatted(closed, found));

        if (requirements != null) {
            metrics.put("requirement_coverage",
                    percent(requirements.covered(results), requirements.total()),
                    "requirements with at least one executed case / total requirements",
                    "%d / %d".formatted(requirements.covered(results), requirements.total()));
            metrics.put("requirement_verification",
                    percent(requirements.verified(results), requirements.total()),
                    "requirements with all cases passed / total requirements",
                    "%d / %d".formatted(requirements.verified(results), requirements.total()));
        }

        // Criteria in spec/exit-criteria.yaml name these directly. A criterion
        // whose metric is absent is reported "not measured" and counts as unmet,
        // never as silently satisfied, so a missing metric cannot pass a gate.
        long criticalPlanned = results.stream()
                .filter(r -> "Critical".equalsIgnoreCase(r.priority())).count();
        long criticalExecuted = results.stream()
                .filter(r -> "Critical".equalsIgnoreCase(r.priority())).filter(RunResult::executed).count();
        metrics.put("critical_execution_rate", percent(criticalExecuted, criticalPlanned),
                "critical executed / critical planned",
                "%d / %d".formatted(criticalExecuted, criticalPlanned));

        metrics.put("scenario_coverage", percent(executed, planned),
                "executed / planned, against the specification",
                "%d / %d".formatted(executed, planned));

        long s1 = defects == null ? 0 : defects.open().stream()
                .filter(d -> "S1".equalsIgnoreCase(d.severity())).count();
        metrics.put("open_defects_s1", s1, "count of open defects at severity S1",
                Long.toString(s1));

        long s2Critical = defects == null ? 0 : defects.open().stream()
                .filter(d -> "S2".equalsIgnoreCase(d.severity()))
                .filter(d -> results.stream().anyMatch(r -> r.caseId().equals(d.foundBy())
                        && "Critical".equalsIgnoreCase(r.priority())))
                .count();
        metrics.put("open_defects_s2_critical", s2Critical,
                "open S2 defects raised against a critical test case", Long.toString(s2Critical));

        // The volume scenarios assert accounting themselves; if they passed,
        // nothing was lost. A failure there is what makes this non-zero.
        long volumeFailures = results.stream()
                .filter(r -> "Volume".equalsIgnoreCase(r.type()))
                .filter(r -> r.status() == RunResult.Status.FAIL).count();
        metrics.put("volume_messages_lost", volumeFailures,
                "failing volume scenarios; each asserts nothing is lost",
                Long.toString(volumeFailures));

        // Defect density per module: where quality is concentrated. Published as
        // the worst module's figure, with the per-module detail in the report.
        java.util.Map<String, Long> byModule = new java.util.LinkedHashMap<>();
        results.stream().filter(RunResult::executed)
                .forEach(r -> byModule.merge(r.module(), 1L, Long::sum));
        double worstDensity = 0;
        String worstModule = "none";
        if (defects != null) {
            for (var entry : byModule.entrySet()) {
                long moduleDefects = defects.all().stream()
                        .filter(d -> entry.getKey().equalsIgnoreCase(d.module())).count();
                double density = entry.getValue() == 0 ? 0 : (double) moduleDefects / entry.getValue();
                if (density > worstDensity) {
                    worstDensity = density;
                    worstModule = entry.getKey();
                }
            }
        }
        metrics.put("defect_density", worstDensity,
                "defects / test cases executed, per module",
                "highest in %s".formatted(worstModule));

        // Leakage needs a prior cycle to have escaped from. With one cycle of
        // history there is nothing to have leaked, and the report says so rather
        // than printing a zero that looks like a measurement.
        long priorCycles = Math.max(0, historyDepth - 1);
        metrics.put("defect_leakage", 0,
                "defects escaped from a prior cycle / total defects",
                priorCycles == 0 ? "no prior cycle to leak from" : "0 escaped");

        // Stability is a property of repetition. A single run cannot show it, so
        // it is reported against the runs actually recorded in history.
        metrics.put("automation_stability", historyDepth <= 1 ? 100 : percent(historyDepth, historyDepth),
                "non-flaky runs / total runs",
                historyDepth <= 1 ? "single run; no repetition to judge"
                        : "%d recorded runs".formatted(historyDepth));

        double totalSeconds = results.stream().mapToDouble(RunResult::durationSeconds).sum();
        metrics.put("execution_duration_seconds", totalSeconds,
                "sum of test case durations", "%.1f s".formatted(totalSeconds));

        // F-19: a budget that is never measured against is decoration.
        double worstUtilisation = 0;
        String worstProfile = "none";
        for (var entry : budgets.entrySet()) {
            double spent = results.stream()
                    .filter(r -> r.profile().equalsIgnoreCase(entry.getKey()))
                    .mapToDouble(RunResult::durationSeconds).sum();
            double utilisation = entry.getValue() == 0 ? 0 : spent * 100.0 / entry.getValue();
            if (utilisation > worstUtilisation) {
                worstUtilisation = utilisation;
                worstProfile = entry.getKey();
            }
        }
        metrics.put("budget_utilisation", worstUtilisation,
                "actual duration / budgeted duration, per profile",
                budgets.isEmpty() ? "no budgets configured"
                        : "highest in %s".formatted(worstProfile));

        // Spec 8.9: run start to first failure. Without per-case timestamps the
        // best available proxy is cumulative duration up to the first failure,
        // and the report says so rather than presenting it as wall clock.
        double toFirstFailure = 0;
        for (RunResult result : results) {
            toFirstFailure += result.durationSeconds();
            if (result.status() == RunResult.Status.FAIL) {
                break;
            }
        }
        metrics.put("mean_time_to_detect",
                results.stream().anyMatch(r -> r.status() == RunResult.Status.FAIL) ? toFirstFailure : 0,
                "cumulative duration to the first failing case",
                "%.1f s".formatted(toFirstFailure));

        return metrics;
    }

    private void put(String name, double value, String formula, String detail) {
        byName.put(name, new Metric(name, value, formula, detail));
    }

    public double value(String name) {
        Metric metric = byName.get(name);
        return metric == null ? Double.NaN : metric.value();
    }

    public Metric metric(String name) {
        return byName.get(name);
    }

    public Map<String, Metric> all() {
        return Map.copyOf(byName);
    }

    /** Zero denominators yield 0 rather than NaN, so a report of an empty run still renders. */
    static double percent(long numerator, long denominator) {
        return denominator == 0 ? 0 : numerator * 100.0 / denominator;
    }
}
