package com.cozentus.enrichment.tests.report;

import java.util.List;
import java.util.Map;

/**
 * One executed scenario, read from {@code cucumber.json}.
 *
 * <p>Class A in spec 8.6: everything here is derivable from the suite's own
 * output with no external input.
 */
public record RunResult(String caseId,
                        String title,
                        String module,
                        String type,
                        String priority,
                        String profile,
                        Status status,
                        double durationSeconds,
                        String failingStep,
                        String failureMessage,
                        Map<String, String> attachments) {

    public enum Status {
        PASS, FAIL, SKIP, BLOCKED;

        /** The token the report template expects. */
        public String token() {
            return switch (this) {
                case PASS -> "pass";
                case FAIL -> "fail";
                // The template filters on "skipped" in thirteen places. While
                // this said "skip", nothing ever matched and the report could
                // not show a planned case that did not run.
                case SKIP -> "skipped";
                case BLOCKED -> "blocked";
            };
        }
    }

    public boolean executed() {
        return status == Status.PASS || status == Status.FAIL;
    }

    public boolean passed() {
        return status == Status.PASS;
    }

    /** The same case with its module restated in the catalogue's vocabulary. */
    public RunResult inModule(String canonicalModule) {
        return new RunResult(caseId, title, canonicalModule, type, priority, profile,
                status, durationSeconds, failingStep, failureMessage, attachments);
    }

    /** Tags carry the axes; a missing one is reported rather than guessed. */
    public static String axis(List<String> tags, List<String> allowed, String fallback) {
        return tags.stream()
                .map(tag -> tag.startsWith("@") ? tag.substring(1) : tag)
                .filter(allowed::contains)
                .findFirst()
                .map(RunResult::titleCase)
                .orElse(fallback);
    }

    static String titleCase(String value) {
        String spaced = value.replace('-', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
