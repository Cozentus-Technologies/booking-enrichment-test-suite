package com.cozentus.enrichment.tests.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates {@code city_correction.feature} from {@code city-cases.csv}.
 *
 * <p>Cucumber has no native way to load an Examples table from a file, so the
 * single-source-of-truth rule is kept by generating the feature instead. One
 * scenario per row, each carrying that row's own hand-written tags, so every
 * case stays individually taggable, traceable, filterable and reportable — which
 * a single Scenario Outline with twenty Examples rows would not be, since
 * Gherkin cannot tag an individual row.
 *
 * <p>The generated file is written to {@code target/generated-features} and is
 * never committed. Editing it by hand is pointless: the next build overwrites it.
 * Change the CSV.
 */
public final class FeatureGenerator {

    private static final String DEFAULT_OUTPUT =
            "target/generated-features/functional/city_correction.feature";

    private FeatureGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : DEFAULT_OUTPUT);
        int written = generate(TestDataLoader.load(), out);
        System.out.printf("Generated %d scenarios into %s%n", written, out);
    }

    /** @return the number of scenarios written */
    public static int generate(List<CityCase> cases, Path out) {
        String feature = render(cases);
        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, feature, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + out, e);
        }
        return cases.size();
    }

    static String render(List<CityCase> cases) {
        StringBuilder feature = new StringBuilder();
        feature.append("""
                # GENERATED FILE - DO NOT EDIT.
                # Source: src/test/resources/testdata/city-cases.csv
                # Regenerate: mvn generate-test-resources
                #
                # One scenario per CSV row rather than a single Scenario Outline, because
                # Gherkin cannot tag an individual Examples row and every case needs its
                # own @TC-nn for traceability, filtering and the coverage report.

                Feature: A raw city value is corrected or flagged as the contract requires

                  The reference list is the eight canonical cities. Each scenario below is
                  one row of the test data table; the expected outcome is stated there and
                  nowhere else.

                """);

        for (CityCase testCase : cases) {
            feature.append("  ").append(String.join(" ", testCase.tags())).append('\n');
            feature.append("  Scenario: ").append(title(testCase)).append('\n');
            if (!testCase.note().isBlank()) {
                feature.append("    ").append(testCase.note()).append('\n');
            }
            feature.append(given(testCase));
            feature.append("    When it is published to the raw topic\n");

            if (testCase.isEnriched()) {
                feature.append("    Then it lands on the enriched topic\n");
                feature.append("    And its origin is \"").append(testCase.expected()).append("\"\n");
            } else {
                feature.append("    Then it lands on the flagged topic\n");
                feature.append("    And the reason is \"").append(originReason(testCase)).append("\"\n");
            }
            feature.append('\n');
        }
        return feature.toString();
    }

    /**
     * The CSV abbreviates the reason because every row varies the origin; the
     * scenario states the full constant the service actually emits, which is
     * also the vocabulary the hand-written features use.
     */
    private static String originReason(CityCase testCase) {
        return testCase.reason().trim() + "_ORIGIN_CITY";
    }

    /** The Given line, which differs for the two sentinel forms. */
    private static String given(CityCase testCase) {
        if (testCase.fieldIsAbsent()) {
            return "    Given a booking \"%s\" with no origin field and destination \"Mumbai\"%n"
                    .formatted(testCase.caseId());
        }
        if (testCase.fieldIsNull()) {
            return "    Given a booking \"%s\" with a null origin and destination \"Mumbai\"%n"
                    .formatted(testCase.caseId());
        }
        return "    Given a booking \"%s\" with origin \"%s\" and destination \"Mumbai\"%n"
                .formatted(testCase.caseId(), testCase.input());
    }

    private static String title(CityCase testCase) {
        String subject = switch (testCase.input()) {
            case CityCase.ABSENT -> "an absent origin field";
            case CityCase.NULL_LITERAL -> "a null origin";
            default -> testCase.input().isBlank()
                    ? "an origin of " + quoted(testCase.input())
                    : "origin " + quoted(testCase.input());
        };
        return testCase.caseId() + " - " + subject + " is "
                + (testCase.isEnriched() ? "corrected to " + testCase.expected() : "flagged");
    }

    /** Keeps whitespace visible in the scenario title, where it would vanish. */
    private static String quoted(String value) {
        return "\"" + value.replace(" ", "·") + "\"";
    }
}
