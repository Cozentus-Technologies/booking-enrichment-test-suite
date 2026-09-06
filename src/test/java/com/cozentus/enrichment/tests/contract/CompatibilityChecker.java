package com.cozentus.enrichment.tests.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares two versions of the same JSON Schema and reports breaking changes,
 * per TEST_SUITE_SPEC.md section 7.5 (TC-45): a field removed, a type narrowed,
 * an enum value removed, or an optional field made required. Purely-additive
 * changes (a new optional property, a widened type, a new enum value, a
 * required field relaxed to optional) are not reported.
 *
 * <p>This is the local stand-in for what a schema registry's compatibility
 * check enforces in production (TEST_SUITE_SPEC.md section 13): it works on
 * the two schema documents alone, with no running service and no registry.
 *
 * <p>Works purely on Jackson {@link JsonNode} — no dependency on the system
 * under test.
 *
 * <h2>Scope and known limitations</h2>
 * <ul>
 *   <li>{@code $ref} pointers of the form {@code #/path/to/def} are resolved
 *       against their own document (old refs against the old document, new
 *       refs against the new document) before comparison, so a change hidden
 *       behind a {@code $defs} indirection (as {@code booking-flagged-v1}'s
 *       {@code flagReason} enum is) is still detected.</li>
 *   <li>{@code properties}, {@code required}, {@code type}, {@code enum} and
 *       array {@code items} are walked recursively. {@code oneOf}/{@code anyOf}/
 *       {@code allOf} branches, {@code patternProperties}, and keyword-level
 *       narrowing that TC-45 does not name (e.g. a tightened {@code minimum}/
 *       {@code maximum}, an added {@code pattern}, or a narrowed {@code format})
 *       are not analysed; a change hiding only inside one of those constructs
 *       would not be reported here.</li>
 * </ul>
 */
public final class CompatibilityChecker {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Parses both documents and compares them. */
    public CompatibilityResult compare(String oldSchemaJson, String newSchemaJson) {
        try {
            JsonNode oldSchema = objectMapper.readTree(oldSchemaJson);
            JsonNode newSchema = objectMapper.readTree(newSchemaJson);
            return compare(oldSchema, newSchema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Could not parse schema JSON for compatibility check: " + e.getOriginalMessage(), e);
        }
    }

    /** Compares two already-parsed schema documents. */
    public CompatibilityResult compare(JsonNode oldSchema, JsonNode newSchema) {
        List<BreakingChange> breakingChanges = new ArrayList<>();
        compareSchemaNode("", oldSchema, oldSchema, newSchema, newSchema, breakingChanges);
        return new CompatibilityResult(breakingChanges);
    }

    /**
     * Compares one schema fragment (a JSON pointer path shared by both
     * versions) and recurses into its properties and array items.
     *
     * @param path         the JSON pointer path of this fragment, shared by both versions
     * @param oldRoot      the full old schema document (for {@code $ref} resolution)
     * @param oldFragment  the old schema fragment at {@code path}
     * @param newRoot      the full new schema document (for {@code $ref} resolution)
     * @param newFragment  the new schema fragment at {@code path}
     */
    private void compareSchemaNode(String path, JsonNode oldRoot, JsonNode oldFragment,
                                    JsonNode newRoot, JsonNode newFragment,
                                    List<BreakingChange> breakingChanges) {
        JsonNode oldResolved = resolveRef(oldRoot, oldFragment);
        JsonNode newResolved = resolveRef(newRoot, newFragment);
        if (isAbsent(oldResolved) || isAbsent(newResolved)) {
            return;
        }

        checkTypeNarrowing(path, oldResolved, newResolved, breakingChanges);
        checkEnumReduction(path, oldResolved, newResolved, breakingChanges);

        JsonNode oldProperties = oldResolved.path("properties");
        JsonNode newProperties = newResolved.path("properties");
        Set<String> oldRequired = toStringSet(oldResolved.path("required"));
        Set<String> newRequired = toStringSet(newResolved.path("required"));

        Iterator<String> oldFieldNames = oldProperties.fieldNames();
        while (oldFieldNames.hasNext()) {
            String field = oldFieldNames.next();
            String fieldPath = path + "/" + field;

            if (!newProperties.has(field)) {
                breakingChanges.add(new BreakingChange(BreakingChangeType.FIELD_REMOVED, fieldPath,
                        "Field \"" + field + "\" existed in the previous schema version and is absent from this one; "
                                + "a consumer reading it will find it missing."));
                continue;
            }

            boolean wasRequired = oldRequired.contains(field);
            boolean isRequiredNow = newRequired.contains(field);
            if (!wasRequired && isRequiredNow) {
                breakingChanges.add(new BreakingChange(BreakingChangeType.OPTIONAL_FIELD_MADE_REQUIRED, fieldPath,
                        "Field \"" + field + "\" was optional in the previous version and is required in this one; "
                                + "a producer that omits it, as it was previously entitled to, now fails validation."));
            }

            compareSchemaNode(fieldPath, oldRoot, oldProperties.get(field), newRoot, newProperties.get(field),
                    breakingChanges);
        }

        // A brand-new property that is immediately required is just as breaking as an
        // existing optional one being tightened: no old producer could ever have supplied it.
        Iterator<String> newFieldNames = newProperties.fieldNames();
        while (newFieldNames.hasNext()) {
            String field = newFieldNames.next();
            if (!oldProperties.has(field) && newRequired.contains(field)) {
                breakingChanges.add(new BreakingChange(BreakingChangeType.OPTIONAL_FIELD_MADE_REQUIRED,
                        path + "/" + field,
                        "Field \"" + field + "\" is new in this version and is required; it did not exist in the "
                                + "previous version, so no existing producer can supply it."));
            }
        }

        if (oldResolved.has("items") && newResolved.has("items")) {
            compareSchemaNode(path + "[]", oldRoot, oldResolved.get("items"), newRoot, newResolved.get("items"),
                    breakingChanges);
        }
    }

    private void checkTypeNarrowing(String path, JsonNode oldSchema, JsonNode newSchema,
                                     List<BreakingChange> breakingChanges) {
        if (!oldSchema.has("type") || !newSchema.has("type")) {
            return;
        }
        Set<String> oldTypes = toTypeSet(oldSchema.get("type"));
        Set<String> newTypes = toTypeSet(newSchema.get("type"));
        Set<String> removedTypes = new LinkedHashSet<>(oldTypes);
        removedTypes.removeAll(newTypes);
        // JSON Schema defines integer as a subset of number, so a field that
        // widens from integer to number still accepts every payload that
        // validated before. A plain set difference over the type names reads
        // that widening as a removal and would block a legitimate relaxation;
        // the reverse, number narrowed to integer, remains breaking.
        if (removedTypes.contains("integer") && newTypes.contains("number")) {
            removedTypes.remove("integer");
        }
        if (!removedTypes.isEmpty()) {
            breakingChanges.add(new BreakingChange(BreakingChangeType.TYPE_NARROWED, path.isEmpty() ? "/" : path,
                    "Allowed type(s) " + removedTypes + " were removed (was " + oldTypes + ", now " + newTypes
                            + "); a payload of a now-disallowed type that previously validated no longer does."));
        }
    }

    private void checkEnumReduction(String path, JsonNode oldSchema, JsonNode newSchema,
                                     List<BreakingChange> breakingChanges) {
        JsonNode oldEnum = oldSchema.get("enum");
        JsonNode newEnum = newSchema.get("enum");
        if (oldEnum == null || newEnum == null) {
            return;
        }
        Set<String> oldValues = toStringSet(oldEnum);
        Set<String> newValues = toStringSet(newEnum);
        Set<String> removedValues = new LinkedHashSet<>(oldValues);
        removedValues.removeAll(newValues);
        if (!removedValues.isEmpty()) {
            breakingChanges.add(new BreakingChange(BreakingChangeType.ENUM_VALUE_REMOVED, path.isEmpty() ? "/" : path,
                    "Enum value(s) " + removedValues + " were removed (was " + oldValues + ", now " + newValues
                            + "); a payload using a removed value that previously validated no longer does."));
        }
    }

    /**
     * Resolves a single-level {@code {"$ref": "#/a/b/c"}} fragment against its
     * own document. Only local ({@code #/...}) refs are supported, which is all
     * the suite's contracts use ({@code booking-flagged-v1}'s {@code flagReason}
     * definition).
     */
    private JsonNode resolveRef(JsonNode root, JsonNode fragment) {
        if (fragment == null || !fragment.isObject() || !fragment.has("$ref")) {
            return fragment;
        }
        String ref = fragment.get("$ref").asText();
        if (!ref.startsWith("#/")) {
            return fragment;
        }
        JsonNode target = root;
        for (String segment : ref.substring(2).split("/")) {
            String unescaped = segment.replace("~1", "/").replace("~0", "~");
            target = target.path(unescaped);
        }
        return target;
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isMissingNode();
    }

    private static Set<String> toStringSet(JsonNode arrayNode) {
        Set<String> values = new LinkedHashSet<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.forEach(node -> values.add(node.asText()));
        }
        return values;
    }

    private static Set<String> toTypeSet(JsonNode typeNode) {
        Set<String> types = new LinkedHashSet<>();
        if (typeNode.isArray()) {
            typeNode.forEach(node -> types.add(node.asText()));
        } else {
            types.add(typeNode.asText());
        }
        return types;
    }

    /** The four TC-45 breaking-change categories this checker detects. */
    public enum BreakingChangeType {
        FIELD_REMOVED,
        TYPE_NARROWED,
        ENUM_VALUE_REMOVED,
        OPTIONAL_FIELD_MADE_REQUIRED
    }

    /** One detected breaking change: its category, the JSON pointer path it was found at, and why it breaks compatibility. */
    public record BreakingChange(BreakingChangeType type, String fieldPath, String reason) {
        @Override
        public String toString() {
            return "[" + type + "] " + fieldPath + ": " + reason;
        }
    }

    /** The outcome of comparing two schema versions. */
    public static final class CompatibilityResult {

        private final List<BreakingChange> breakingChanges;

        CompatibilityResult(List<BreakingChange> breakingChanges) {
            this.breakingChanges = List.copyOf(breakingChanges);
        }

        /** True when no breaking change was detected, i.e. the new version is (as far as this checker can tell) additive-only. */
        public boolean isCompatible() {
            return breakingChanges.isEmpty();
        }

        public List<BreakingChange> getBreakingChanges() {
            return breakingChanges;
        }

        /** One line per breaking change, or a confirmation message when compatible. */
        public String describe() {
            if (isCompatible()) {
                return "No breaking changes detected; the new schema version is additive-only.";
            }
            return breakingChanges.stream().map(BreakingChange::toString).collect(Collectors.joining(System.lineSeparator()));
        }
    }
}
