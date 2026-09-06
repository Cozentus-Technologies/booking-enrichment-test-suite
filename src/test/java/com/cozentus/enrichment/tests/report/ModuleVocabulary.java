package com.cozentus.enrichment.tests.report;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D-10. One name per module, taken from {@code spec/test-cases.yaml}.
 *
 * <p>Three vocabularies were in use at once: the catalogue's ("Booking level",
 * "Schema contract"), the feature tags' ("flagging", "schema"), and the defect
 * register's suite layering ("Harness", "Step definitions"). Defect density
 * joined results to defects by case-insensitive equality across all three, so
 * only "Message contract" ever matched and nine of eleven defects - including
 * the single open one - contributed to no module at all.
 *
 * <p>The catalogue wins because it is the only one of the three that covers
 * every planned case and is already the report's denominator everywhere else.
 */
public final class ModuleVocabulary {

    /**
     * Tag-derived names that have no catalogue row of their own, mapped to the
     * catalogue module whose feature file carries those scenarios. Only reached
     * for a case absent from the catalogue; PlannedSetGuard makes that a build
     * failure rather than a silent mismatch.
     */
    private static final Map<String, String> TAG_ALIASES = Map.of(
            "schema", "Schema contract",
            "flagging", "Routing",
            "passthrough", "Booking level",
            "encoding", "Edge cases",
            "dates", "Edge cases");

    private final Set<String> catalogue;
    private final Map<String, PlannedCase> plan;

    public ModuleVocabulary(List<PlannedCase> planned) {
        this.plan = PlannedCase.byId(planned);
        Set<String> names = new LinkedHashSet<>();
        planned.forEach(c -> names.add(c.module()));
        this.catalogue = Set.copyOf(names);
    }

    public Set<String> catalogue() {
        return catalogue;
    }

    /** The catalogue's own name for an executed case, falling back to its tags. */
    public String of(RunResult result) {
        PlannedCase planned = plan.get(result.caseId());
        return planned != null && !planned.module().isBlank()
                ? planned.module()
                : canonical(result.module());
    }

    /**
     * The module a defect counts against. The case that found it is the
     * strongest link there is - it names a catalogue row directly - so it is
     * preferred over the register's own free-text column.
     */
    public String of(Defects.Defect defect) {
        PlannedCase planned = plan.get(defect.foundBy());
        return planned != null && !planned.module().isBlank()
                ? planned.module()
                : canonical(defect.module());
    }

    /**
     * A name in the catalogue's vocabulary, or the original when nothing maps.
     * Suite-layer modules (CI, Build, Harness) deliberately stay as they are:
     * the catalogue has no row for them and inventing one would attribute a
     * build defect to a feature area that never had it.
     */
    public String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Unassigned";
        }
        for (String known : catalogue) {
            if (known.equalsIgnoreCase(raw)) {
                return known;
            }
        }
        String aliased = TAG_ALIASES.get(raw.toLowerCase());
        return aliased != null && catalogue.contains(aliased) ? aliased : raw;
    }

    /** Executed results restated in the catalogue's vocabulary. */
    public List<RunResult> applyTo(List<RunResult> results) {
        return results.stream().map(r -> r.inModule(of(r))).toList();
    }

    /** Defect counts per catalogue module, for defect density. */
    public Map<String, Long> defectsByModule(Defects defects) {
        Map<String, Long> counted = new LinkedHashMap<>();
        if (defects == null) {
            return counted;
        }
        defects.all().forEach(d -> counted.merge(of(d), 1L, Long::sum));
        return counted;
    }
}
