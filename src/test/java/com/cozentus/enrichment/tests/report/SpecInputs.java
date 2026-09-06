package com.cozentus.enrichment.tests.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * The committed configuration of spec 8.6 Class B: the planned set, the
 * requirement map, gate targets, budgets, scope and risks.
 *
 * <p>None of it is derivable from {@code cucumber.json}, which records only what
 * ran. A missing file is not fatal — the report says the section had no input
 * rather than failing the build (spec 8.10).
 */
public final class SpecInputs {

    private final Path directory;

    public SpecInputs(Path directory) {
        this.directory = directory;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(String fileName) {
        Object loaded = load(fileName);
        if (loaded instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add((Map<String, Object>) map);
                }
            }
            return rows;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String fileName) {
        Object loaded = load(fileName);
        return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** A named list inside a mapping file, e.g. {@code in_scope} within scope.yaml. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> section(String fileName, String key) {
        Object value = map(fileName).get(key);
        if (value instanceof List<?> list) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    rows.add((Map<String, Object>) entry);
                }
            }
            return rows;
        }
        return List.of();
    }

    private Object load(String fileName) {
        Path file = directory.resolve(fileName);
        if (!Files.exists(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    public Requirements requirements() {
        List<Requirements.Requirement> parsed = new ArrayList<>();
        for (Map<String, Object> row : list("requirements.yaml")) {
            List<String> cases = new ArrayList<>();
            Object raw = row.get("cases");
            if (raw instanceof List<?> items) {
                items.forEach(item -> cases.add(String.valueOf(item)));
            }
            parsed.add(new Requirements.Requirement(
                    String.valueOf(row.getOrDefault("id", "")),
                    String.valueOf(row.getOrDefault("description", "")),
                    cases,
                    String.valueOf(row.getOrDefault("criticality", "medium"))));
        }
        return new Requirements(parsed);
    }

    public List<ExitCriteria.Criterion> exitCriteria() {
        List<ExitCriteria.Criterion> parsed = new ArrayList<>();
        for (Map<String, Object> row : list("exit-criteria.yaml")) {
            parsed.add(new ExitCriteria.Criterion(
                    String.valueOf(row.getOrDefault("criterion", "")),
                    String.valueOf(row.getOrDefault("metric", "")),
                    String.valueOf(row.getOrDefault("operator", "gte")),
                    toDouble(row.get("target")),
                    Boolean.TRUE.equals(row.get("blocking"))));
        }
        return parsed;
    }

    /** The planned set. Execution percentage and slippage are impossible without it. */
    public List<Map<String, Object>> plannedCases() {
        List<Map<String, Object>> cases = list("test-cases.yaml");
        if (!cases.isEmpty()) {
            return cases;
        }
        Object nested = map("test-cases.yaml").get("cases");
        if (nested instanceof List<?>) {
            return section("test-cases.yaml", "cases");
        }
        return List.of();
    }

    /** The planned set as rows, in catalogue order. */
    public List<PlannedCase> plannedSet() {
        return plannedCases().stream().map(PlannedCase::from)
                .filter(c -> !c.id().isBlank()).toList();
    }

    public Map<String, Object> report() {
        return map("report.yaml");
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).replace("%", "").trim());
        } catch (RuntimeException notANumber) {
            return 0;
        }
    }

    /** Which spec files are present, so the report can name what it lacked. */
    public Map<String, Boolean> availability() {
        Map<String, Boolean> present = new LinkedHashMap<>();
        for (String name : List.of("test-cases.yaml", "requirements.yaml", "exit-criteria.yaml",
                "budgets.yaml", "scope.yaml", "report.yaml", "risks.yaml")) {
            present.put(name, Files.exists(directory.resolve(name)));
        }
        return present;
    }
}
