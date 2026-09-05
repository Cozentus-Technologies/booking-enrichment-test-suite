package com.cozentus.enrichment.tests.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the suite's versioned copy of the service's published JSON Schema
 * contracts (see {@code src/test/resources/contracts}, copied verbatim from
 * the producer per TEST_SUITE_SPEC.md section 2 and the {@code contracts/README.md}
 * in the service repository) by logical schema name and version.
 *
 * <p>Resource naming convention: {@code contracts/<name>-v<version>.schema.json},
 * e.g. {@code contracts/booking-enriched-v1.schema.json} for
 * {@code getSchema("booking-enriched", 1)}. This class works purely on JSON text
 * and Jackson {@link JsonNode} loaded from the classpath — it has no dependency
 * on the system under test, satisfying the suite's black-box rule.
 *
 * <p>Parsed/compiled schemas are cached per (name, version) pair so repeated
 * lookups — for example once per Cucumber scenario — do not re-parse and
 * re-compile the same schema file.
 */
public final class SchemaRepository {

    private static final String RESOURCE_PATH_TEMPLATE = "contracts/%s-v%d.schema.json";

    /**
     * Configures the compiled schema to report failure locations as RFC 6901
     * JSON pointers (e.g. {@code /enrichment/originConfidence}) rather than the
     * library's default JSONPath-flavoured notation, so that
     * {@link SchemaValidator} failure messages name an unambiguous location.
     */
    private static final SchemaValidatorsConfig POINTER_CONFIG =
            SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchemaFactory schemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final Map<String, JsonNode> schemaNodeCache = new ConcurrentHashMap<>();
    private final Map<String, JsonSchema> compiledSchemaCache = new ConcurrentHashMap<>();

    /**
     * Returns the cached, compiled schema for the given logical name and version,
     * compiling and caching it on first use.
     *
     * @param name    logical schema name, e.g. {@code "booking-enriched"}
     * @param version schema version, e.g. {@code 1}
     * @throws SchemaNotFoundException if the corresponding resource is missing or unreadable
     */
    public JsonSchema getSchema(String name, int version) {
        return compiledSchemaCache.computeIfAbsent(
                cacheKey(name, version),
                key -> schemaFactory.getSchema(getSchemaNode(name, version), POINTER_CONFIG));
    }

    /**
     * Returns the raw, parsed schema document for the given logical name and
     * version, e.g. for structural comparison by {@link CompatibilityChecker}.
     *
     * @throws SchemaNotFoundException if the corresponding resource is missing or unreadable
     */
    public JsonNode getSchemaNode(String name, int version) {
        return schemaNodeCache.computeIfAbsent(cacheKey(name, version), key -> readSchemaNode(name, version));
    }

    private JsonNode readSchemaNode(String name, int version) {
        String resourcePath = resourcePath(name, version);
        try (InputStream in = SchemaRepository.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new SchemaNotFoundException(
                        "Schema not found on classpath at \"" + resourcePath + "\" "
                                + "(requested logical schema \"" + name + "\", version " + version + "). "
                                + "Expected the file at src/test/resources/" + resourcePath + ".");
            }
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new SchemaNotFoundException(
                    "Failed to read or parse schema resource \"" + resourcePath + "\": " + e.getMessage(), e);
        }
    }

    private static String resourcePath(String name, int version) {
        return String.format(RESOURCE_PATH_TEMPLATE, name, version);
    }

    private static String cacheKey(String name, int version) {
        return name + "-v" + version;
    }

    /** Thrown when a requested schema resource is missing from the classpath, or fails to parse. */
    public static final class SchemaNotFoundException extends RuntimeException {

        public SchemaNotFoundException(String message) {
            super(message);
        }

        public SchemaNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
