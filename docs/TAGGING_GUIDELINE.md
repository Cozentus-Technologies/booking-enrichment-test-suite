# Tagging Guideline

Source: TEST_SUITE_SPEC.md section 6. This document restates the rule set the
build enforces; TEST_SUITE_SPEC.md is the source of truth if the two ever
disagree.

## 1. Axes

Every scenario carries:

- Exactly one tag from **Type**
- Exactly one tag from **Priority**
- At least one tag from **Feature area**
- Exactly one tag from **Traceability**

An **Execution profile** tag (`@fast` / `@slow` / `@nightly`) is expected in
practice but is not mechanically checked by CoverageReporter — it drives the
time-budget conversation in TEST_SUITE_SPEC.md section 9.4, not the coverage
reconciliation in section 8.3.

### Type — exactly one

```
@functional  @contract  @resilience  @volume  @smoke  @regression
```

### Feature area — at least one

```
@city-correction  @routing  @flagging  @passthrough
@message-contract  @schema  @encoding  @dates
```

### Priority — exactly one

```
@critical  @high  @medium  @low
```

### Execution profile

```
@fast      under 5s, runs on every commit
@slow      requires broker startup or an extended service instance
@nightly   long-running — the volume suite
```

### Traceability — exactly one

```
@TC-nn
```

`@TC-DRIVEN` is the sole exception: it marks the single Scenario Outline in
`city_correction.feature` whose Examples table is populated from
`testdata/city-cases.csv`, so the individual `TC-01`..`TC-20` traceability
lives in the CSV's own `caseId` column rather than in twenty separate
`@TC-nn` tags on one outline.

## 2. Worked example

```gherkin
@functional @city-correction @critical @fast @TC-06
Scenario: A misspelled origin city is corrected before publishing
```

Read left to right: type, feature area, priority, execution profile,
traceability. Order is a convention for reviewability, not something the
build checks.

## 3. Enforcement

A build step fails the run if any scenario is missing a type, a priority, or
a traceability tag (TEST_SUITE_SPEC.md section 6.4). This is deliberate:

- Untagged scenarios cannot be sliced by `-Dcucumber.filter.tags`
- Untagged scenarios cannot be reconciled by `CoverageReporter` against the
  scenario catalogue in TEST_SUITE_SPEC.md section 7
- Tag discipline decays within a sprint once a gap is allowed to pass —
  the check exists so a missing tag is a build failure today, not a coverage
  gap discovered during a release retro

The enforcement step is a plain JUnit/Cucumber assertion over the parsed
feature files (or over `cucumber.json` after a run), not a separate tool. It
belongs next to `CoverageReporter` in `support/`, since both read the same
tag data for a different purpose — one gates the build, the other reports on
it.

## 4. Filtering examples

```bash
mvn test -Dcucumber.filter.tags="@smoke"
mvn test -Dcucumber.filter.tags="@functional and @critical"
mvn test -Dcucumber.filter.tags="@city-correction"
mvn test -Dcucumber.filter.tags="@contract"
mvn test -Dcucumber.filter.tags="@nightly"
mvn test -Dcucumber.filter.tags="not @slow"
mvn test -Dcucumber.filter.tags="not @quarantine"
```

`run-tests.sh` wraps the five named profiles (`smoke`, `functional`,
`contract`, `full`, `nightly`) from TEST_SUITE_SPEC.md section 9.3; use the
raw `-Dcucumber.filter.tags` expression directly for anything more specific,
such as chasing a single `@TC-nn`.

## 5. Quarantine

A scenario that fails intermittently is tagged `@quarantine`, excluded from
the release gate via `not @quarantine`, and given a named owner and a date in
the scenario's comment. It is never fixed by adding a retry
(TEST_SUITE_SPEC.md section 10.3). `@quarantine` is deliberately not listed
among the axes above — it is an exceptional, temporary marker, not a
dimension every scenario is expected to carry one of.


## Priority tags and Allure severity

The suite's priority axis is `@critical` / `@high` / `@medium` / `@low`. Allure's
severity vocabulary is `blocker` / `critical` / `normal` / `minor` / `trivial`.

Only `critical` appears in both, so **`@critical` is the only priority that
reaches the Allure report as a severity.** The Allure Cucumber adapter derives
severity from tags while the scenario runs and finalises its labels after all
hooks, so a label written from `@Before` or `@After` is discarded — this was
tried and removed rather than left in place not working.

Two consequences worth knowing:

- The Allure report can be filtered by severity for critical scenarios, and not
  for the other three.
- **The by-priority breakdown in `target/scenario-coverage.md` does not depend on
  Allure.** It reads the Cucumber tags directly and covers all four priorities,
  including the critical pass rate that is the release gate. That report, not
  Allure, is the source for the gate.

If full severity mapping is wanted later, the supported route is a second tag per
scenario in Allure's own vocabulary (`@severity=normal`), which doubles the tag
noise on every scenario. That was judged not worth it while the release gate is
already covered.
