package com.cozentus.enrichment.tests.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces the Test Execution Summary Report (spec 8.10).
 *
 * <p>Templating over computed values; no language model is involved at any
 * point. The data is written to {@code report-data.json} before injection, so a
 * dashboard or a pipeline gate can consume it without reparsing HTML.
 *
 * <p>Generation must not fail the build: a report that cannot be produced is
 * logged and the run result stands.
 */
public final class ReportGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MARKER = "__REPORT_DATA__";
    private static final String TEMPLATE = "/report/report-template.html";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private final Path targetDir;
    private final Path specDir;
    private final Path defectsCsv;
    private final Path historyFile;

    public ReportGenerator(Path targetDir, Path specDir, Path defectsCsv, Path historyFile) {
        this.targetDir = targetDir;
        this.specDir = specDir;
        this.defectsCsv = defectsCsv;
        this.historyFile = historyFile;
    }

    public static void main(String[] args) {
        Path base = Path.of(args.length > 0 ? args[0] : ".");
        ReportGenerator generator = new ReportGenerator(
                base.resolve("target"), base.resolve("spec"),
                base.resolve("defects.csv"), base.resolve("reports/history.jsonl"));
        try {
            Path written = generator.generate();
            System.out.println("Test Execution Summary Report: " + written);
        } catch (RuntimeException | IOException e) {
            // Spec 8.10: never fail the build for a report.
            System.err.println("Report generation failed, run result stands: " + e);
        }
    }

    public Path generate() throws IOException {
        List<RunResult> results = CucumberResults.read(
                targetDir.resolve("cucumber-reports/cucumber.json"));
        SpecInputs spec = new SpecInputs(specDir);
        Defects defects = Defects.fromCsv(defectsCsv);
        Requirements requirements = spec.requirements();

        List<Map<String, Object>> planned = spec.plannedCases();
        int plannedCount = planned.isEmpty() ? results.size() : planned.size();

        java.util.Map<String, Integer> budgetSeconds = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : spec.list("budgets.yaml")) {
            Object seconds = row.get("budget_seconds");
            if (seconds instanceof Number number) {
                budgetSeconds.put(text(row, "profile", ""), number.intValue());
            }
        }
        int historyDepth = countHistory();

        Metrics metrics = Metrics.compute(results, plannedCount, defects, requirements,
                historyDepth, budgetSeconds);
        List<ExitCriteria.Evaluated> criteria =
                ExitCriteria.evaluate(spec.exitCriteria(), metrics);
        ExitCriteria.Recommendation recommendation =
                ExitCriteria.recommend(criteria, defects, results);

        ObjectNode data = buildData(results, planned, plannedCount, spec, defects,
                requirements, metrics, criteria, recommendation);

        Files.createDirectories(targetDir);
        Path dataFile = targetDir.resolve("report-data.json");
        Files.writeString(dataFile,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                StandardCharsets.UTF_8);

        appendHistory(data, metrics);

        Path html = targetDir.resolve("test-execution-summary-report.html");
        Files.writeString(html, inject(MAPPER.writeValueAsString(data)), StandardCharsets.UTF_8);
        return html;
    }

    // --- the data contract the template expects --------------------------

    private ObjectNode buildData(List<RunResult> results,
                                 List<Map<String, Object>> planned,
                                 int plannedCount,
                                 SpecInputs spec,
                                 Defects defects,
                                 Requirements requirements,
                                 Metrics metrics,
                                 List<ExitCriteria.Evaluated> criteria,
                                 ExitCriteria.Recommendation recommendation) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("report", reportIdentification(spec, results, recommendation));
        root.set("exitCriteria", exitCriteria(criteria));
        root.set("budgets", budgets(spec, results));
        root.set("history", history());
        root.set("defects", defects(defects, results));
        root.set("requirements", requirements(requirements, results));
        root.set("risks", rows(spec.list("risks.yaml"),
                Map.of("r", "risk", "l", "likelihood", "i", "impact",
                       "m", "mitigation", "o", "owner")));
        root.set("environment", environment(spec));
        root.set("inScope", rows(spec.section("scope.yaml", "in_scope"),
                Map.of("t", "title", "d", "detail")));
        root.set("outScope", rows(spec.section("scope.yaml", "out_of_scope"),
                Map.of("t", "title", "d", "detail")));
        root.set("assumptions", rows(spec.section("scope.yaml", "assumptions"),
                Map.of("t", "title", "d", "detail")));
        root.set("blocked", blocked(results, planned, plannedCount));
        root.set("cases", cases(results));
        root.set("metrics", metricsNode(metrics));
        return root;
    }

    private ObjectNode reportIdentification(SpecInputs spec, List<RunResult> results,
                                            ExitCriteria.Recommendation recommendation) {
        Map<String, Object> configured = spec.report();
        ZonedDateTime now = ZonedDateTime.now();
        double seconds = results.stream().mapToDouble(RunResult::durationSeconds).sum();

        ObjectNode report = MAPPER.createObjectNode();
        String prefix = text(configured, "id_prefix", "TESR-BE");
        report.put("id", "%s-%s-%s".formatted(prefix, LocalDate.now(), env("BUILD_NUMBER", "local")));
        report.put("version", text(configured, "version", "1.0"));
        report.put("project", text(configured, "project", "Booking Enrichment"));
        report.put("module", text(configured, "module", "City enrichment service"));
        report.put("build", env("BUILD_UNDER_TEST", text(configured, "build", "city-enrichment (local build)")));
        report.put("environment", System.getProperty("suite.env", "local").toUpperCase());
        report.put("cycle", env("TEST_CYCLE", "local run"));
        report.put("started", now.minusSeconds((long) seconds).format(STAMP));
        report.put("ended", now.format(STAMP));
        report.put("duration", Math.round(seconds));
        report.put("preparedBy", text(configured, "prepared_by", "Test Automation Engineer"));
        report.put("reviewedBy", text(configured, "reviewed_by", "Technical Architect"));
        report.put("approvedBy", text(configured, "approved_by", "Product Owner"));
        report.put("trigger", env("GITHUB_EVENT_NAME", "Local run"));
        report.put("recommendation", recommendation.token());
        report.put("commit", env("GITHUB_SHA", ""));
        return report;
    }

    private ArrayNode exitCriteria(List<ExitCriteria.Evaluated> criteria) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ExitCriteria.Evaluated criterion : criteria) {
            ObjectNode node = array.addObject();
            node.put("c", criterion.description());
            node.put("target", criterion.target());
            node.put("actual", criterion.actual());
            node.put("ok", criterion.met());
            node.put("blocking", criterion.blocking());
        }
        return array;
    }

    private ArrayNode budgets(SpecInputs spec, List<RunResult> results) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Map<String, Object> row : spec.list("budgets.yaml")) {
            String profile = text(row, "profile", "");
            List<RunResult> inProfile = results.stream()
                    .filter(r -> r.profile().equalsIgnoreCase(profile)).toList();
            ObjectNode node = array.addObject();
            node.put("profile", RunResult.titleCase(profile));
            node.put("cases", inProfile.size());
            node.put("duration", Math.round(inProfile.stream()
                    .mapToDouble(RunResult::durationSeconds).sum()));
            Object budget = row.get("budget_seconds");
            if (budget instanceof Number number) {
                node.put("budget", number.intValue());
            } else {
                node.putNull("budget");
            }
        }
        return array;
    }

    private ArrayNode defects(Defects defects, List<RunResult> results) {
        ArrayNode array = MAPPER.createArrayNode();
        LocalDate today = LocalDate.now();
        for (Defects.Defect defect : defects.all()) {
            ObjectNode node = array.addObject();
            node.put("id", defect.id());
            node.put("summary", defect.summary());
            node.put("module", defect.module());
            node.put("severity", defect.severity());
            node.put("priority", defect.priority());
            node.put("foundBy", defect.foundBy());
            node.put("status", defect.status());
            node.put("owner", defect.owner());
            node.put("raised", defect.raised() == null ? "" : defect.raised().toString());
            node.put("age", defect.ageDays(today));
            node.put("impact", defects.releaseImpact(defect, results));
        }
        return array;
    }

    private ArrayNode requirements(Requirements requirements, List<RunResult> results) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Requirements.Requirement requirement : requirements.all()) {
            ObjectNode node = array.addObject();
            node.put("id", requirement.id());
            node.put("desc", requirement.description());
            node.put("cases", String.join(", ", requirement.cases()));
            node.put("verdict", requirements.verdict(requirement, results));
            node.put("criticality", requirement.criticality());
        }
        return array;
    }

    private ArrayNode environment(SpecInputs spec) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Map<String, Object> row : spec.section("scope.yaml", "environment")) {
            ObjectNode node = array.addObject();
            node.put("c", text(row, "component", ""));
            node.put("v", text(row, "version", ""));
            node.put("n", text(row, "note", ""));
        }
        if (array.isEmpty()) {
            add(array, "Application under test", env("BUILD_UNDER_TEST", "city-enrichment (local build)"), "Started per scenario by the suite");
            add(array, "Message broker", "Apache Kafka 3.9 (KRaft)", "Three topics, three partitions each");
            add(array, "Suite", "JDK 21, Maven, Cucumber-JVM 7", "Black box; no compile dependency on the service");
        }
        return array;
    }

    private void add(ArrayNode array, String component, String version, String note) {
        ObjectNode node = array.addObject();
        node.put("c", component);
        node.put("v", version);
        node.put("n", note);
    }

    /** Spec 8.11: blocked and not-executed are different facts and stay separate. */
    private ArrayNode blocked(List<RunResult> results, List<Map<String, Object>> planned, int plannedCount) {
        ArrayNode array = MAPPER.createArrayNode();

        long blockedCount = results.stream()
                .filter(r -> r.status() == RunResult.Status.BLOCKED).count();
        if (blockedCount > 0) {
            ObjectNode node = array.addObject();
            node.put("c", "Blocked — prerequisite failed");
            node.put("n", blockedCount);
            node.put("cause", "A dependency of the scenario failed, so it could not run.");
            node.put("impact", "Treat as unverified. The cause is recorded on each scenario.");
        }

        int notExecuted = Math.max(0, plannedCount - (int) results.stream().filter(RunResult::executed).count());
        if (notExecuted > 0) {
            ObjectNode node = array.addObject();
            node.put("c", "Not executed — tag filter");
            node.put("n", notExecuted);
            node.put("cause", "Excluded by the execution profile selected for this run.");
            node.put("impact", "Chosen, not blocked. Counts toward planned, not toward executed.");
        }
        return array;
    }

    private ArrayNode cases(List<RunResult> results) {
        ArrayNode array = MAPPER.createArrayNode();
        for (RunResult result : results) {
            ObjectNode node = array.addObject();
            node.put("id", result.caseId());
            node.put("t", result.title());
            node.put("m", result.module());
            node.put("ty", result.type());
            node.put("p", result.priority());
            node.put("pr", result.profile());
            node.put("s", result.status().token());
            node.put("d", result.durationSeconds());
            if (result.failureMessage() != null) {
                node.put("err", result.failureMessage());
                node.put("step", result.failingStep());
            }
            result.attachments().forEach((name, value) ->
                    node.put("ev_" + name.replaceAll("[^a-zA-Z0-9]", "_"),
                            value.length() > 4000 ? value.substring(0, 4000) + "…" : value));
        }
        return array;
    }

    private ObjectNode metricsNode(Metrics metrics) {
        ObjectNode node = MAPPER.createObjectNode();
        metrics.all().forEach((name, metric) -> {
            ObjectNode entry = node.putObject(name);
            entry.put("value", Math.round(metric.value() * 10) / 10.0);
            entry.put("formula", metric.formula());
            entry.put("detail", metric.detail());
        });
        return node;
    }

    // --- history ---------------------------------------------------------

    /** How many runs are on record, for metrics that need repetition to mean anything. */
    private int countHistory() {
        if (!Files.exists(historyFile)) {
            return 0;
        }
        try {
            return (int) Files.readAllLines(historyFile, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank()).count();
        } catch (IOException unreadable) {
            return 0;
        }
    }

    private ArrayNode history() {
        ArrayNode array = MAPPER.createArrayNode();
        if (!Files.exists(historyFile)) {
            return array;
        }
        try {
            List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 8);
            for (String line : lines.subList(from, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                array.add(MAPPER.readTree(line));
            }
        } catch (IOException unreadable) {
            // A trend is a nicety; its absence must not stop the report.
        }
        return array;
    }

    private void appendHistory(ObjectNode data, Metrics metrics) {
        try {
            Files.createDirectories(historyFile.getParent());
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("cycle", data.path("report").path("cycle").asText());
            entry.put("overall", Math.round(metrics.value("pass_rate") * 10) / 10.0);
            entry.put("critical", Math.round(metrics.value("critical_pass_rate") * 10) / 10.0);
            entry.put("at", ZonedDateTime.now().toString());
            Files.writeString(historyFile, MAPPER.writeValueAsString(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            // Same: history is additive, never a reason to fail.
        }
    }

    // --- template --------------------------------------------------------

    private String inject(String json) throws IOException {
        try (InputStream in = ReportGenerator.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IOException("Missing template on the classpath: " + TEMPLATE);
            }
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!template.contains(MARKER)) {
                throw new IOException("Template has no " + MARKER + " marker to inject into");
            }
            return template.replace(MARKER, json);
        }
    }

    private ArrayNode rows(List<Map<String, Object>> source, Map<String, String> mapping) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Map<String, Object> row : source) {
            ObjectNode node = array.addObject();
            mapping.forEach((target, from) -> node.put(target, text(row, from, "")));
        }
        return array;
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
