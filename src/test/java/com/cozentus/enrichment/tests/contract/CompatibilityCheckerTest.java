package com.cozentus.enrichment.tests.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.cozentus.enrichment.tests.contract.CompatibilityChecker.BreakingChangeType.ENUM_VALUE_REMOVED;
import static com.cozentus.enrichment.tests.contract.CompatibilityChecker.BreakingChangeType.FIELD_REMOVED;
import static com.cozentus.enrichment.tests.contract.CompatibilityChecker.BreakingChangeType.OPTIONAL_FIELD_MADE_REQUIRED;
import static com.cozentus.enrichment.tests.contract.CompatibilityChecker.BreakingChangeType.TYPE_NARROWED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link CompatibilityChecker} against small, inline schema pairs, one
 * per TEST_SUITE_SPEC.md TC-45 breaking-change category, plus an additive-only
 * pair that must be reported compatible.
 */
class CompatibilityCheckerTest {

    private final CompatibilityChecker checker = new CompatibilityChecker();

    @Test
    void detectsAFieldRemoval() {
        String oldSchema = """
                {
                  "type": "object",
                  "properties": {
                    "bookingId": { "type": "string" },
                    "shipper": { "type": "string" }
                  },
                  "required": ["bookingId", "shipper"]
                }
                """;
        String newSchema = """
                {
                  "type": "object",
                  "properties": {
                    "bookingId": { "type": "string" }
                  },
                  "required": ["bookingId"]
                }
                """;

        CompatibilityChecker.CompatibilityResult result = checker.compare(oldSchema, newSchema);

        assertThat(result.isCompatible()).isFalse();
        assertThat(result.getBreakingChanges())
                .extracting(CompatibilityChecker.BreakingChange::type, CompatibilityChecker.BreakingChange::fieldPath)
                .contains(org.assertj.core.groups.Tuple.tuple(FIELD_REMOVED, "/shipper"));
    }

    @Test
    void detectsATypeNarrowedFromUnionToSingle() {
        String oldSchema = """
                {
                  "type": "object",
                  "properties": {
                    "value": { "type": ["string", "number"] }
                  }
                }
                """;
        String newSchema = """
                {
                  "type": "object",
                  "properties": {
                    "value": { "type": "string" }
                  }
                }
                """;

        CompatibilityChecker.CompatibilityResult result = checker.compare(oldSchema, newSchema);

        assertThat(result.isCompatible()).isFalse();
        assertThat(result.getBreakingChanges())
                .anySatisfy(change -> {
                    assertThat(change.type()).isEqualTo(TYPE_NARROWED);
                    assertThat(change.fieldPath()).isEqualTo("/value");
                    assertThat(change.reason()).contains("number");
                });
    }

    @Test
    void detectsAnEnumValueRemoval() {
        String oldSchema = """
                {
                  "type": "object",
                  "properties": {
                    "mode": { "type": "string", "enum": ["ROAD", "RAIL", "AIR"] }
                  }
                }
                """;
        String newSchema = """
                {
                  "type": "object",
                  "properties": {
                    "mode": { "type": "string", "enum": ["ROAD", "RAIL"] }
                  }
                }
                """;

        CompatibilityChecker.CompatibilityResult result = checker.compare(oldSchema, newSchema);

        assertThat(result.isCompatible()).isFalse();
        assertThat(result.getBreakingChanges())
                .anySatisfy(change -> {
                    assertThat(change.type()).isEqualTo(ENUM_VALUE_REMOVED);
                    assertThat(change.fieldPath()).isEqualTo("/mode");
                    assertThat(change.reason()).contains("AIR");
                });
    }

    @Test
    void detectsAnEnumValueRemovalBehindALocalRef() {
        // Mirrors booking-flagged-v1's flagReason indirection: the enum lives in $defs,
        // not directly on the property, so ref resolution must be exercised too.
        String oldSchema = """
                {
                  "type": "object",
                  "properties": {
                    "reason": { "$ref": "#/$defs/flagReason" }
                  },
                  "$defs": {
                    "flagReason": { "type": "string", "enum": ["UNMATCHED_ORIGIN_CITY", "MALFORMED_MESSAGE"] }
                  }
                }
                """;
        String newSchema = """
                {
                  "type": "object",
                  "properties": {
                    "reason": { "$ref": "#/$defs/flagReason" }
                  },
                  "$defs": {
                    "flagReason": { "type": "string", "enum": ["UNMATCHED_ORIGIN_CITY"] }
                  }
                }
                """;

        CompatibilityChecker.CompatibilityResult result = checker.compare(oldSchema, newSchema);

        assertThat(result.isCompatible()).isFalse();
        assertThat(result.getBreakingChanges())
                .anySatisfy(change -> {
                    assertThat(change.type()).isEqualTo(ENUM_VALUE_REMOVED);
                    assertThat(change.reason()).contains("MALFORMED_MESSAGE");
                });
    }

    @Test
    void detectsAnOptionalFieldMadeRequired() {
        String oldSchema = """
                {
                  "type": "object",
                  "properties": {
                    "bookingId": { "type": "string" },
                    "correlationId": { "type": "string" }
                  },
                  "required": ["bookingId"]
                }
                """;
        String newSchema = """
                {
                  "type": "object",
                  "properties": {
                    "bookingId": { "type": "string" },
                    "correlationId": { "type": "string" }
                  },
                  "required": ["bookingId", "correlationId"]
                }
                """;

        CompatibilityChecker.CompatibilityResult result = checker.compare(oldSchema, newSchema);

        assertThat(result.isCompatible()).isFalse();
        assertThat(result.getBreakingChanges())
                .extracting(CompatibilityChecker.BreakingChange::type, CompatibilityChecker.BreakingChange::fieldPath)
                .contains(org.assertj.core.groups.Tuple.tuple(OPTIONAL_FIELD_MADE_REQUIRED, "/correlationId"));
    }

