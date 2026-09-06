package com.cozentus.enrichment.tests.data;

import com.cozentus.enrichment.tests.model.Outcome;
import com.cozentus.enrichment.tests.support.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves capability F-09: the volume generator's matchable and unmatchable
 * pools come from {@code testdata/city-cases.csv} — the same table behind the
 * behavioural scenarios and the oracle — rather than an independently
 * maintained hardcoded list, while keeping its public API and determinism
 * intact.
 */
class VolumeDataGeneratorTest {

    private static final List<CityCase> ENRICHED_AND_FLAGGED_ROWS = TestDataLoader.load();

    // ---- determinism and the existing public contract ----------------------

    @Test
    void sameSeedProducesIdenticalOutput() {
        List<VolumeDataGenerator.Row> first = VolumeDataGenerator.generate(50, 10, 42L);
        List<VolumeDataGenerator.Row> second = VolumeDataGenerator.generate(50, 10, 42L);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void differentSeedsCanProduceDifferentOutput() {
        List<VolumeDataGenerator.Row> first = VolumeDataGenerator.generate(50, 10, 1L);
        List<VolumeDataGenerator.Row> second = VolumeDataGenerator.generate(50, 10, 2L);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void requestedInvalidCountIsHonoured() {
        List<VolumeDataGenerator.Row> rows = VolumeDataGenerator.generate(100, 17, 7L);

        long flaggedCount = rows.stream().filter(VolumeDataGenerator.Row::expectedFlagged).count();

        assertThat(rows).hasSize(100);
        assertThat(flaggedCount).isEqualTo(17);
    }

    @Test
    void invalidGreaterThanTotalStillRejected() {
        assertThatThrownBy(() -> VolumeDataGenerator.generate(5, 6, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- every generated origin traces back to a real CSV row -------------

    @Test
    void everyGeneratedOriginTracesBackToARealCsvRow() {
        Set<String> validEnrichedOrigins = ENRICHED_AND_FLAGGED_ROWS.stream()
                .filter(CityCase::isEnriched)
                .filter(row -> !row.fieldIsAbsent() && !row.fieldIsNull())
                .map(CityCase::input)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        Set<String> validFlaggedOrigins = ENRICHED_AND_FLAGGED_ROWS.stream()
                .filter(row -> row.outcome() == Outcome.FLAGGED)
                .filter(row -> !row.fieldIsAbsent() && !row.fieldIsNull())
                .map(CityCase::input)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());

        List<VolumeDataGenerator.Row> rows = VolumeDataGenerator.generate(200, 40, 99L);

        for (VolumeDataGenerator.Row row : rows) {
            String origin = Json.field(row.payload(), "origin");
            if (row.expectedFlagged()) {
                assertThat(validFlaggedOrigins).as("flagged origin %s traces to a FLAGGED CSV row", origin)
                        .contains(origin);
            } else {
                assertThat(validEnrichedOrigins).as("matched origin %s traces to an ENRICHED CSV row", origin)
                        .contains(origin);
            }
            String destination = Json.field(row.payload(), "destination");
            assertThat(validEnrichedOrigins).as("destination %s traces to an ENRICHED CSV row", destination)
                    .contains(destination);
        }
    }

    // ---- sentinel rows and blanks never surface as a published value ------

    @Test
    void sentinelValuesNeverAppearAsAPublishedOrigin() {
        List<VolumeDataGenerator.Row> rows = VolumeDataGenerator.generate(150, 30, 5L);

        for (VolumeDataGenerator.Row row : rows) {
            String origin = Json.field(row.payload(), "origin");
            assertThat(origin).isNotNull();
            assertThat(origin).isNotEqualTo(CityCase.ABSENT);
            assertThat(origin).isNotEqualTo(CityCase.NULL_LITERAL);
            assertThat(origin).isNotBlank();
        }
    }

    @Test
    void extractPoolSkipsBothSentinelRowsAndBlankInput() {
        List<CityCase> rows = List.of(
                caseRow("A", "<ABSENT>", Outcome.FLAGGED),
                caseRow("B", "null", Outcome.FLAGGED),
                caseRow("C", "   ", Outcome.FLAGGED),
                caseRow("D", "", Outcome.FLAGGED),
                caseRow("E", "Warsaw", Outcome.FLAGGED));

        List<String> pool = VolumeDataGenerator.extractPool(rows, Outcome.FLAGGED);

        assertThat(pool).containsExactly("Warsaw");
    }

    // ---- an empty pool is rejected loudly, not silently defaulted ---------

    @Test
    void emptyEnrichedPoolIsRejectedRatherThanFallingBackSilently() {
        List<CityCase> onlyFlagged = List.of(caseRow("A", "Warsaw", Outcome.FLAGGED));

        assertThatThrownBy(() -> VolumeDataGenerator.extractPool(onlyFlagged, Outcome.ENRICHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENRICHED");
    }

    @Test
    void emptyFlaggedPoolIsRejectedRatherThanFallingBackSilently() {
        List<CityCase> onlyEnriched = List.of(caseRow("A", "Mumbai", Outcome.ENRICHED));

        assertThatThrownBy(() -> VolumeDataGenerator.extractPool(onlyEnriched, Outcome.FLAGGED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLAGGED");
    }

    @Test
    void poolOfOnlySentinelAndBlankRowsIsRejected() {
        List<CityCase> allUnusable = List.of(
                caseRow("A", "<ABSENT>", Outcome.FLAGGED),
                caseRow("B", "null", Outcome.FLAGGED),
                caseRow("C", "   ", Outcome.FLAGGED));

        assertThatThrownBy(() -> VolumeDataGenerator.extractPool(allUnusable, Outcome.FLAGGED))
                .isInstanceOf(IllegalStateException.class);
    }

    private static CityCase caseRow(String caseId, String input, Outcome outcome) {
        return new CityCase(caseId, input, "", outcome, "", List.of("@fast"), "");
    }
}
