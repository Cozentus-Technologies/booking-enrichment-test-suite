package com.cozentus.enrichment.tests.data;

import com.cozentus.enrichment.tests.model.Outcome;
import java.util.List;

/**
 * One row of {@code city-cases.csv} — the suite's single source of truth.
 *
 * <p>Two sentinels carry what CSV cannot express directly:
 * {@code <ABSENT>} means the field is omitted from the JSON entirely, and a bare
 * {@code null} means a JSON null literal rather than the four-character string.
 */
public record CityCase(String caseId,
                       String input,
                       String expected,
                       Outcome outcome,
                       String reason,
                       List<String> tags,
                       String note) {

    public static final String ABSENT = "<ABSENT>";
    public static final String NULL_LITERAL = "null";

    /** True when the field should not appear in the published JSON at all. */
    public boolean fieldIsAbsent() {
        return ABSENT.equals(input);
    }

    /** True when the field should be published as a JSON null. */
    public boolean fieldIsNull() {
        return NULL_LITERAL.equals(input);
    }

    /** The value to publish, or null for both sentinel forms. */
    public String publishedValue() {
        return fieldIsAbsent() || fieldIsNull() ? null : input;
    }

    public boolean isEnriched() {
        return outcome == Outcome.ENRICHED;
    }
}
