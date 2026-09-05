package com.cozentus.enrichment.tests.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds a batch with a known enriched/flagged split.
 *
 * <p>Framed as correctness at scale, not performance: nothing here measures
 * throughput, and no scenario asserts one.
 */
public final class VolumeDataGenerator {

    private static final List<String> MATCHABLE =
            List.of("Mumbai", "New Delhi", "Bangalore", "Chennai", "Kolkata", "Pune");
    private static final List<String> UNMATCHABLE =
            List.of("Warsaw", "Lisbon", "Dubai", "Singapore", "Berlin");

    /** A booking with its expected outcome, so the assertion needs no second source. */
    public record Row(String bookingId, String payload, boolean expectedFlagged) {
    }

    private VolumeDataGenerator() {
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
