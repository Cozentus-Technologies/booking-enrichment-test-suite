package com.cozentus.enrichment.tests.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D-12. The golden test for the report.
 *
 * <p>Every figure the report published used to be reproducible only by
 * believing it. Some were literals in the template: a 100% critical pass rate,
 * a 2m 41s mean time to detect, a defect id that is not in the register. A
 * literal cannot be wrong about a run it never read, which is exactly why it
 * has to be the thing this test forbids.
 *
 * <p>The fixture run is deliberately unhappy: one critical case passes, one
 * critical case fails, a Scenario Outline contributes two Examples rows under
 * one id, one case is blocked, and 90-odd catalogued cases never ran at all.
 * A report that still says "all critical test cases passed" over that input is
 * the bug.
 */
class ReportGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String template;

    private static JsonNode data;
    private static String html;

    @TempDir
    static Path base;

    @BeforeAll
    static void renderOnce() throws IOException {
        try (InputStream in = ReportGeneratorTest.class.getResourceAsStream(
                "/report/report-template.html")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Path target = base.resolve("target/cucumber-reports");
        Files.createDirectories(target);
        try (InputStream in = ReportGeneratorTest.class.getResourceAsStream(
                "/report/fixtures/cucumber.json")) {
            Files.write(target.resolve("cucumber.json"), in.readAllBytes());
        }

        // The committed spec directory and defect register, not a fixture: the
        // point of the planned set is that it is the real catalogue.
        ReportGenerator generator = new ReportGenerator(
                base.resolve("target"), Path.of("spec"), Path.of("defects.csv"),
                base.resolve("reports/history.jsonl"));
        Path written = generator.generate();

        html = Files.readString(written, StandardCharsets.UTF_8);
        data = MAPPER.readTree(base.resolve("target/report-data.json").toFile());
    }

    // ---- the figures themselves -------------------------------------------

    @Test
    void criticalPassRateIsNotOneHundred() {
        // One critical passed, one critical failed, so the release-governing
        // figure is 50. Any arrangement that cannot fall below 100 is broken.
        assertThat(data.at("/metrics/critical_pass_rate/value").asDouble()).isEqualTo(50.0);
        assertThat(data.at("/metrics/critical_pass_rate/detail").asText()).isEqualTo("1 / 2");
    }

    @Test
    void criticalExecutionRateIsMeasuredAgainstThePlanNotTheRun() {
        // D-5: derived from the executed list alone it could only ever be 100%.
        JsonNode metric = data.at("/metrics/critical_execution_rate");
        assertThat(metric.path("value").asDouble()).isLessThan(100.0);

        long criticalPlanned = plannedWithPriority("Critical");
        assertThat(metric.path("detail").asText()).isEqualTo("2 / " + criticalPlanned);
    }

    @Test
    void executedIsFewerThanPlannedAndTheAbsentCasesAreListed() {
        List<JsonNode> cases = list(data.path("cases"));
        long executed = cases.stream().filter(c -> {
            String s = c.path("s").asText();
            return s.equals("pass") || s.equals("fail");
        }).count();

        assertThat(executed).isEqualTo(3);
        assertThat(cases.size()).isGreaterThan((int) executed);

        List<String> absent = cases.stream().filter(c -> c.path("s").asText().equals("skipped"))
                .map(c -> c.path("id").asText()).toList();
        assertThat(absent).contains("TC-21", "TC-60", "TC-75").doesNotContain("TC-01", "TC-06");

        // Every absent case carries the plan's own columns, or the slippage
        // table would list ids with nothing beside them.
        JsonNode tc21 = cases.stream().filter(c -> c.path("id").asText().equals("TC-21"))
                .findFirst().orElseThrow();
        assertThat(tc21.path("t").asText()).isNotEmpty();
        assertThat(tc21.path("m").asText()).isNotEmpty();
        assertThat(tc21.path("p").asText()).isNotEmpty();
        assertThat(tc21.path("reason").asText()).isNotEmpty();
    }

    @Test
    void plannedSetCarriesEveryCatalogueCase() {
        List<String> ids = list(data.path("cases")).stream()
                .map(c -> c.path("id").asText()).toList();
        for (java.util.Map<String, Object> planned : new SpecInputs(Path.of("spec")).plannedCases()) {
            assertThat(ids).contains(String.valueOf(planned.get("id")));
        }
    }

    @Test
    void verdictIsHold() {
        // A blocking exit criterion is unmet, so the recommendation the
        // template renders must be the held one, whatever the defect register
        // happens to say.
        assertThat(data.at("/report/recommendation").asText()).isEqualTo("stop");
        assertThat(list(data.path("exitCriteria")))
                .anyMatch(c -> c.path("blocking").asBoolean() && !c.path("ok").asBoolean());
    }

    @Test
    void evidencePanelsArePopulated() {
        JsonNode failed = caseById("TC-06");
        assertThat(failed.path("s").asText()).isEqualTo("fail");
        assertThat(failed.path("why").asText()).contains("expected origin");
        assertThat(failed.path("pub").asText()).contains("BKG-0002");
        assertThat(failed.path("obs").asText()).contains("originConfidence");
        assertThat(failed.path("env").asText()).contains("partition");
        assertThat(failed.path("log").asText()).contains("CityMatcher");
    }

    @Test
    void aScenarioOutlineCollapsesToOneCase() {
        List<JsonNode> outline = list(data.path("cases")).stream()
                .filter(c -> c.path("id").asText().equals("TC-34")).toList();
        assertThat(outline).hasSize(1);
        assertThat(outline.get(0).path("s").asText()).isEqualTo("pass");
    }

    @Test
    void blockedIsNotCountedAsExecuted() {
        assertThat(caseById("TC-50").path("s").asText()).isEqualTo("blocked");
        assertThat(list(data.path("blocked")))
                .anyMatch(b -> b.path("c").asText().startsWith("Blocked"))
                .anyMatch(b -> b.path("c").asText().startsWith("Not executed"));
    }

    @Test
    void aConstantIsNeverPublishedAsAMeasurement() {
        // D-6: both of these evaluated to a fixed number for every input.
        assertThat(data.at("/metrics/automation_stability/value").isNull()).isTrue();
        assertThat(data.at("/metrics/automation_stability/detail").asText())
                .isEqualTo("not measured");
        assertThat(data.at("/metrics/defect_leakage/value").isNull()).isTrue();
        assertThat(data.at("/metrics/defect_leakage/detail").asText()).isEqualTo("not measured");
    }

    @Test
    void defectDensityJoinsOnTheCatalogueVocabulary() {
        // D-10: joined by tag-derived module names, only "Message contract"
        // matched and the one open defect was invisible.
        assertThat(data.at("/metrics/defect_density/detail").asText())
                .isNotEqualTo("highest in none");

        java.util.Map<String, PlannedCase> plan =
                PlannedCase.byId(new SpecInputs(Path.of("spec")).plannedSet());
        java.util.Set<String> catalogue = plan.values().stream()
                .map(PlannedCase::module).collect(java.util.stream.Collectors.toSet());

        List<JsonNode> traced = list(data.path("defects")).stream()
                .filter(d -> plan.containsKey(d.path("foundBy").asText())).toList();
        assertThat(traced).isNotEmpty();
        for (JsonNode defect : traced) {
            assertThat(defect.path("dm").asText())
                    .describedAs("defect %s counts against a catalogue module", defect.path("id"))
                    .isEqualTo(plan.get(defect.path("foundBy").asText()).module())
                    .isIn(catalogue);
        }

        // The register's own layering is kept for display and is not the join
        // key: this is one of the nine that used to match nothing.
        JsonNode harness = list(data.path("defects")).stream()
                .filter(d -> d.path("module").asText().equals("Harness")).findFirst().orElseThrow();
        assertThat(harness.path("dm").asText()).isNotEqualTo("Harness").isIn(catalogue);
    }

    @Test
    void historyRecordsRunsNotRegenerations() throws IOException {
        Path history = base.resolve("reports/history.jsonl");
        long after = Files.exists(history) ? Files.readAllLines(history).size() : 0;
        assertThat(after).isEqualTo(1);

        JsonNode entry = MAPPER.readTree(Files.readAllLines(history).get(0));
        assertThat(entry.path("executed").asInt()).isEqualTo(3);
        assertThat(entry.path("planned").asInt()).isGreaterThan(3);
        assertThat(entry.path("at").asText()).isEqualTo("2026-09-06T10:00:00.000Z");

        // D-7: a regeneration over the same cucumber.json is the same run.
        new ReportGenerator(base.resolve("target"), Path.of("spec"), Path.of("defects.csv"),
                history).generate();
        assertThat(Files.readAllLines(history)).hasSize(1);
    }

    @Test
    void reportIdentificationReadsEveryKeyReportYamlDefines() {
        // D-9: four of six keys were read under names the file does not use.
        assertThat(data.at("/report/id").asText()).startsWith("TESR-BE-");
        assertThat(data.at("/report/preparedBy").asText()).isEqualTo("Test Automation Engineer");
        assertThat(data.at("/report/reviewedBy").asText()).isEqualTo("Technical Architect");
        assertThat(data.at("/report/approvedBy").asText()).isEqualTo("Product Owner");
        assertThat(data.at("/report/project").asText())
                .isEqualTo("Booking Enrichment — TMS publishing pipeline");
    }

    // ---- the template publishes nothing of its own ------------------------

    @Test
    void theTemplateHoldsNoLiteralPercentage() {
        assertThat(scriptOf(template)).doesNotContainPattern("['\"`]\\s*\\d+(\\.\\d+)?%");
        assertThat(outsideStyle(template)).doesNotContain("100%");
    }

    @Test
    void theTemplateHoldsNoDefectIdOrHandWrittenVerdict() {
        assertThat(template).doesNotContainPattern("DEF-\\d+");
        assertThat(template).doesNotContain("2m 41s");
        assertThat(template).doesNotContain("Two failures");
        assertThat(template).doesNotContain("Five defects");

        // The verdict sentence used to assert every critical case had passed
        // whatever the statuses said. It has to branch on the failures.
        assertThat(scriptOf(template)).doesNotContain("All ${cr.length} critical");
        assertThat(scriptOf(template)).contains("crf.length");
    }

    @Test
    void everyKeyInReportYamlIsRead(@TempDir Path spec) throws IOException {
        // D-9: four of six keys were read under names the file does not define,
        // and the defaults happened to match, so nothing looked wrong.
        Files.writeString(spec.resolve("report.yaml"), """
                project: "Fixture project"
                module: "Fixture module"
                report_id_prefix: "FIX"
                prepared_by_role: "Fixture preparer"
                reviewed_by_role: "Fixture reviewer"
                approved_by_role: "Fixture approver"
                """);
        Path out = base.resolve("keys");
        Files.createDirectories(out.resolve("cucumber-reports"));
        Files.copy(base.resolve("target/cucumber-reports/cucumber.json"),
                out.resolve("cucumber-reports/cucumber.json"));

        new ReportGenerator(out, spec, Path.of("defects.csv"), out.resolve("history.jsonl"))
                .generate();
        JsonNode report = MAPPER.readTree(out.resolve("report-data.json").toFile()).path("report");

        assertThat(report.path("id").asText()).startsWith("FIX-");
        assertThat(report.path("project").asText()).isEqualTo("Fixture project");
        assertThat(report.path("module").asText()).isEqualTo("Fixture module");
        assertThat(report.path("preparedBy").asText()).isEqualTo("Fixture preparer");
        assertThat(report.path("reviewedBy").asText()).isEqualTo("Fixture reviewer");
        assertThat(report.path("approvedBy").asText()).isEqualTo("Fixture approver");
    }

    @Test
    void theTemplateTakesTheRecommendationFromTheData() {
        // D-2: recomputing it here let the page print "proceed" while
        // ExitCriteria.recommend had computed "hold".
        assertThat(scriptOf(template)).contains("R.report.recommendation");
    }

    @Test
    void theTemplateReadsTheStatusTokenTheGeneratorEmits() {
        // D-4: the generator emitted "skip" and the template filtered on
        // "skipped", so nothing was ever counted as not executed.
        assertThat(RunResult.Status.SKIP.token()).isEqualTo("skipped");
        assertThat(scriptOf(template)).doesNotContain("'skip'").doesNotContain("\"skip\"");
    }

    @Test
    void theTemplateStatesWhatItsCoverageIsCoverageOf() {
        // D-11: a coverage figure with no stated basis reads as code coverage.
        assertThat(template).contains("spec/test-cases.yaml");
        assertThat(template).contains("Scenario coverage");
    }

    @Test
    void everyFigureInTheTemplateComesFromTheData() {
        String script = scriptOf(template);
        assertThat(script).contains("R.metrics");

        // A metric row that does not name a key in R.metrics is a figure with
        // no source. Every key the template names has to exist in the data.
        String table = script.substring(script.indexOf("const rows=[", script.indexOf("function metrics(")));
        table = table.substring(0, table.indexOf("];"));

        List<String> keys = new ArrayList<>();
        Matcher inTable = Pattern.compile("'([a-z][a-z_]+)'").matcher(table);
        while (inTable.find()) {
            keys.add(inTable.group(1));
        }
        Matcher direct = Pattern.compile("\\bm?(?:M|pc|det|val)\\('([a-z][a-z_]+)'").matcher(script);
        while (direct.find()) {
            keys.add(direct.group(1));
        }
        assertThat(keys).isNotEmpty();
        for (String key : keys) {
            assertThat(data.path("metrics").has(key))
                    .describedAs("metric %s is read by the template but never emitted", key)
                    .isTrue();
        }
    }

    @Test
    void theRenderedPageCarriesTheSameDataTheJsonFileDoes() throws IOException {
        assertThat(html).doesNotContain("__REPORT_DATA__");
        String injected = html.substring(html.indexOf("const R = ") + "const R = ".length());
        injected = injected.substring(0, injected.lastIndexOf(";;"));
        assertThat(MAPPER.readTree(injected)).isEqualTo(data);
    }

    @Test
    void everySectionRendersWithAnEmptyHistory() {
        // D-8: trend() dereferenced h[h.length-2] unguarded, threw on a first
        // run, and every section below it stayed blank.
        assertThat(data.path("history")).isEmpty();
        String script = scriptOf(template);
        int trend = script.indexOf("function trend()");
        String body = script.substring(trend, script.indexOf("\n}", trend));
        assertThat(body).contains("h.length<2");
        assertThat(body).doesNotContain("(v-88)/12");
    }

    // ---- helpers ----------------------------------------------------------

    private static long plannedWithPriority(String priority) {
        return new SpecInputs(Path.of("spec")).plannedCases().stream()
                .filter(row -> priority.equalsIgnoreCase(String.valueOf(row.get("priority"))))
                .count();
    }

    private static JsonNode caseById(String id) {
        return list(data.path("cases")).stream()
                .filter(c -> c.path("id").asText().equals(id)).findFirst().orElseThrow();
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    /** The template's own script, with the injected data marker removed. */
    private static String scriptOf(String source) {
        String script = source.substring(source.indexOf("<script>"));
        return script.replace("__REPORT_DATA__", "");
    }

    private static String outsideStyle(String source) {
        return source.replaceAll("(?s)<style>.*?</style>", "");
    }
}
