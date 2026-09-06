package com.cozentus.enrichment.tests.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds a raw booking payload.
 *
 * <p>Every scenario states only what it varies; everything else takes a default,
 * so a reader can see at a glance what the scenario is actually about.
 *
 * <p>Builds JSON directly rather than serialising a model class, because the
 * suite has to be able to produce payloads a model could not represent — an
 * absent field, an explicit null, an unrecognised extra field.
 */
public final class BookingBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode node = MAPPER.createObjectNode();

    private BookingBuilder(String bookingId) {
        node.put("bookingId", bookingId);
        node.put("shipper", "ABC Logistics");
        node.put("mode", "ROAD");
        node.put("requestedDate", "2026-09-05");
    }

    public static BookingBuilder booking(String bookingId) {
        return new BookingBuilder(bookingId);
    }

    public BookingBuilder origin(String value) {
        node.put("origin", value);
        return this;
    }

    public BookingBuilder destination(String value) {
        node.put("destination", value);
        return this;
    }

    /** An explicit JSON null, which is not the same as the string "null". */
    public BookingBuilder nullOrigin() {
        node.putNull("origin");
        return this;
    }

    /** Omits the field entirely, which is not the same as an empty string. */
    public BookingBuilder withoutOrigin() {
        node.remove("origin");
        return this;
    }

    /** The destination mirror of {@link #nullOrigin()}, for the B-1 cases. */
    public BookingBuilder nullDestination() {
        node.putNull("destination");
        return this;
    }

    /** The destination mirror of {@link #withoutOrigin()}, for the B-1 cases. */
    public BookingBuilder withoutDestination() {
        node.remove("destination");
        return this;
    }

    public BookingBuilder shipper(String value) {
        node.put("shipper", value);
        return this;
    }

    public BookingBuilder mode(String value) {
        node.put("mode", value);
        return this;
    }

    public BookingBuilder requestedDate(String value) {
        node.put("requestedDate", value);
        return this;
    }

    public BookingBuilder extraField(String name, String value) {
        node.put(name, value);
        return this;
    }

    public String build() {
        return node.toString();
    }
}
