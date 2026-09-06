package com.cozentus.enrichment.tests.report;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The requirement side of the traceability matrix. Class B in spec 8.6: a run
 * records which cases executed, never which requirement they were meant to
 * verify, so that half of the mapping has to be committed alongside the suite.
 */
public final class Requirements {

    public record Requirement(String id, String description, List<String> cases, String criticality) {
    }

    private final List<Requirement> requirements;

    public Requirements(List<Requirement> requirements) {
        this.requirements = List.copyOf(requirements);
    }

    public static Requirements empty() {
        return new Requirements(List.of());
    }

    public List<Requirement> all() {
        return requirements;
    }

    public int total() {
        return requirements.size();
    }

    /** At least one mapped case executed. */
    public long covered(List<RunResult> results) {
        Set<String> executed = executedIds(results);
        return requirements.stream()
                .filter(r -> r.cases().stream().anyMatch(executed::contains))
                .count();
    }

    /** Every mapped case executed and passed. */
    public long verified(List<RunResult> results) {
        Map<String, RunResult> byId = byId(results);
        return requirements.stream()
                .filter(r -> !r.cases().isEmpty())
                .filter(r -> r.cases().stream()
                        .allMatch(id -> byId.containsKey(id) && byId.get(id).passed()))
                .count();
    }

    /** Per requirement: executed count, passed count, and a verdict for the matrix. */
    public String verdict(Requirement requirement, List<RunResult> results) {
        Map<String, RunResult> byId = byId(results);
        long executed = requirement.cases().stream().filter(byId::containsKey)
                .filter(id -> byId.get(id).executed()).count();
        long passed = requirement.cases().stream().filter(byId::containsKey)
                .filter(id -> byId.get(id).passed()).count();

        if (executed == 0) {
            return "Not verified";
        }
        if (passed == requirement.cases().size()) {
            return "Verified";
        }
        return passed == executed ? "Partially verified" : "Failed";
    }

    private static Set<String> executedIds(List<RunResult> results) {
        return results.stream().filter(RunResult::executed).map(RunResult::caseId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Map<String, RunResult> byId(List<RunResult> results) {
        return results.stream().collect(java.util.stream.Collectors.toMap(
                RunResult::caseId, r -> r, (a, b) -> a));
    }
}
