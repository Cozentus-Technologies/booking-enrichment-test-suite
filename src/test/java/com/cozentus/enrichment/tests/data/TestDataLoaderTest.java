package com.cozentus.enrichment.tests.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cozentus.enrichment.tests.model.Outcome;
import java.io.BufferedReader;
import java.io.StringReader;
import com.cozentus.enrichment.tests.model.CityField;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestDataLoaderTest {

    @Test
    @DisplayName("the committed table loads with every row populated")
    void committedTableLoads() {
        List<CityCase> cases = TestDataLoader.load();

        // Not a literal count: every legitimate row added to the table would
        // otherwise fail here for no reason. What must hold is the shape - the
        // table is non-empty and evenly split between the two fields.
        assertThat(cases).isNotEmpty();
        assertThat(cases.stream().filter(c -> c.field() == CityField.ORIGIN).count())
                .isEqualTo(cases.stream().filter(c -> c.field() == CityField.DESTINATION).count());
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.caseId()).matches("TC-\\d{2,3}");
            assertThat(c.field()).isNotNull();
            assertThat(c.tags()).isNotEmpty();
            assertThat(c.note()).isNotBlank();
        });
    }

    @Test
    @DisplayName("quoted fields keep leading and trailing spaces, which TC-04 depends on")
    void quotedWhitespaceSurvives() {
        CityCase tc04 = TestDataLoader.load().stream()
                .filter(c -> c.caseId().equals("TC-04")).findFirst().orElseThrow();

        assertThat(tc04.input()).isEqualTo("  Mumbai  ");
        assertThat(tc04.input()).startsWith(" ").endsWith(" ");
    }

    @Test
    @DisplayName("an enriched row always names its expected city; a flagged row never does")
    void expectedValuePresenceMatchesOutcome() {
        for (CityCase c : TestDataLoader.load()) {
            if (c.outcome() == Outcome.ENRICHED) {
                assertThat(c.expected()).as("%s expected", c.caseId()).isNotBlank();
            } else {
                assertThat(c.expected()).as("%s expected", c.caseId()).isBlank();
                assertThat(c.reason()).as("%s reason", c.caseId()).isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("the two sentinels are distinguished from ordinary values")
    void sentinelsAreRecognised() {
        List<CityCase> cases = TestDataLoader.load();
        CityCase absent = cases.stream().filter(c -> c.caseId().equals("TC-19")).findFirst().orElseThrow();
        CityCase nul = cases.stream().filter(c -> c.caseId().equals("TC-20")).findFirst().orElseThrow();

        assertThat(absent.fieldIsAbsent()).isTrue();
        assertThat(absent.fieldIsNull()).isFalse();
        assertThat(absent.publishedValue()).isNull();

        assertThat(nul.fieldIsNull()).isTrue();
        assertThat(nul.fieldIsAbsent()).isFalse();
        assertThat(nul.publishedValue()).isNull();

        // An ordinary blank is neither sentinel.
        CityCase blank = cases.stream().filter(c -> c.caseId().equals("TC-17")).findFirst().orElseThrow();
        assertThat(blank.fieldIsAbsent()).isFalse();
        assertThat(blank.fieldIsNull()).isFalse();
    }

    @Test
    @DisplayName("every case id is unique")
    void caseIdsAreUnique() {
        List<String> ids = TestDataLoader.load().stream().map(CityCase::caseId).toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a row with the wrong column count is rejected by row, not silently skipped")
    void malformedRowIsRejected() {
        String csv = """
                caseId,field,input,expected,outcome,reason,confidence,tags,note
                TC-01,origin,Mumbai,Mumbai,ENRICHED,,exact,@functional @critical @city-correction @TC-01,note
                TC-02,origin,Mumbai,Mumbai,ENRICHED
                """;

        assertThatThrownBy(() -> TestDataLoader.parse(new BufferedReader(new StringReader(csv))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("columns");
    }

    @Test
    @DisplayName("a row with no tags is rejected, since an untagged case cannot be traced")
    void untaggedRowIsRejected() {
        String csv = """
                caseId,field,input,expected,outcome,reason,confidence,tags,note
                TC-01,origin,Mumbai,Mumbai,ENRICHED,,exact,,note
                """;

        assertThatThrownBy(() -> TestDataLoader.parse(new BufferedReader(new StringReader(csv))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TC-01");
    }

    @Test
    @DisplayName("an embedded comma inside quotes does not split the field")
    void embeddedCommaSurvives() {
        assertThat(TestDataLoader.splitCsv("a,\"b,c\",d")).containsExactly("a", "b,c", "d");
    }

    @Test
    @DisplayName("B-1: a row that does not say which field it varies is rejected")
    void fieldlessRowIsRejected() {
        String csv = """
                caseId,field,input,expected,outcome,reason,confidence,tags,note
                TC-01,,Mumbai,Mumbai,ENRICHED,,exact,@functional @critical @fast @TC-01,note
                """;

        assertThatThrownBy(() -> TestDataLoader.parse(new BufferedReader(new StringReader(csv))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("which field");
    }

    @Test
    @DisplayName("B-1: every origin case has a destination mirror carrying the same input")
    void everyCaseIsMirrored() {
        List<CityCase> cases = TestDataLoader.load();

        Map<String, CityCase> byId = cases.stream()
                .collect(Collectors.toMap(CityCase::caseId, c -> c));

        cases.stream().filter(c -> c.field() == CityField.ORIGIN).forEach(origin -> {
            int n = Integer.parseInt(origin.caseId().substring(3));
            CityCase mirror = byId.get("TC-%d".formatted(100 + n));

            assertThat(mirror).as("destination mirror of %s", origin.caseId()).isNotNull();
            assertThat(mirror.field()).isEqualTo(CityField.DESTINATION);
            assertThat(mirror.input()).isEqualTo(origin.input());
            assertThat(mirror.expected()).isEqualTo(origin.expected());
            assertThat(mirror.outcome()).isEqualTo(origin.outcome());
            assertThat(mirror.reason()).isEqualTo(origin.reason());
        });
    }
}
