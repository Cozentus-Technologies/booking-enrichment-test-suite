package com.cozentus.enrichment.tests.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The recorded runs in {@code reports/history.jsonl}.
 *
 * <p>D-6. Stability and leakage are properties of repetition: neither can be
 * read off a single run, and both used to be published anyway as a constant
 * (100 and 0) that no input could move. They are computed here from the
 * recorded per-case outcomes, and reported as not measured while there is
 * nothing to compare against - which is a different statement from zero.
 */
public final class RunHistory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One recorded run: its aggregate figures and the outcome of each case. */
    public record Entry(String cycle, String at, int executed, int planned,
                        double overall, double critical, Map<String, String> results) {
    }

    private final List<Entry> entries;

    public RunHistory(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static RunHistory empty() {
        return new RunHistory(List.of());
    }

    public static RunHistory read(Path file) {
        if (file == null || !Files.exists(file)) {
            return empty();
        }
        List<Entry> parsed = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(line);
                Map<String, String> results = new LinkedHashMap<>();
                node.path("results").fields()
                        .forEachRemaining(e -> results.put(e.getKey(), e.getValue().asText()));
                parsed.add(new Entry(node.path("cycle").asText(""), node.path("at").asText(""),
                        node.path("executed").asInt(0), node.path("planned").asInt(0),
                        node.path("overall").asDouble(0), node.path("critical").asDouble(0),
                        results));
            }
        } catch (IOException | RuntimeException unreadable) {
            // A trend is a nicety; an unreadable history must not stop the report.
            return new RunHistory(parsed);
        }
        return new RunHistory(parsed);
    }

    public List<Entry> entries() {
        return entries;
    }

    public int runs() {
        return entries.size();
    }

    /**
     * Share of cases that produced the same outcome every time they ran.
     *
     * <p>A case that passes in one cycle and fails in the next without the
     * service changing is the flake this figure exists to surface. Empty until
     * two runs have recorded a case in common, because with one run every case
     * trivially agrees with itself and the answer would always be 100%.
     */
    public OptionalDouble stability() {
        Map<String, List<String>> outcomes = new LinkedHashMap<>();
        for (Entry entry : entries) {
            entry.results().forEach((id, status) -> {
                if (status.equals("pass") || status.equals("fail")) {
                    outcomes.computeIfAbsent(id, k -> new ArrayList<>()).add(status);
                }
            });
        }
        List<List<String>> repeated = outcomes.values().stream().filter(o -> o.size() > 1).toList();
        if (repeated.isEmpty()) {
            return OptionalDouble.empty();
        }
        long stable = repeated.stream().filter(o -> o.stream().distinct().count() == 1).count();
        return OptionalDouble.of(stable * 100.0 / repeated.size());
    }

    /**
     * Share of defects that escaped an earlier cycle: the case that found the
     * defect had already run, and passed, before the defect was raised.
     *
     * <p>Empty with no prior cycle. Printing 0 there says "nothing escaped"
     * when the truth is "there was nowhere to escape from".
     */
    public OptionalDouble leakage(Defects defects) {
        if (entries.size() < 2 || defects == null || defects.all().isEmpty()) {
            return OptionalDouble.empty();
        }
        List<Entry> prior = entries.subList(0, entries.size() - 1);
        long leaked = defects.all().stream()
                .filter(d -> !d.foundBy().isBlank())
                .filter(d -> prior.stream()
                        .anyMatch(e -> "pass".equals(e.results().get(d.foundBy()))))
                .count();
        return OptionalDouble.of(leaked * 100.0 / defects.all().size());
    }
}
