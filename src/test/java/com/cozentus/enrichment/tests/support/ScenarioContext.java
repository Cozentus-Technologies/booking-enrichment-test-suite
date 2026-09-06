package com.cozentus.enrichment.tests.support;

import com.cozentus.enrichment.tests.harness.ServiceController;
import com.cozentus.enrichment.tests.harness.ServiceHarness;
import com.cozentus.enrichment.tests.model.EnrichedView;
import com.cozentus.enrichment.tests.model.FlaggedView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All per-scenario state. Step classes hold none of their own, so they can be
 * split by concern without any of them owning a fact another needs.
 *
 * <p>A fresh instance per scenario, injected by cucumber-picocontainer.
 */
public class ScenarioContext {

    private TestConfig config;
    private ServiceHarness harness;
    private ServiceController service;
    private String scenarioId;

    /** The booking most steps mean when they say "it". */
    private String currentBookingId;
    private final Map<String, String> publishedPayloads = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> publishedHeaders = new LinkedHashMap<>();
    private final List<String> publishedOrder = new ArrayList<>();

    private EnrichedView enriched;
    private FlaggedView flagged;

    /** Spec 8.11: set when a prerequisite failed, so the scenario could not run. */
    private String blockedCause;

    /** Recorded when an absence assertion was used, so the report can state the window. */
    private String absenceWindowUsed;

    public void bind(TestConfig config, ServiceHarness harness,
                     ServiceController service, String scenarioId) {
        this.config = config;
        this.harness = harness;
        this.service = service;
        this.scenarioId = scenarioId;
    }

    public TestConfig config() {
        return require(config, "configuration");
    }

    public ServiceHarness harness() {
        return require(harness, "harness");
    }

    public ServiceController service() {
        return require(service, "service");
    }

    public String scenarioId() {
        return scenarioId;
    }

    // --- what was published ---------------------------------------------

    public void recordPublished(String bookingId, String payload, Map<String, String> headers) {
        this.currentBookingId = bookingId;
        publishedPayloads.put(bookingId, payload);
        publishedHeaders.put(bookingId, Map.copyOf(headers));
        publishedOrder.add(bookingId);
    }

    public String currentBookingId() {
        return require(currentBookingId, "a published booking");
    }

    public String publishedPayload(String bookingId) {
        return publishedPayloads.get(bookingId);
    }

    public Map<String, String> publishedHeaders(String bookingId) {
        return publishedHeaders.getOrDefault(bookingId, Map.of());
    }

    /** In publication order, which the ordering scenario needs. */
    public List<String> publishedOrder() {
        return List.copyOf(publishedOrder);
    }

    // --- what was observed ----------------------------------------------

    public void observed(EnrichedView view) {
        this.enriched = view;
        this.flagged = null;
    }

    public void observed(FlaggedView view) {
        this.flagged = view;
        this.enriched = null;
    }

    public EnrichedView enriched() {
        return require(enriched, "an enriched message; assert the route before its contents");
    }

    public FlaggedView flagged() {
        return require(flagged, "a flagged message; assert the route before its contents");
    }

    public boolean hasEnriched() {
        return enriched != null;
    }

    /** The raw payload of whichever output message was observed, for report attachment. */
    public String observedPayload() {
        if (enriched != null) {
            return enriched.raw();
        }
        return flagged != null ? flagged.raw() : null;
    }

    public void blocked(String cause) {
        this.blockedCause = cause;
    }

    public String blockedCause() {
        return blockedCause;
    }

    public boolean isBlocked() {
        return blockedCause != null;
    }

    public void absenceWindowUsed(String description) {
        this.absenceWindowUsed = description;
    }

    public String absenceWindowUsed() {
        return absenceWindowUsed;
    }

    /** The observed message's envelope, which spec 8.4 requires in the evidence. */
    public com.cozentus.enrichment.tests.model.ConsumedMessage observedMessage() {
        if (enriched != null) {
            return enriched.message();
        }
        return flagged != null ? flagged.message() : null;
    }

    private static <T> T require(T value, String what) {
        if (value == null) {
            throw new IllegalStateException(
                    "This step needs " + what + ", which no earlier step in this scenario established.");
        }
        return value;
    }
}
