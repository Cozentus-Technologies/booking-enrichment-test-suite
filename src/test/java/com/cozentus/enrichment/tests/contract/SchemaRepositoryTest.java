package com.cozentus.enrichment.tests.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves {@link SchemaRepository} loads, parses and caches the versioned contract schemas. */
class SchemaRepositoryTest {

    private final SchemaRepository repository = new SchemaRepository();

    @Test
    void loadsBookingEnrichedV1() {
        JsonSchema schema = repository.getSchema("booking-enriched", 1);
        assertThat(schema).isNotNull();
    }

    @Test
    void loadsBookingFlaggedV1() {
        JsonSchema schema = repository.getSchema("booking-flagged", 1);
        assertThat(schema).isNotNull();
    }

    @Test
    void loadsBookingRawV1() {
        JsonSchema schema = repository.getSchema("booking-raw", 1);
        assertThat(schema).isNotNull();
    }

    @Test
    void schemaNodeReflectsTheActualContractFile() {
        JsonNode node = repository.getSchemaNode("booking-enriched", 1);
        assertThat(node.path("title").asText()).isEqualTo("Booking (enriched) v1");
        assertThat(node.path("required").toString()).contains("enrichment");
    }

    @Test
    void compiledSchemaIsCachedAcrossCalls() {
        JsonSchema first = repository.getSchema("booking-flagged", 1);
        JsonSchema second = repository.getSchema("booking-flagged", 1);
        assertThat(first).isSameAs(second);
    }

    @Test
    void schemaNodeIsCachedAcrossCalls() {
        JsonNode first = repository.getSchemaNode("booking-raw", 1);
        JsonNode second = repository.getSchemaNode("booking-raw", 1);
        assertThat(first).isSameAs(second);
    }

    @Test
    void missingSchemaFailsLoudlyWithTheResourcePath() {
        assertThatThrownBy(() -> repository.getSchema("booking-nonexistent", 7))
                .isInstanceOf(SchemaRepository.SchemaNotFoundException.class)
                .hasMessageContaining("contracts/booking-nonexistent-v7.schema.json")
                .hasMessageContaining("booking-nonexistent")
                .hasMessageContaining("7");
    }

    @Test
    void missingVersionOfAnExistingSchemaFailsLoudlyToo() {
        assertThatThrownBy(() -> repository.getSchema("booking-enriched", 99))
                .isInstanceOf(SchemaRepository.SchemaNotFoundException.class)
                .hasMessageContaining("contracts/booking-enriched-v99.schema.json");
    }
}
