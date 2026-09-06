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
        Path cucumberJson = targetDir.resolve("cucumber-reports/cucumber.json");
        SpecInputs spec = new SpecInputs(specDir);
        Defects defects = Defects.fromCsv(defectsCsv);
        Requirements requirements = spec.requirements();

        // D-4/D-5: the plan is the catalogue. Falling back to the run makes
        // every completeness figure 100% by construction, so the fallback only
        // stands when the catalogue is genuinely absent.
        List<RunResult> asRun = CucumberResults.read(cucumberJson);
        List<PlannedCase> planned = spec.plannedSet();
        if (planned.isEmpty()) {
            planned = PlannedCase.of(asRun);
        }
        ModuleVocabulary vocabulary = new ModuleVocabulary(planned);
        List<RunResult> results = vocabulary.applyTo(asRun);

        java.util.Map<String, Integer> budgetSeconds = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : spec.list("budgets.yaml")) {
            Object seconds = row.get("budget_seconds");
            if (seconds instanceof Number number) {
                budgetSeconds.put(text(row, "profile", ""), number.intValue());
            }
        }
        RunHistory history = RunHistory.read(historyFile);

        Metrics metrics = Metrics.compute(results, planned, defects, requirements,
                history, budgetSeconds);
        List<ExitCriteria.Evaluated> criteria =
                ExitCriteria.evaluate(spec.exitCriteria(), metrics);
        ExitCriteria.Recommendation recommendation =
                ExitCriteria.recommend(criteria, defects, results);

        ObjectNode data = buildData(results, planned, vocabulary, history, spec, defects,
                requirements, metrics, criteria, recommendation);

        Files.createDirectories(targetDir);
        Path dataFile = targetDir.resolve("report-data.json");
        Files.writeString(dataFile,
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                StandardCharsets.UTF_8);

        appendHistory(data, metrics, results, planned.size(),
                CucumberResults.runStartedAt(cucumberJson));

        Path html = targetDir.resolve("test-execution-summary-report.html");
        Files.writeString(html, inject(MAPPER.writeValueAsString(data)), StandardCharsets.UTF_8);
        return html;
    }

    // --- the data contract the template expects --------------------------

    private ObjectNode buildData(List<RunResult> results,
                                 List<PlannedCase> planned,
                                 ModuleVocabulary vocabulary,
                                 RunHistory history,
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
        root.set("history", history(history));
        root.set("defects", defects(defects, vocabulary, results));
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
        root.set("blocked", blocked(results, planned));
        root.set("cases", cases(results, planned, defects));
        root.set("metrics", metricsNode(metrics));
        return root;
    }

    private ObjectNode reportIdentification(SpecInputs spec, List<RunResult> results,
                                            ExitCriteria.Recommendation recommendation) {
        Map<String, Object> configured = spec.report();
        ZonedDateTime now = ZonedDateTime.now();
        double seconds = results.stream().mapToDouble(RunResult::durationSeconds).sum();

        ObjectNode report = MAPPER.createObjectNode();
        // D-9: these four were read under names spec/report.yaml does not use,
        // so the file's own values were silently ignored and the defaults
        // below stood in for them.
        String prefix = text(configured, "report_id_prefix", "TESR-BE");
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
        report.put("preparedBy", text(configured, "prepared_by_role", "Test Automation Engineer"));
        report.put("reviewedBy", text(configured, "reviewed_by_role", "Technical Architect"));
        report.put("approvedBy", text(configured, "approved_by_role", "Product Owner"));
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

    private ArrayNode defects(Defects defects, ModuleVocabulary vocabulary,
                              List<RunResult> results) {
        ArrayNode array = MAPPER.createArrayNode();
        LocalDate today = LocalDate.now();
        for (Defects.Defect defect : defects.all()) {
            ObjectNode node = array.addObject();
            node.put("id", defect.id());
            node.put("summary", defect.summary());
            node.put("module", defect.module());
            // D-10: the register's own column stays as authored, because it
            // records the suite layer the defect belongs to. The join key is
            // separate and always in the catalogue's vocabulary.
            node.put("dm", vocabulary.of(defect));
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
    private ArrayNode blocked(List<RunResult> results, List<PlannedCase> planned) {
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

        java.util.Set<String> executedIds = results.stream().filter(RunResult::executed)
                .map(RunResult::caseId).collect(java.util.stream.Collectors.toSet());
        int notExecuted = (int) planned.stream()
                .filter(p -> !executedIds.contains(p.id())).count();
        if (notExecuted > 0) {
            ObjectNode node = array.addObject();
            node.put("c", "Not executed — tag filter");
            node.put("n", notExecuted);
            node.put("cause", "Excluded by the execution profile selected for this run.");
            node.put("impact", "Chosen, not blocked. Counts toward planned, not toward executed.");
        }
        return array;
    }

    /**
     * The planned set left-joined against the run.
     *
     * <p>D-4. Built from the executed results alone, this array was both the
     * numerator and the denominator of execution completeness, which therefore
     * rendered 100% after any tag-filtered run. A catalogued case the run never
     * reached is emitted here with the status the template counts as not
     * executed, so the slippage table has something to list.
     */
    private ArrayNode cases(List<RunResult> results, List<PlannedCase> planned, Defects defects) {
        java.util.Map<String, RunResult> executed = new LinkedHashMap<>();
        results.forEach(r -> executed.putIfAbsent(r.caseId(), r));

        ArrayNode array = MAPPER.createArrayNode();
        for (PlannedCase plan : planned) {
            RunResult result = executed.remove(plan.id());
            if (result == null) {
                absentCase(array.addObject(), plan);
            } else {
                executedCase(array.addObject(), result, defects);
            }
        }
        // A case that ran and is not in the catalogue is a catalogue gap, not a
        // reason to drop a result: dropping it would push executed above
        // planned. PlannedSetGuard fails the build on exactly this.
        executed.values().forEach(result -> executedCase(array.addObject(), result, defects));
        return array;
    }

    private void absentCase(ObjectNode node, PlannedCase plan) {
        node.put("id", plan.id());
        node.put("t", plan.title());
        node.put("m", plan.module());
        node.put("ty", plan.type());
        node.put("p", plan.priority());
        node.put("pr", plan.profile());
        node.put("s", RunResult.Status.SKIP.token());
        node.put("d", 0);
        node.put("reason", "Not selected by this run's tag filter or execution profile.");
    }

    private void executedCase(ObjectNode node, RunResult result, Defects defects) {
        node.put("id", result.caseId());
        node.put("t", result.title());
        node.put("m", result.module());
        node.put("ty", result.type());
        node.put("p", result.priority());
        node.put("pr", result.profile());
        node.put("s", result.status().token());
        node.put("d", result.durationSeconds());

        // D-8: the failure panels read why/pub/obs/defect and nothing wrote
        // them, so every panel rendered empty over evidence the suite had
        // already collected and attached.
        if (result.failureMessage() != null) {
            node.put("why", result.failingStep() == null
                    ? result.failureMessage()
                    : result.failingStep() + System.lineSeparator() + result.failureMessage());
        }
        evidence(node, result, "published", "pub");
        evidence(node, result, "observed", "obs");
        evidence(node, result, "envelope", "env");
        evidence(node, result, "service log", "log");
        if (result.attachments().containsKey("blocked")) {
            node.put("reason", clip(result.attachments().get("blocked")));
        }

        String defectIds = defects == null ? "" : defects.all().stream()
                .filter(d -> result.caseId().equals(d.foundBy()))
                .filter(Defects.Defect::open)
                .map(Defects.Defect::id)
                .collect(java.util.stream.Collectors.joining(", "));
        if (!defectIds.isEmpty()) {
            node.put("defect", defectIds);
        }
    }

    private void evidence(ObjectNode node, RunResult result, String attachment, String field) {
        String value = result.attachments().get(attachment);
        if (value != null && !value.isBlank()) {
            node.put(field, clip(value));
        }
    }

    private static String clip(String value) {
        return value.length() > 4000 ? value.substring(0, 4000) + "…" : value;
    }

    private ObjectNode metricsNode(Metrics metrics) {
        ObjectNode node = MAPPER.createObjectNode();
        metrics.all().forEach((name, metric) -> {
            ObjectNode entry = node.putObject(name);
            // A null value is what "not measured" looks like in the data.
            // Math.round(NaN) is 0, which would publish a constant as a
            // measurement all over again (D-6).
            if (metric.measured()) {
                entry.put("value", Math.round(metric.value() * 10) / 10.0);
            } else {
                entry.putNull("value");
            }
            entry.put("formula", metric.formula());
            entry.put("detail", metric.detail());
        });
        return node;
    }

    // --- history ---------------------------------------------------------

    private ArrayNode history(RunHistory history) {
        ArrayNode array = MAPPER.createArrayNode();
        List<RunHistory.Entry> entries = history.entries();
        for (RunHistory.Entry entry : entries.subList(Math.max(0, entries.size() - 8), entries.size())) {
            ObjectNode node = array.addObject();
            node.put("cycle", entry.cycle());
            node.put("at", entry.at());
            node.put("executed", entry.executed());
            node.put("planned", entry.planned());
            node.put("overall", entry.overall());
            node.put("critical", entry.critical());
        }
        return array;
    }

    /**
     * D-7. History is a record of runs, and this used to append on every
     * invocation - so regenerating a report over an unchanged cucumber.json
     * manufactured a cycle that never happened, and the trend line grew
     * without anything having been executed.
     *
     * <p>The run's own timestamp is recorded rather than the moment of
     * generation, and per-case outcomes alongside it, because stability and
     * leakage cannot be computed from aggregates (D-6).
     */
    private void appendHistory(ObjectNode data, Metrics metrics, List<RunResult> results,
                               int planned, String runStartedAt) {
        long executed = results.stream().filter(RunResult::executed).count();
        if (executed == 0) {
            return;
        }
        String at = runStartedAt == null ? ZonedDateTime.now().toString() : runStartedAt;
        if (alreadyRecorded(at)) {
            return;
        }
        try {
            Files.createDirectories(historyFile.getParent());
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("cycle", data.path("report").path("cycle").asText());
            entry.put("at", at);
            entry.put("executed", executed);
            entry.put("planned", planned);
            entry.put("overall", Math.round(metrics.value("pass_rate") * 10) / 10.0);
            entry.put("critical", Math.round(metrics.value("critical_pass_rate") * 10) / 10.0);
            ObjectNode outcomes = entry.putObject("results");
            results.forEach(r -> outcomes.put(r.caseId(), r.status().token()));
            Files.writeString(historyFile, MAPPER.writeValueAsString(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            // History is additive, never a reason to fail.
        }
    }

    /** Two reports over one cucumber.json are one run, however often it is regenerated. */
    private boolean alreadyRecorded(String at) {
        return RunHistory.read(historyFile).entries().stream()
                .anyMatch(entry -> at.equals(entry.at()));
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
