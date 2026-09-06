package com.cozentus.enrichment.tests.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.cozentus.enrichment.tests.model.CityField;
import com.cozentus.enrichment.tests.model.ConfidencePath;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
        List<String> traceability = matches("(@TC-\\d{2,3})\\s*$");

        assertThat(traceability).hasSize(CASES.size()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an enriched row asserts its expected city; a flagged row asserts the exact reason")
    void assertionMatchesOutcome() {
        for (CityCase testCase : CASES) {
            String field = testCase.field().lowerName();
            if (testCase.isEnriched()) {
                assertThat(FEATURE).as("%s", testCase.caseId())
                        .contains("And its " + field + " is \"" + testCase.expected() + "\"");
            } else {
                assertThat(FEATURE).as("%s", testCase.caseId())
                        .contains("And the reason is \"" + testCase.reason().trim()
                                + testCase.field().reasonSuffix() + "\"");
            }
        }
    }

    @Test
    @DisplayName("the generated steps use the same wording as the hand-written features")
    void vocabularyMatchesTheHandWrittenFeatures() {
        // Divergent wording for identical intent doubles the number of step
        // definitions someone has to write and keep working. These four
        // phrasings are the ones shared with the hand-authored files.
        assertThat(FEATURE)
                .contains("When it is published to the raw topic")
                .contains("Then it lands on the enriched topic")
                .contains("Then it lands on the flagged topic")
                .contains("And the reason is \"");

        assertThat(FEATURE)
                .doesNotContain("When the booking is published to the raw topic")
                .doesNotContain("lands on the \"booking.")
                .doesNotContain("the flag reason concerns");
    }

    @Test
    @DisplayName("the sentinels produce distinct Given phrasings, not a literal value")
    void sentinelsBecomeTheirOwnSteps() {
        assertThat(FEATURE)
                .contains("with no origin field and destination")
                .contains("with a null origin and destination")
                .contains("with no destination field and origin")
                .contains("with a null destination and origin")
                .doesNotContain("with origin \"<ABSENT>\"")
                .doesNotContain("with destination \"<ABSENT>\"");
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

    @Test
    @DisplayName("B-1: every case is mirrored onto the destination field")
    void bothFieldsAreCovered() {
        Map<CityField, Long> byField = CASES.stream()
                .collect(Collectors.groupingBy(CityCase::field, Collectors.counting()));

        assertThat(byField)
                .as("a rule proven only on origin is not proven on destination")
                .containsEntry(CityField.ORIGIN, byField.get(CityField.DESTINATION));
        assertThat(matches("^  Scenario: (.+)$")).hasSize(CASES.size());
    }

    @Test
    @DisplayName("B-1: each outcome class is exercised on both fields")
    void everyOutcomeClassOnBothFields() {
        for (CityField field : CityField.values()) {
            List<String> classes = CASES.stream()
                    .filter(c -> c.field() == field)
                    .map(c -> c.outcome() + "/" + c.reason().trim())
                    .distinct()
                    .toList();

            assertThat(classes).as("outcome classes on %s", field)
                    .contains("ENRICHED/", "FLAGGED/UNMATCHED", "FLAGGED/MISSING");
        }
    }

    @Test
    @DisplayName("B-1: the field not under test is pinned to a known-good value")
    void theOtherFieldIsHeldConstant() {
        for (CityCase testCase : CASES) {
            String otherField = testCase.field().other().lowerName();

            assertThat(FeatureGenerator.given(testCase))
                    .as("%s must vary only %s", testCase.caseId(), testCase.field().lowerName())
                    .contains(otherField + " \"Mumbai\"");
        }
    }

    @Test
    @DisplayName("B-5: an exact-path row asserts confidence 1.0, a fuzzy-path row asserts below it")
    void confidencePathIsAsserted() {
        for (CityCase testCase : CASES) {
            if (testCase.confidence() == ConfidencePath.NONE) {
                continue;
            }
            String expected = "    And the %s confidence is %s%n"
                    .formatted(testCase.field().lowerName(),
                            testCase.confidence() == ConfidencePath.EXACT
                                    ? "exactly 1.0" : "below 1.0");

            assertThat(FEATURE).as("%s", testCase.caseId()).contains(expected);
        }
    }


    @Test
    @DisplayName("B-5: every enriched row states which path its value took")
    void everyEnrichedRowDeclaresItsPath() {
        for (CityCase testCase : CASES) {
            if (testCase.isEnriched()) {
                assertThat(testCase.confidence()).as("%s", testCase.caseId())
                        .isNotEqualTo(ConfidencePath.NONE);
            }
        }
    }
}
