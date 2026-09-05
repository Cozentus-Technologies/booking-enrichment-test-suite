package com.cozentus.enrichment.tests.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a JSON payload string against a named, versioned schema resolved
 * through a {@link SchemaRepository}.
 *
 * <p>This class operates purely on JSON text / Jackson {@link JsonNode} — it
 * never imports a class from the system under test, satisfying the suite's
 * black-box rule (TEST_SUITE_SPEC.md section 2, "Structural rule enforced by
 * the build").
 */
public final class SchemaValidator {

    private final SchemaRepository schemaRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SchemaValidator() {
        this(new SchemaRepository());
    }

    public SchemaValidator(SchemaRepository schemaRepository) {
        this.schemaRepository = schemaRepository;
    }

    /**
     * Validates {@code payloadJson} against the schema named {@code schemaName}
     * at {@code version}.
     *
     * @param payloadJson the raw JSON payload, exactly as it appeared on the wire
     * @param schemaName  logical schema name, e.g. {@code "booking-enriched"}
     * @param version     schema version, e.g. {@code 1}
     * @return a {@link Result} describing whether the payload conforms, and if
     *         not, one human-readable message per violation
     */
    public Result validate(String payloadJson, String schemaName, int version) {
        String schemaId = schemaName + "-v" + version;

        JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException e) {
            return Result.invalid(List.of(
                    "[" + schemaId + "] $ (document root): payload is not well-formed JSON and could not be "
                            + "parsed at all -> " + e.getOriginalMessage()));
        }

        JsonSchema schema = schemaRepository.getSchema(schemaName, version);
        Set<ValidationMessage> messages = schema.validate(payload);

        if (messages.isEmpty()) {
            return Result.valid();
        }

        List<String> humanReadable = messages.stream()
                .map(message -> formatMessage(schemaId, message))
                .sorted()
                .collect(Collectors.toList());

        return Result.invalid(humanReadable);
    }

    /**
     * Formats a single {@link ValidationMessage} as a self-contained line naming
     * the schema, the JSON pointer of the offending location, and what was wrong
     * with it — deliberately verbose enough to paste directly into a defect
     * report without needing to reproduce the failure locally.
     */
    private static String formatMessage(String schemaId, ValidationMessage message) {
        String pointer = (message.getInstanceLocation() == null
                || message.getInstanceLocation().toString().isEmpty())
                ? "/ (document root)"
                : message.getInstanceLocation().toString();
        return "[" + schemaId + "] " + pointer + " -> " + message.getMessage();
    }

    /** Outcome of validating one payload against one named schema. */
    public static final class Result {

        private final boolean valid;
        private final List<String> errors;

        private Result(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors;
        }

        static Result valid() {
            return new Result(true, Collections.emptyList());
        }

        static Result invalid(List<String> errors) {
            return new Result(false, List.copyOf(errors));
        }

        public boolean isValid() {
            return valid;
        }

        /** One human-readable message per schema violation. Empty when {@link #isValid()}. */
        public List<String> getErrors() {
            return errors;
        }

        /** All error messages joined with newlines, convenient for assertion failure text. */
        public String errorsAsText() {
            return String.join(System.lineSeparator(), errors);
        }

        @Override
        public String toString() {
            return valid ? "VALID" : "INVALID:" + System.lineSeparator() + errorsAsText();
        }
    }
}
