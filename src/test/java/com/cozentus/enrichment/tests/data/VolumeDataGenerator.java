package com.cozentus.enrichment.tests.data;

import com.cozentus.enrichment.tests.model.Outcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds a batch with a known enriched/flagged split.
 *
 * <p>Framed as correctness at scale, not performance: nothing here measures
 * throughput, and no scenario asserts one.
 *
 * <p>The matchable and unmatchable origin pools are drawn from
 * {@code testdata/city-cases.csv} via {@link TestDataLoader} — the same table
 * that drives the behavioural scenarios and the expected-result oracle — so
 * the volume generator cannot drift from what the suite otherwise calls a
 * match or a flag (capability F-09). The two sentinel rows
 * ({@link CityCase#fieldIsAbsent()} / {@link CityCase#fieldIsNull()}) and any
 * row with a blank input are excluded: a bulk row needs an actual publishable
 * value, not a stand-in for "no value".
 */
public final class VolumeDataGenerator {

    private static final List<String> MATCHABLE = loadPool(Outcome.ENRICHED);
    private static final List<String> UNMATCHABLE = loadPool(Outcome.FLAGGED);

    /** A booking with its expected outcome, so the assertion needs no second source. */
    public record Row(String bookingId, String payload, boolean expectedFlagged) {
    }

    private VolumeDataGenerator() {
    }

    /**
     * Builds the pool of publishable input values for the given outcome from
     * {@code city-cases.csv}, skipping both sentinel rows and any blank input.
     *
     * @throws IllegalStateException if no usable row exists for the outcome —
     *         a silent fallback to hardcoded data would recreate exactly the
     *         drift this method exists to remove
     */
    static List<String> loadPool(Outcome outcome) {
        return extractPool(TestDataLoader.load(), outcome);
    }

    /**
     * Pure filtering logic, split out from {@link #loadPool(Outcome)} so it can
     * be exercised directly against fixture rows — including the empty-pool
     * failure — without depending on the shape of the committed CSV.
     */
    static List<String> extractPool(List<CityCase> cases, Outcome outcome) {
        List<String> pool = cases.stream()
                .filter(row -> row.outcome() == outcome)
                .filter(row -> !row.fieldIsAbsent() && !row.fieldIsNull())
                .map(CityCase::input)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (pool.isEmpty()) {
            throw new IllegalStateException(
                    "No usable " + outcome + " rows with a publishable input in "
                            + TestDataLoader.DEFAULT_RESOURCE + ": cannot build the volume data "
                            + (outcome == Outcome.ENRICHED ? "matchable" : "unmatchable") + " pool. "
                            + "Refusing to fall back to hardcoded city names.");
        }
        return pool;
    }

    /**
     * @param total   how many bookings
     * @param invalid how many carry an unmatchable origin
     * @param seed    fixed, so a failure names the same bookings on a rerun
     */
    public static List<Row> generate(int total, int invalid, long seed) {
        if (invalid > total) {
            throw new IllegalArgumentException(
                    "Cannot have " + invalid + " invalid bookings out of " + total);
        }
        Random random = new Random(seed);
        List<Row> rows = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            String bookingId = "BKG-VOL-%05d".formatted(i);
            boolean flagged = i < invalid;
            String origin = flagged
                    ? UNMATCHABLE.get(random.nextInt(UNMATCHABLE.size()))
                    : MATCHABLE.get(random.nextInt(MATCHABLE.size()));
            String destination = MATCHABLE.get(random.nextInt(MATCHABLE.size()));

            rows.add(new Row(bookingId,
                    BookingBuilder.booking(bookingId).origin(origin).destination(destination).build(),
                    flagged));
        }
        return List.copyOf(rows);
    }
}
