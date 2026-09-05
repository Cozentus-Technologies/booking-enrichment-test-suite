package com.cozentus.enrichment.tests.model;

import java.time.Instant;
import java.util.Map;

/**
 * A message as the suite observed it. Deliberately the suite's own type, not the
 * service's: sharing a model class would be a compile dependency on the system
 * under test and would let a field rename pass unnoticed on both sides at once.
 */
public record ConsumedMessage(String topic,
                              String key,
                              String payload,
                              int partition,
                              long offset,
                              Instant timestamp,
                              Map<String, String> headers) {

    public ConsumedMessage {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public String header(String name) {
        return headers.get(name);
    }
}
