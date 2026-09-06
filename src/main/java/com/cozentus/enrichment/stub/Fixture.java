package com.cozentus.enrichment.stub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the stub emits, stated as data rather than computed.
 *
 * <p>The stub exists to prove the suite can fail, so it must contain no
 * matching logic of its own. If it decided for itself which city an input
 * resolved to, a mutation would have to be expressed as a change to that logic
 * and the stub would slowly become a second implementation of the service -
 * with its own bugs, and no way to tell a suite failure from a stub failure.
 * Every city decision therefore comes from a table of exact input strings.
 *
 * @param name       the fixture's own name, used in log lines and the report
 * @param mappings   consulted in order, first match wins, compared verbatim
 * @param whenMissing what to emit when the field carries no value at all
 * @param fallback   what to emit for an input no mapping names
 * @param behaviour  the switches a mutation fixture flips
 */
public record Fixture(String name,
                      List<Mapping> mappings,
                      Rule whenMissing,
                      Rule fallback,
                      Behaviour behaviour) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Which output topic a decision sends the booking to. */
    public enum Route { ENRICHED, FLAGGED }

    /**
     * Which reference list the scenario started the service against.
     *
     * <p>The suite signals "extended reference list" only by switching
     * {@code --cities.source} from {@code classpath:/cities.json} to an
     * {@code inline:} list. That is the sole observable difference available to
     * the stub, and it has to key on it: {@code "Delh"} must enrich to
     * {@code New Delhi} for TC-12 and be reported ambiguous for TC-46, which
     * are the same input under two different configurations.
     */
    public enum Cities { ANY, DEFAULT, EXTENDED;

        static Cities of(String citiesSource) {
            return citiesSource != null && citiesSource.startsWith("inline:") ? EXTENDED : DEFAULT;
        }

        boolean matches(Cities configured) {
            return this == ANY || this == configured;
        }
    }

    /** One row of the replay table. */
    public record Mapping(String whenCity, Cities whenCities, Rule then) {
    }

    /**
     * The outcome for one city field.
     *
     * @param thenCity       the canonical name to publish, for an enriched decision
     * @param thenConfidence exactly 1.0 for the exact path, below it for a correction;
     *                       the suite asserts only which side of 1.0 the value falls,
     *                       never the value itself, so the numbers here need only be
     *                       plausible members of the contract's [0, 1] interval
     * @param thenReason     the reason stem, without the field suffix the stub appends
     */
    public record Rule(Route thenTopic,
                       String thenCity,
                       Double thenConfidence,
                       String thenReason,
                       List<String> thenCandidates) {
    }

    /**
     * The mutation switches.
     *
     * <p>One field per protected rule, so a mutation fixture is a single line of
     * JSON on top of the baseline and it is impossible to break two things at
     * once by accident - which would make the result unattributable.
     */
    public record Behaviour(boolean silent,
                            String constantKey,
                            Double constantConfidence,
                            boolean reportDestinationUnderOrigin,
                            boolean fabricateMalformedBookingId,
                            boolean normaliseOriginal,
                            boolean reverseSharedKeyOutputs,
                            boolean alsoPublishFlaggedToEnriched,
                            boolean swapOneRoutePair) {

        static Behaviour none() {
            return new Behaviour(false, null, null, false, false, false, false, false, false);
        }
    }

    /** Loads {@code /stub/<name>.json}, resolving {@code extends} first. */
    public static Fixture load(String name) {
        JsonNode node = read(name);
        Fixture base = node.hasNonNull("extends")
                ? load(node.get("extends").asText())
                : new Fixture(name, List.of(), null, null, Behaviour.none());
        return merge(name, base, node);
    }

    private static JsonNode read(String name) {
        String resource = "/stub/" + name + ".json";
        try (InputStream in = Fixture.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("No stub fixture " + resource
                        + " on the classpath. Available fixtures live in src/main/resources/stub.");
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    /**
     * A child's mappings are prepended, not appended: first match wins, so a
     * mutation shadows the baseline row for one input by naming it again rather
     * than by restating the whole table. A restated table would drift from the
     * baseline and the run would then be testing two changes at once.
     */
    private static Fixture merge(String name, Fixture base, JsonNode node) {
        List<Mapping> mappings = new ArrayList<>(readMappings(node.path("mappings")));
        mappings.addAll(base.mappings());

        return new Fixture(name,
                List.copyOf(mappings),
                node.has("whenMissing") ? readRule(node.get("whenMissing")) : base.whenMissing(),
                node.has("default") ? readRule(node.get("default")) : base.fallback(),
                readBehaviour(node.path("behaviour"), base.behaviour()));
    }

    private static List<Mapping> readMappings(JsonNode array) {
        List<Mapping> mappings = new ArrayList<>();
        for (JsonNode row : array) {
            mappings.add(new Mapping(
                    row.path("whenCity").asText(),
                    row.hasNonNull("whenCities")
                            ? Cities.valueOf(row.get("whenCities").asText().toUpperCase(Locale.ROOT))
                            : Cities.ANY,
                    readRule(row)));
        }
        return mappings;
    }

    private static Rule readRule(JsonNode node) {
        List<String> candidates = new ArrayList<>();
        node.path("thenCandidates").forEach(candidate -> candidates.add(candidate.asText()));

        return new Rule(
                Route.valueOf(node.path("thenTopic").asText("flagged").toUpperCase(Locale.ROOT)),
                node.hasNonNull("thenCity") ? node.get("thenCity").asText() : null,
                node.hasNonNull("thenConfidence") ? node.get("thenConfidence").asDouble() : null,
                node.hasNonNull("thenReason") ? node.get("thenReason").asText() : null,
                List.copyOf(candidates));
    }

    /** Absent switches inherit from the parent, so a mutation states only what it breaks. */
    private static Behaviour readBehaviour(JsonNode node, Behaviour inherited) {
        return new Behaviour(
                flag(node, "silent", inherited.silent()),
                node.has("constantKey")
                        ? (node.get("constantKey").isNull() ? null : node.get("constantKey").asText())
                        : inherited.constantKey(),
                node.has("constantConfidence")
                        ? (node.get("constantConfidence").isNull()
                                ? null : node.get("constantConfidence").asDouble())
                        : inherited.constantConfidence(),
                flag(node, "reportDestinationUnderOrigin", inherited.reportDestinationUnderOrigin()),
                flag(node, "fabricateMalformedBookingId", inherited.fabricateMalformedBookingId()),
                flag(node, "normaliseOriginal", inherited.normaliseOriginal()),
                flag(node, "reverseSharedKeyOutputs", inherited.reverseSharedKeyOutputs()),
                flag(node, "alsoPublishFlaggedToEnriched", inherited.alsoPublishFlaggedToEnriched()),
                flag(node, "swapOneRoutePair", inherited.swapOneRoutePair()));
    }

    private static boolean flag(JsonNode node, String field, boolean inherited) {
        return node.has(field) ? node.get(field).asBoolean() : inherited;
    }

    /** The decision for one supplied value, or the fallback when no row names it. */
    public Rule decide(String value, Cities configured) {
        if (value == null || value.isBlank()) {
            // Presence, not matching: the contract defines missing as null, empty
            // or whitespace-only, and none of the three can be written as a
            // lookup key ("absent" has no string form at all).
            return whenMissing;
        }
        for (Mapping mapping : mappings) {
            if (mapping.whenCities().matches(configured) && mapping.whenCity().equals(value)) {
                return mapping.then();
            }
        }
        return fallback;
    }
}
