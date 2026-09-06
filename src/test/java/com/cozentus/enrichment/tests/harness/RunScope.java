package com.cozentus.enrichment.tests.harness;

import com.cozentus.enrichment.tests.model.ConsumedMessage;
import com.cozentus.enrichment.tests.support.TestConfig;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The slice of a shared topic one run owns (E-1).
 *
 * <p>On {@code local} and {@code ci} the suite provisions a topic per scenario,
 * so everything on it is this run's by construction and this class is the
 * identity function. Against a deployment the suite did not provision there are
 * three long-lived topics carrying whatever else that deployment is doing:
 * another engineer's run, a previous run of this one, or real traffic. A
 * scenario that correlated on {@code BKG-3001} would find someone else's
 * {@code BKG-3001} and assert against it.
 *
 * <p>So every booking id this run publishes is qualified with a prefix unique to
 * the run, in the key and in the payload, and the qualification is removed again
 * on the way back. That keeps the namespace entirely inside the harness: step
 * definitions and feature files go on using the ids they wrote, and nothing
 * above {@link ServiceHarness} has to know which profile it is running under.
 */
public final class RunScope {

    /**
     * One per JVM, not one per scenario. Two scenarios in the same run may
     * legitimately publish the same booking id (TC-53 publishes one twice), and
     * a per-scenario namespace would make those look like different bookings.
     */
    private static final RunScope THIS_RUN = unique();

    private final String prefix;

    private RunScope(String prefix) {
        this.prefix = prefix;
    }

    /** The profile owns its topics, so no namespace is needed and none is added. */
    public static RunScope unscoped() {
        return new RunScope("");
    }

    /** A fresh namespace. Package callers want {@link #forEnvironment}; tests want this. */
    public static RunScope unique() {
        return new RunScope("RUN-" + UUID.randomUUID().toString().substring(0, 8) + "-");
    }

    public static RunScope forEnvironment(TestConfig config) {
        return config.isExternal() ? THIS_RUN : unscoped();
    }

    static RunScope withPrefix(String prefix) {
        return new RunScope(prefix);
    }

    public String prefix() {
        return prefix;
    }

    /** @return the booking id as it goes onto the wire */
    public String qualify(String bookingId) {
        return bookingId == null ? null : prefix + bookingId;
    }

    /**
     * Rewrites the booking id inside a payload to match its key.
     *
     * <p>Textual rather than JSON-aware on purpose: half the resilience
     * scenarios publish payloads Jackson cannot parse - truncated, an array
     * where an object belongs - and those need a run-scoped id every bit as
     * much as the well-formed ones do.
     *
     * <p>ISO-8859-1 is a byte-for-byte bijection, so a payload that is not text
     * at all comes back unchanged rather than mangled into replacement
     * characters, and the ASCII id is still found and replaced when it is there.
     */
    public byte[] qualifyPayload(String bookingId, byte[] payload) {
        if (prefix.isEmpty() || bookingId == null || payload == null) {
            return payload;
        }
        String text = new String(payload, StandardCharsets.ISO_8859_1);
        return text.replace(bookingId, prefix + bookingId).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Whether this run published the booking the message concerns.
     *
     * <p>The key answers it for everything the service routes normally. TC-52
     * publishes without a key and the service keys the result {@code UNKNOWN},
     * which says nothing about whose it is - the qualified id it echoes back in
     * the payload does.
     */
    public boolean published(ConsumedMessage message) {
        if (prefix.isEmpty()) {
            return true;
        }
        return (message.key() != null && message.key().startsWith(prefix))
                || (message.payload() != null && message.payload().contains(prefix));
    }

    /** Undoes {@link #qualify}, so a step definition sees the id its feature file wrote. */
    public ConsumedMessage localise(ConsumedMessage message) {
        if (prefix.isEmpty()) {
            return message;
        }
        return new ConsumedMessage(message.topic(),
                message.key() == null ? null : message.key().replace(prefix, ""),
                message.payload() == null ? null : message.payload().replace(prefix, ""),
                message.partition(), message.offset(), message.timestamp(), message.headers());
    }
}
