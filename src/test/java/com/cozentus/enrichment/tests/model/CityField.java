package com.cozentus.enrichment.tests.model;

/**
 * Which of the two city fields a test case varies.
 *
 * <p>B-1. The table used to vary the origin only, so every rule in the matching
 * contract was proven on one field and merely assumed on the other. A service
 * that enriched the origin correctly and left the destination untouched would
 * have passed. Each case is now stated once per field, and the field not under
 * test is pinned to a canonical value so a failure names one field, not two.
 */
public enum CityField {

    ORIGIN,
    DESTINATION;

    /** The JSON property name, which is also the word the Gherkin steps use. */
    public String lowerName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The other field, held at a known-good value while this one varies. */
    public CityField other() {
        return this == ORIGIN ? DESTINATION : ORIGIN;
    }

    /** The CSV abbreviates the reason; the service emits it with this suffix. */
    public String reasonSuffix() {
        return "_" + name() + "_CITY";
    }
}
