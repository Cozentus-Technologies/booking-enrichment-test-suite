package com.cozentus.enrichment.tests.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code cucumber.json}, the machine-readable source for every generated
 * report. Nothing downstream parses console output or HTML (spec 8.3).
 */
public final class CucumberResults {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> MODULES = List.of(
            "city-correction", "routing", "flagging", "passthrough",
            "message-contract", "schema", "encoding", "dates");
    private static final List<String> TYPES = List.of(
            "functional", "contract", "resilience", "volume", "smoke", "regression");
    private static final List<String> PRIORITIES = List.of("critical", "high", "medium", "low");
    private static final List<String> PROFILES = List.of("fast", "slow", "nightly");

    private CucumberResults() {
    }

    public static List<RunResult> read(Path cucumberJson) {
        if (!Files.exists(cucumberJson)) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(cucumberJson, StandardCharsets.UTF_8));
            return parse(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + cucumberJson, e);
        }
    }

    static List<RunResult> parse(JsonNode root) {
        Map<String, RunResult> byCase = new LinkedHashMap<>();

        for (JsonNode feature : root) {
            for (JsonNode element : feature.path("elements")) {
                if (!"scenario".equals(element.path("type").asText())) {
                    continue;
                }
                RunResult result = toResult(element);
                // A Scenario Outline yields one element per Examples row under the
                // same @TC id. Collapse them: the case is the unit the plan counts,
                // and it fails if any of its rows failed.
                byCase.merge(result.caseId(), result, CucumberResults::worst);
            }
        }
        return List.copyOf(byCase.values());
    }

    private static RunResult worst(RunResult a, RunResult b) {
        RunResult failing = a.status() == RunResult.Status.FAIL ? a
                : b.status() == RunResult.Status.FAIL ? b : null;
        double duration = a.durationSeconds() + b.durationSeconds();
        RunResult keep = failing != null ? failing : a;
        return new RunResult(keep.caseId(), keep.title(), keep.module(), keep.type(),
                keep.priority(), keep.profile(), keep.status(), duration,
                keep.failingStep(), keep.failureMessage(), keep.attachments());
    }

    private static RunResult toResult(JsonNode element) {
        List<String> tags = new ArrayList<>();
        element.path("tags").forEach(tag -> tags.add(tag.path("name").asText()));

        String caseId = tags.stream()
                .filter(tag -> tag.matches("@TC-\\d+"))
                .findFirst().map(tag -> tag.substring(1))
                .orElse(element.path("name").asText());

        long nanos = 0;
        RunResult.Status status = RunResult.Status.PASS;
        String failingStep = null;
        String failureMessage = null;
        boolean any = false;

        for (JsonNode step : element.path("steps")) {
            JsonNode result = step.path("result");
            nanos += result.path("duration").asLong(0);
            String stepStatus = result.path("status").asText();
            any = true;
            if ("failed".equals(stepStatus) && status != RunResult.Status.FAIL) {
                status = RunResult.Status.FAIL;
                failingStep = (step.path("keyword").asText("") + step.path("name").asText()).trim();
                failureMessage = firstLine(result.path("error_message").asText(""));
            } else if ("undefined".equals(stepStatus) || "pending".equals(stepStatus)) {
                status = RunResult.Status.FAIL;
                failingStep = step.path("name").asText();
                failureMessage = "Step is " + stepStatus;
            }
        }
        if (!any || allSkipped(element)) {
            status = RunResult.Status.SKIP;
        }

        Map<String, String> attachments = attachments(element);
        // Spec 8.11: cucumber.json has no notion of blocked, so a hook records it
        // as an attachment and the reporter reads it back.
        if (attachments.containsKey("blocked")) {
            status = RunResult.Status.BLOCKED;
        }

        return new RunResult(caseId,
                element.path("name").asText(),
                RunResult.axis(tags, MODULES, "Unassigned"),
                RunResult.axis(tags, TYPES, "Functional"),
                RunResult.axis(tags, PRIORITIES, "Medium"),
                RunResult.axis(tags, PROFILES, "Fast"),
                status,
                Math.round(nanos / 1_000_000.0) / 1000.0,
                failingStep,
                failureMessage,
                attachments);
    }

    private static boolean allSkipped(JsonNode element) {
        for (JsonNode step : element.path("steps")) {
            if (!"skipped".equals(step.path("result").path("status").asText())) {
                return false;
            }
        }
        return true;
    }

    /** Evidence attached by the @After hook (spec 8.4). */
    private static Map<String, String> attachments(JsonNode element) {
        Map<String, String> found = new LinkedHashMap<>();
        for (JsonNode node : element.path("after")) {
            collect(node, found);
        }
        for (JsonNode step : element.path("steps")) {
            collect(step, found);
        }
        return found;
    }

    private static void collect(JsonNode holder, Map<String, String> into) {
        for (JsonNode embedding : holder.path("embeddings")) {
            String name = embedding.path("name").asText("attachment");
            String data = embedding.path("data").asText("");
            try {
                into.putIfAbsent(name, new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException notBase64) {
                into.putIfAbsent(name, data);
            }
        }
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String line = message.lines().findFirst().orElse(message);
        return line.length() > 240 ? line.substring(0, 240) + "…" : line;
    }
}