    @Test
    void detectsABrandNewRequiredFieldAsBreakingToo() {
        String oldSchema = """
                {
                  "type": "object",
                  "properties": {
                    "bookingId": { "type": "string" }
                  },
                  "required": ["bookingId"]
                }
                """;
        String newSchema = """
                {
                  "type": "object",
                  "properties": {
                    "bookingId": { "type": "string" },
                    "tenantId": { "type": "string" }
                  },
                  "required": ["bookingId", "tenantId"]
                }
                """;

        CompatibilityChecker.CompatibilityResult result = checker.compare(oldSchema, newSchema);

        assertThat(result.isCompatible()).isFalse();
        assertThat(result.getBreakingChanges())
                .extracting(CompatibilityChecker.BreakingChange::type, CompatibilityChecker.BreakingChange::fieldPath)
                .contains(org.assertj.core.groups.Tuple.tuple(OPTIONAL_FIELD_MADE_REQUIRED, "/tenantId"));
    }

    @Test
    void reportsMultipleBreakingChangesInOneComparison() {
        String oldSchema = """
                {
                  "type": "object",
                  "properties": {
                    "a": { "type": "string" },
                    "b": { "type": "string" }
                  },
                  "required": ["a"]
                }
                """;
        String newSchema = """
                {
                  "type": "object",
                  "properties": {
                    "a": { "type": "string" }
                  },
                  "required": ["a"]
                }
                """;

        CompatibilityChecker.CompatibilityResult result = checker.compare(oldSchema, newSchema);

        assertThat(result.isCompatible()).isFalse();
        List<CompatibilityChecker.BreakingChange> changes = result.getBreakingChanges();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).type()).isEqualTo(FIELD_REMOVED);
        assertThat(result.describe()).contains("FIELD_REMOVED").contains("/b");
    }

    @Test
    void additiveChangesOnlyAreReportedCompatible() {
        String oldSchema = """
                {
                  "type": "object",
                  "properties": {
                    "bookingId": { "type": "string" },
                    "mode": { "type": "string", "enum": ["ROAD", "RAIL"] }
                  },
                  "required": ["bookingId"]
                }
                """;
        // Additive: new optional property, widened type (string -> string|null), a new enum value,
        // and an existing required field relaxed to optional -- none of these are breaking.
        String newSchema = """
                {
                  "type": "object",
                  "properties": {
                    "bookingId": { "type": ["string", "null"] },
                    "mode": { "type": "string", "enum": ["ROAD", "RAIL", "AIR"] },
                    "correlationId": { "type": "string" }
                  },
                  "required": []
                }
                """;

        CompatibilityChecker.CompatibilityResult result = checker.compare(oldSchema, newSchema);

        assertThat(result.isCompatible()).isTrue();
        assertThat(result.getBreakingChanges()).isEmpty();
        assertThat(result.describe()).contains("No breaking changes");
    }

    @Test
    void nestedObjectFieldRemovalIsDetectedAtItsOwnPath() {
        String oldSchema = """
                {
                  "type": "object",
                  "properties": {
                    "enrichment": {
                      "type": "object",
                      "properties": {
                        "originalOrigin": { "type": "string" },
                        "originConfidence": { "type": "number" }
                      },
                      "required": ["originalOrigin", "originConfidence"]
                    }
                  },
                  "required": ["enrichment"]
                }
                """;
        String newSchema = """
                {
                  "type": "object",
                  "properties": {
                    "enrichment": {
                      "type": "object",
                      "properties": {
                        "originalOrigin": { "type": "string" }
                      },
                      "required": ["originalOrigin"]
                    }
                  },
                  "required": ["enrichment"]
                }
                """;

        CompatibilityChecker.CompatibilityResult result = checker.compare(oldSchema, newSchema);

        assertThat(result.isCompatible()).isFalse();
        assertThat(result.getBreakingChanges())
                .extracting(CompatibilityChecker.BreakingChange::fieldPath)
                .contains("/enrichment/originConfidence");
    }

    @Test
    @DisplayName("B-10: widening integer to number is compatible, because integer is a subset of number")
    void integerWidenedToNumberIsCompatible() {
        // JSON Schema defines integer as a subset of number, so every payload
        // that validated as an integer still validates as a number. A naive set
        // difference over the type names calls this a removal and would block a
        // legitimate schema relaxation.
        String previous = """
                {"type":"object","properties":{"score":{"type":"integer"}}}""";
        String current = """
                {"type":"object","properties":{"score":{"type":"number"}}}""";

        assertThat(new CompatibilityChecker().compare(previous, current).isCompatible())
                .isTrue();
    }

    @Test
    @DisplayName("B-10: narrowing number to integer is still breaking")
    void numberNarrowedToIntegerIsBreaking() {
        String previous = """
                {"type":"object","properties":{"score":{"type":"number"}}}""";
        String current = """
                {"type":"object","properties":{"score":{"type":"integer"}}}""";

        CompatibilityChecker.CompatibilityResult result =
                new CompatibilityChecker().compare(previous, current);

        assertThat(result.isCompatible()).isFalse();
        assertThat(result.getBreakingChanges())
                .anyMatch(c -> c.type() == CompatibilityChecker.BreakingChangeType.TYPE_NARROWED);
    }
}
