package com.cozentus.enrichment.tests.data;

import com.cozentus.enrichment.tests.model.Outcome;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads {@code testdata/city-cases.csv}.
 *
 * <p>The same table drives the generated Gherkin Examples, the volume generator
 * and the expected results, so no expected value is written anywhere else.
 */
public final class TestDataLoader {

    public static final String DEFAULT_RESOURCE = "/testdata/city-cases.csv";

    private TestDataLoader() {
    }

    public static List<CityCase> load() {
        return load(DEFAULT_RESOURCE);
    }

    public static List<CityCase> load(String resource) {
        try (InputStream in = TestDataLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test data resource: " + resource);
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }

    static List<CityCase> parse(BufferedReader reader) throws IOException {
        List<String> header = splitCsv(reader.readLine());
        List<CityCase> cases = new ArrayList<>();

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            List<String> values = splitCsv(line);
            if (values.size() != header.size()) {
                throw new IllegalStateException(
                        "Row has " + values.size() + " columns, header has " + header.size()
                                + ": " + line);
            }
            cases.add(toCase(header, values));
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("Test data table is empty");
        }
        return List.copyOf(cases);
    }

    private static CityCase toCase(List<String> header, List<String> values) {
        String caseId = column(header, values, "caseId");
        String outcome = column(header, values, "outcome");
        List<String> tags = Arrays.stream(column(header, values, "tags").split("\\s+"))
                .filter(tag -> !tag.isBlank())
                .toList();

        if (outcome.isBlank()) {
            throw new IllegalStateException(caseId + " has no outcome");
        }
        if (tags.isEmpty()) {
            throw new IllegalStateException(caseId + " has no tags");
        }
        return new CityCase(caseId,
                column(header, values, "input"),
                column(header, values, "expected"),
                Outcome.valueOf(outcome.trim()),
                column(header, values, "reason"),
                tags,
                column(header, values, "note"));
    }

    private static String column(List<String> header, List<String> values, String name) {
        int index = header.indexOf(name);
        if (index < 0) {
            throw new IllegalStateException("Test data is missing the " + name + " column");
        }
        return values.get(index);
    }

    /**
     * Minimal RFC4180 split. Quoted fields preserve commas and, importantly,
     * leading and trailing spaces — TC-04 and TC-18 depend on that surviving.
     */
    static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
