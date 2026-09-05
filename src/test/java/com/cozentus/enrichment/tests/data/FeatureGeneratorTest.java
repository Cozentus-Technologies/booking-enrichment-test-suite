package com.cozentus.enrichment.tests.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The generated feature is the contract the steps are built against. */
class FeatureGeneratorTest {

    private static final List<CityCase> CASES = TestDataLoader.load();
    private static final String FEATURE = FeatureGenerator.render(CASES);

    private static List<String> matches(String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.MULTILINE).matcher(FEATURE);
        return matcher.results().map(r -> r.group(1)).toList();
    }

    @Test
    @DisplayName("one scenario is produced per CSV row, so each is separately reportable")
    void oneScenarioPerRow() {
        assertThat(matches("^  Scenario: (.+)$")).hasSize(CASES.size());
    }

    @Test
    @DisplayName("every row's own tags are carried onto its scenario")
    void tagsAreCarriedThrough() {
        for (CityCase testCase : CASES) {
            assertThat(FEATURE)
                    .as("tags for %s", testCase.caseId())
                    .contains(String.join(" ", testCase.tags()));
        }
    }

    @Test
    @DisplayName("every scenario carries exactly one traceability tag")
    void everyScenarioIsTraceable() {
        List<String> traceability = matches("(@TC-\\d{2})\\s*$");

        assertThat(traceability).hasSize(CASES.size()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an enriched row asserts its expected city; a flagged row asserts a reason")
    void assertionMatchesOutcome() {
        for (CityCase testCase : CASES) {
            if (testCase.isEnriched()) {
                assertThat(FEATURE).as("%s", testCase.caseId())
                        .contains("And its origin is \"" + testCase.expected() + "\"");
            }
        }
        long flaggedRows = CASES.stream().filter(c -> !c.isEnriched()).count();
        assertThat(matches("^    And (the flag reason concerns the origin field)$"))
                .hasSize((int) flaggedRows);
    }

    @Test
    @DisplayName("the sentinels produce distinct Given phrasings, not a literal value")
    void sentinelsBecomeTheirOwnSteps() {
        assertThat(FEATURE)
                .contains("with no origin field and destination")
                .contains("with a null origin and destination")
                .doesNotContain("with origin \"<ABSENT>\"");
    }

    @Test
    @DisplayName("whitespace-only and padded inputs are published verbatim")
    void whitespaceIsPreservedInTheStep() {
        assertThat(FEATURE)
                .contains("with origin \"  Mumbai  \"")
                .contains("with origin \"   \"");
    }

    @Test
    @DisplayName("the file warns that it is generated, so nobody edits it by hand")
    void carriesADoNotEditBanner() {
        assertThat(FEATURE)
                .startsWith("# GENERATED FILE - DO NOT EDIT.")
                .contains("city-cases.csv");
    }

    @Test
    @DisplayName("each row's note becomes the scenario description, so the why survives")
    void notesBecomeDescriptions() {
        CityCase first = CASES.get(0);

        assertThat(FEATURE).contains(first.note());
    }
}
