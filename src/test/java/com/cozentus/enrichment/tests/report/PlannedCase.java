package com.cozentus.enrichment.tests.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of {@code spec/test-cases.yaml}: a case the cycle planned to run,
 * whether or not it ran.
 *
 * <p>D-4/D-5. The report used to build its case list out of the executed
 * results alone, which made every denominator the numerator: execution
 * completeness was 100% by construction and the critical execution rate, a
 * blocking exit criterion, could not fall below 100 no matter how much of the
 * plan a tag filter had skipped. The plan has to come from somewhere the run
 * cannot influence, and this is it.
 */
public record PlannedCase(String id, String title, String module, String type,
                          String priority, String profile) {

    public static PlannedCase from(Map<String, Object> row) {
        return new PlannedCase(
                text(row, "id"), text(row, "title"), text(row, "module"),
                text(row, "type"), text(row, "priority"), text(row, "profile"));
    }

    /**
     * The degenerate plan: the run is its own plan. Only honest when nothing
     * else is available, and it is what made the rates uninformative.
     */
    public static List<PlannedCase> of(List<RunResult> results) {
        return results.stream()
                .map(r -> new PlannedCase(r.caseId(), r.title(), r.module(), r.type(),
                        r.priority(), r.profile()))
                .toList();
    }

    public static Map<String, PlannedCase> byId(List<PlannedCase> planned) {
        Map<String, PlannedCase> index = new LinkedHashMap<>();
        planned.forEach(c -> index.putIfAbsent(c.id(), c));
        return index;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
