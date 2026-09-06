package com.cozentus.enrichment.stub;

import com.cozentus.enrichment.stub.Fixture.Cities;
import com.cozentus.enrichment.stub.Fixture.Route;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;

/**
 * Writes the bulk sample and its oracle for a run against the stub.
 *
 * <p>The volume scenarios read both files from the service repository, which is
 * exactly what the mutation check must be able to do without: the whole point
 * of the stub is that the check runs with the service absent from disk. So the
 * sample is generated here and pointed at with {@code -Dsample.data.path}.
 *
 * <p>The oracle is not written by hand. Every row is labelled by asking the
 * baseline fixture what it will do with that row, so the oracle and the stub
 * cannot disagree - and if the baseline fixture changes, the oracle changes
 * with it rather than silently going stale.
 */
public final class SampleData {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SampleData() {
    }

    /**
     * Inputs chosen so that both output topics are populated and more than one
     * flag reason appears: TC-62 compares per-reason totals, and a sample whose
     * every failure had the same cause would not exercise it.
     */
    private static final List<String> MATCHABLE =
            List.of("Mumbai", "MUMBAI", "New Delhi", "Mumbi", "now delhi", "Bangalor", "Pne", "Chennai");
    private static final List<String> UNMATCHABLE =
            List.of("Warsaw", "Lisbon", "Mumbaiii", "Pn");

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args.length > 0 ? args[0] : "target/stub-sample");
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 42L;

        Files.createDirectories(directory);
        Path data = directory.resolve("bookings-sample.jsonl");
        Path oracle = Path.of(data + ".expected.json");

        List<String> rows = rows(count, seed);
        Files.write(data, rows, StandardCharsets.UTF_8);
        Files.writeString(oracle, label(rows), StandardCharsets.UTF_8);

        System.out.printf("Wrote %d sample rows to %s and its oracle to %s%n",
                rows.size(), data, oracle);
    }

    private static List<String> rows(int count, long seed) {
        Random random = new Random(seed);
        List<String> rows = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            ObjectNode booking = MAPPER.createObjectNode();
            booking.put("bookingId", "BKG-SAMPLE-%05d".formatted(i));
            booking.put("shipper", "ABC Logistics");
            booking.put("mode", "ROAD");
            booking.put("requestedDate", "2026-09-05");

            // A fixed cycle rather than a random draw for the outcome, so the
            // split between the four cases is exact at any count and the oracle
            // is reproducible without reading the data back.
            switch (i % 20) {
                case 0, 1, 2 -> {
                    booking.put("origin", pick(UNMATCHABLE, random));
                    booking.put("destination", pick(MATCHABLE, random));
                }
                case 3, 4 -> {
                    booking.put("origin", pick(MATCHABLE, random));
                    booking.put("destination", pick(UNMATCHABLE, random));
                }
                case 5 -> {
                    booking.put("origin", "");
                    booking.put("destination", pick(MATCHABLE, random));
                }
                default -> {
                    booking.put("origin", pick(MATCHABLE, random));
                    booking.put("destination", pick(MATCHABLE, random));
                }
            }
            rows.add(booking.toString());
        }
        return rows;
    }

    private static String pick(List<String> pool, Random random) {
        return pool.get(random.nextInt(pool.size()));
    }

    /** Derives the oracle from the baseline fixture rather than restating it. */
    private static String label(List<String> rows) throws IOException {
        Fixture baseline = Fixture.load("baseline");
        int enriched = 0;
        int flagged = 0;
        TreeMap<String, Integer> byReason = new TreeMap<>();

        for (String row : rows) {
            var booking = MAPPER.readTree(row);
            List<String> reasons = new ArrayList<>();
            for (String field : List.of("origin", "destination")) {
                var rule = baseline.decide(booking.path(field).asText(null), Cities.DEFAULT);
                if (rule.thenTopic() == Route.FLAGGED) {
                    reasons.add(rule.thenReason() + "_" + field.toUpperCase(Locale.ROOT) + "_CITY");
                }
            }
            if (reasons.isEmpty()) {
                enriched++;
            } else {
                flagged++;
                reasons.forEach(reason -> byReason.merge(reason, 1, Integer::sum));
            }
        }

        ObjectNode oracle = MAPPER.createObjectNode();
        ObjectNode totals = oracle.putObject("totals");
        totals.put("enriched", enriched);
        totals.put("flagged", flagged);
        ObjectNode reasonTotals = totals.putObject("byReason");
        byReason.forEach(reasonTotals::put);
        return oracle.toPrettyString();
    }
}
