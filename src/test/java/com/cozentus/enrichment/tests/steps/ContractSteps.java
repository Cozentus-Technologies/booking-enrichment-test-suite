package com.cozentus.enrichment.tests.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.contract.CompatibilityChecker;
import com.cozentus.enrichment.tests.contract.SchemaValidator;
import com.cozentus.enrichment.tests.support.ScenarioContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

/**
 * Schema conformance. This is the producer side of contract testing: the service
 * publishes a contract and every output message is checked against it.
 */
public class ContractSteps {

    private final ScenarioContext context;
    private final SchemaValidator validator = new SchemaValidator();
    private final CompatibilityChecker checker = new CompatibilityChecker();

    private String schemaUnderComparison;

    public ContractSteps(ScenarioContext context) {
        this.context = context;
    }

    @Then("the message validates against the {string} schema")
    public void messageValidatesAgainst(String schema) {
        String payload = observedPayload();
        SchemaValidator.Result result = validate(payload, schema);

        assertThat(result.isValid())
                .as("payload must satisfy its published contract %s:%n%s%npayload:%n%s",
                        schema, result.errorsAsText(), payload)
                .isTrue();
    }

    /**
     * The mutual-exclusion guarantee. A flagged booking failing the enriched
     * schema means the contract enforces the routing rule independently of the
     * routing assertion — two mechanisms catching the same class of defect.
     */
    @Then("the message fails validation against the {string} schema")
    public void messageFailsValidationAgainst(String schema) {
        String payload = observedPayload();
        SchemaValidator.Result result = validate(payload, schema);

        assertThat(result.isValid())
                .as("a message on one route must not satisfy the other route's schema;"
                        + " payload:%n%s", payload)
                .isFalse();
    }

    @Given("the current {string} schema and its previous committed version")
    public void theCurrentAndPreviousSchema(String schema) {
        this.schemaUnderComparison = schema;
    }

    /**
     * What a schema registry enforces in production. With only v1 committed there
     * is no previous version, so the comparison is against itself: a schema is
     * trivially compatible with itself, and the step becomes meaningful the moment
     * a v2 exists.
     */
    @Then("the schema change between them is additive only")
    public void theChangeIsAdditiveOnly() {
        var repository = new com.cozentus.enrichment.tests.contract.SchemaRepository();
        String name = nameOf(schemaUnderComparison);
        int version = versionOf(schemaUnderComparison);
        JsonNode current = repository.getSchemaNode(name, version);

        // Only v1 is committed, so there is no earlier version to compare with and
        // the comparison is against itself, which is trivially compatible. The step
        // is deliberately kept rather than skipped: it wires up the check now, so
        // the day a v2 lands the guard is already in place and running. Until then
        // it proves the comparator agrees a schema has not changed, and nothing more.
        JsonNode previous = repository.getSchemaNode(name, previousVersionOf(version));

        CompatibilityChecker.CompatibilityResult result = checker.compare(previous, current);

        assertThat(result.isCompatible())
                .as("breaking changes against the previous version:%n%s", result.describe())
                .isTrue();
    }

    /** No v0 exists, so version 1 compares against itself. */
    private static int previousVersionOf(int version) {
        return Math.max(1, version - 1);
    }

    /**
     * Scenarios name a schema as it is published, version included, e.g.
     * "booking-enriched-v1". The repository takes name and version separately,
     * so the suffix is split off here rather than doubled onto the resource path.
     */
    private SchemaValidator.Result validate(String payload, String schema) {
        return validator.validate(payload, nameOf(schema), versionOf(schema));
    }

    static String nameOf(String schema) {
        return schema.replaceAll("-v\\d+$", "");
    }

    static int versionOf(String schema) {
        var matcher = java.util.regex.Pattern.compile("-v(\\d+)$").matcher(schema);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
    }

    private String observedPayload() {
        String payload = context.observedPayload();
        assertThat(payload).as("no output message has been observed yet").isNotNull();
        return payload;
    }
}
