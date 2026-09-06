package com.cozentus.enrichment.tests.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The defect register. Class C in spec 8.6.
 *
 * <p>The spec's primary source is a Jira feed; this is the documented fallback,
 * a CSV exported and committed per cycle. The link between suite and tracker is
 * the test case that found the defect: the suite knows that much, and the
 * tracker supplies the rest.
 */
public final class Defects {

    public record Defect(String id, String summary, String module, String severity,
                         String priority, String status, String owner,
                         LocalDate raised, String foundBy) {

        public boolean open() {
            return !"Closed".equalsIgnoreCase(status) && !"Deferred".equalsIgnoreCase(status);
        }

        public long ageDays(LocalDate asOf) {
            return raised == null ? 0 : ChronoUnit.DAYS.between(raised, asOf);
        }
    }

    private final List<Defect> defects;

    private Defects(List<Defect> defects) {
        this.defects = List.copyOf(defects);
    }

    public static Defects empty() {
        return new Defects(List.of());
    }

    public static Defects fromCsv(Path file) {
        if (!Files.exists(file)) {
            return empty();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> header = null;
            List<Defect> parsed = new ArrayList<>();

            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                List<String> fields = split(line);
                if (header == null) {
                    header = fields;
                    continue;
                }
                Map<String, String> row = new java.util.LinkedHashMap<>();
                for (int i = 0; i < header.size() && i < fields.size(); i++) {
                    row.put(header.get(i).trim(), fields.get(i).trim());
                }
                parsed.add(new Defect(
                        row.getOrDefault("id", ""),
                        row.getOrDefault("summary", ""),
                        row.getOrDefault("module", ""),
                        row.getOrDefault("severity", "S3"),
                        row.getOrDefault("priority", "P3"),
                        row.getOrDefault("status", "Open"),
                        row.getOrDefault("owner", ""),
                        parseDate(row.get("raised")),
                        row.getOrDefault("test_case", "")));
            }
            return new Defects(parsed);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    /**
     * Spec 8.8. Impact is derived from a fixed rule, not judged at report time,
     * and the rule is printed in the report so the reader can audit it.
     */
    public String releaseImpact(Defect defect, List<RunResult> results) {
        if (!defect.open()) {
            return "Closed";
        }
        boolean againstCritical = results.stream()
                .anyMatch(r -> r.caseId().equals(defect.foundBy())
                        && "Critical".equalsIgnoreCase(r.priority()));
        boolean againstHigh = results.stream()
                .anyMatch(r -> r.caseId().equals(defect.foundBy())
                        && "High".equalsIgnoreCase(r.priority()));

        if ("S1".equalsIgnoreCase(defect.severity()) || againstCritical) {
            return "Blocking";
        }
        if ("S2".equalsIgnoreCase(defect.severity()) && againstHigh) {
            return "Conditional";
        }
        return "Non-blocking";
    }

    public List<Defect> all() {
        return defects;
    }

    public List<Defect> open() {
        return defects.stream().filter(Defect::open).toList();
    }

    public long closedCount() {
        return defects.stream().filter(d -> !d.open()).count();
    }

    public boolean anyBlocking(List<RunResult> results) {
        return open().stream().anyMatch(d -> "Blocking".equals(releaseImpact(d, results)));
    }

    public boolean anyConditional(List<RunResult> results) {
        return open().stream().anyMatch(d -> "Conditional".equals(releaseImpact(d, results)));
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
        } catch (RuntimeException notADate) {
            return null;
        }
    }

    static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
