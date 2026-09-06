package com.cozentus.enrichment.tests.model;

/**
 * Which route a value took to reach its canonical form.
 *
 * <p>B-5. Asserting only that the output city is correct cannot tell the exact
 * path from the fuzzy one: {@code "  New   Delhi "} and {@code "now delhi"} both
 * come out as {@code New Delhi}, but the first must be an exact match after
 * normalisation and the second a correction. If whitespace collapsing broke, the
 * first would quietly start taking the fuzzy path and still produce the right
 * city, so the city assertion alone would stay green.
 *
 * <p>The distinction is drawn as {@code exactly 1.0} against {@code below 1.0},
 * never as a comparison with the confidence floor. Encoding the floor would
 * copy the service's constant into the suite, and the two would then agree by
 * construction rather than by test.
 */
public enum ConfidencePath {

    /** Resolved without fuzzy matching: confidence is exactly 1.0. */
    EXACT,

    /** Corrected by fuzzy matching: confidence is below 1.0. */
    FUZZY,

    /** No confidence to assert, because the booking was flagged. */
    NONE;

    public static ConfidencePath parse(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? NONE : valueOf(trimmed.toUpperCase(java.util.Locale.ROOT));
    }

    /** The Gherkin phrasing this path asserts. */
    public String phrase() {
        return switch (this) {
            case EXACT -> "exactly 1.0";
            case FUZZY -> "below 1.0";
            case NONE -> throw new IllegalStateException("a flagged case has no confidence");
        };
    }
}
