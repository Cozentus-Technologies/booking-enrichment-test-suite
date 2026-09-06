package com.cozentus.enrichment.stub;

import com.cozentus.enrichment.stub.Fixture.Cities;
import com.cozentus.enrichment.stub.Fixture.Route;
import com.cozentus.enrichment.stub.Fixture.Rule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns one inbound record into the records the stub publishes.
 *
 * <p>Pure: no Kafka, no clock, no state. Everything that needs to look across
 * records - reordering, the one-off route swap - lives in {@link ContractStub},
 * so this class can be reasoned about one message at a time.
 *
 * <p>The shapes here are taken from the three published schemas in
 * {@code src/test/resources/contracts}, not from the service. That is what
 * makes the baseline fixture green: the suite validates against those schemas,
 * so a stub built to satisfy them satisfies the suite.
 */
public final class StubRouter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Present on every output, per the suite's TC-38. */
    private static final String STATUS_HEADER = "x-enrichment-status";

    /** The flagged schema's fallback when an unparseable message arrived without one. */
    private static final String UNKNOWN_KEY = "UNKNOWN";

    /** The fields the enriched schema permits; anything else is dropped, as TC-43 requires. */
    private static final List<String> PASSTHROUGH_FIELDS = List.of("shipper", "mode", "requestedDate");

    private final Fixture fixture;
    private final Cities cities;

    public StubRouter(Fixture fixture, Cities cities) {
        this.fixture = fixture;
        this.cities = cities;
    }

    /** One record the stub is about to publish. */
    public record Emission(Route route, String key, String payload, Map<String, String> headers) {

        Emission on(Route other) {
            return new Emission(other, key, payload, headers);
        }
    }

    public List<Emission> route(String rawPayload, String inboundKey, Map<String, String> inboundHeaders) {
        if (fixture.behaviour().silent()) {
            return List.of();
        }
        JsonNode booking = parse(rawPayload);
        return booking != null && booking.isObject()
                ? routeBooking((ObjectNode) booking, inboundHeaders)
                : List.of(malformed(rawPayload, inboundKey, inboundHeaders));
    }

    private static JsonNode parse(String rawPayload) {
        try {
            JsonNode node = MAPPER.readTree(rawPayload);
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception neverParsed) {
            return null;
        }
    }

    // --- the two routes -------------------------------------------------

    private List<Emission> routeBooking(ObjectNode booking, Map<String, String> inboundHeaders) {
        Rule origin = fixture.decide(textOrNull(booking, "origin"), cities);
        Rule destination = fixture.decide(textOrNull(booking, "destination"), cities);

        if (origin.thenTopic() == Route.ENRICHED && destination.thenTopic() == Route.ENRICHED) {
            return List.of(new Emission(Route.ENRICHED, key(booking),
                    enrichedPayload(booking, origin, destination), headers(inboundHeaders, "ENRICHED")));
        }

        Emission flagged = new Emission(Route.FLAGGED, key(booking),
                flaggedPayload(booking, origin, destination),
                headers(inboundHeaders, "FLAGGED"));

        if (fixture.behaviour().alsoPublishFlaggedToEnriched()) {
            // The mutation the routing guarantee exists to catch: the booking is
            // correctly flagged and also leaks onto the success topic.
            return List.of(flagged, flagged.on(Route.ENRICHED));
        }
        return List.of(flagged);
    }

    private Emission malformed(String rawPayload, String inboundKey, Map<String, String> inboundHeaders) {
        ObjectNode payload = MAPPER.createObjectNode();

        if (fixture.behaviour().fabricateMalformedBookingId()) {
            payload.put("bookingId", inboundKey);
        } else {
            payload.putNull("bookingId");
        }
        payload.putArray("reasons").add("MALFORMED_MESSAGE");
        payload.putArray("fields");
        payload.put("original", fixture.behaviour().normaliseOriginal()
                ? normalise(rawPayload) : rawPayload);

        // The constant-key mutation applies here too. Keying only the parsed
        // route would leave the malformed route correct, and TC-52 - the one
        // scenario that asserts the UNKNOWN fallback - would stay green against
        // a service that had lost every key.
        return new Emission(Route.FLAGGED,
                constantKeyOr(inboundKey == null ? UNKNOWN_KEY : inboundKey),
                payload.toString(),
                headers(inboundHeaders, "FLAGGED"));
    }

    // --- payload shapes -------------------------------------------------

    private String enrichedPayload(ObjectNode booking, Rule origin, Rule destination) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("bookingId", booking.path("bookingId").deepCopy());
        PASSTHROUGH_FIELDS.stream()
                .filter(booking::has)
                .forEach(field -> payload.set(field, booking.get(field).deepCopy()));
        payload.put("origin", origin.thenCity());
        payload.put("destination", destination.thenCity());

        ObjectNode enrichment = payload.putObject("enrichment");
        enrichment.put("originalOrigin", textOrNull(booking, "origin"));
        enrichment.put("originalDestination", textOrNull(booking, "destination"));
        enrichment.put("originConfidence", confidence(origin));
        enrichment.put("destinationConfidence", confidence(destination));
        return payload.toString();
    }

    private String flaggedPayload(ObjectNode booking, Rule origin, Rule destination) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("bookingId", booking.path("bookingId").deepCopy());

        ArrayNode reasons = payload.putArray("reasons");
        ArrayNode fields = payload.putArray("fields");

        // Origin before destination. A consumer reading reasons[0] to decide
        // which field to route for review gets the wrong field if this order
        // is not fixed, which is why TC-23 and TC-47 assert it.
        appendFailure(reasons, fields, "origin", origin, textOrNull(booking, "origin"));
        appendFailure(reasons, fields, "destination", destination, textOrNull(booking, "destination"));

        payload.set("original", fixture.behaviour().normaliseOriginal()
                ? normalisedCopy(booking) : booking.deepCopy());
        return payload.toString();
    }

    private void appendFailure(ArrayNode reasons, ArrayNode fields,
                               String field, Rule rule, String suppliedValue) {
        if (rule.thenTopic() != Route.FLAGGED) {
            return;
        }
        String reportedField = reportedField(field);
        String reason = rule.thenReason() + "_" + reportedField.toUpperCase(Locale.ROOT) + "_CITY";

        reasons.add(reason);
        ObjectNode entry = fields.addObject();
        entry.put("field", reportedField);
        entry.put("reason", reason);
        entry.put("value", suppliedValue);
        ArrayNode candidates = entry.putArray("candidates");
        rule.thenCandidates().forEach(candidates::add);
    }

    /**
     * The field-swap mutation, one-directional on purpose: a destination failure
     * is reported as an origin one, while origin failures are left alone. A
     * symmetric swap would turn both halves of the mirrored data set red at once
     * and prove nothing about which half the suite actually distinguishes.
     */
    private String reportedField(String field) {
        return fixture.behaviour().reportDestinationUnderOrigin() && "destination".equals(field)
                ? "origin" : field;
    }

    private double confidence(Rule rule) {
        Double constant = fixture.behaviour().constantConfidence();
        return constant != null ? constant : rule.thenConfidence();
    }

    // --- envelope -------------------------------------------------------

    private String key(ObjectNode booking) {
        return constantKeyOr(textOrNull(booking, "bookingId"));
    }

    private String constantKeyOr(String key) {
        String constant = fixture.behaviour().constantKey();
        return constant != null ? constant : key;
    }

    /** Inbound headers are carried through, then the route is stamped on top. */
    private static Map<String, String> headers(Map<String, String> inbound, String status) {
        Map<String, String> headers = new LinkedHashMap<>(inbound);
        headers.put(STATUS_HEADER, status);
        return headers;
    }

    // --- helpers --------------------------------------------------------

    private static String textOrNull(ObjectNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /**
     * What "normalised" means for the mutation that records a cleaned-up copy of
     * the booking instead of the one that arrived. The contract requires
     * {@code original} to be the payload untouched, so any consistent cleanup
     * breaks it; trim, collapse and case-fold is the cleanup a matcher would
     * plausibly apply before storing what it saw.
     */
    private static String normalise(String value) {
        return value == null ? null
                : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static JsonNode normalisedCopy(ObjectNode booking) {
        ObjectNode copy = booking.deepCopy();
        for (String field : List.of("origin", "destination")) {
            String value = textOrNull(booking, field);
            if (value != null) {
                copy.put(field, normalise(value));
            }
        }
        return copy;
    }

    /** Exposed so the process loop can see which switches are set. */
    public Fixture fixture() {
        return fixture;
    }
}
