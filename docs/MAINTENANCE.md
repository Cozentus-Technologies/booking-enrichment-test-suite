# Maintenance Guide

Source: TEST_SUITE_SPEC.md section 3. This table is the argument for the
layered design — each row states what a given kind of change touches, and
just as importantly, what it does not.

## Layers

```
Layer 1   FEATURE FILES        business language, topic names only
             |
Layer 2   STEP DEFINITIONS     translate intent to harness calls
             |
Layer 3   HARNESS + DATA       Kafka mechanics, polling, correlation
             |
Layer 4   CONFIGURATION        broker, topics, timeouts, service control
```

Rules that keep the table below true:

- No Kafka API call appears in a step definition — enforced in review, not
  by the build. A step definition that imports `org.apache.kafka.*` directly
  is a review finding.
- Steps hold no state. All per-scenario state lives in `ScenarioContext`
  (`support/ScenarioContext.java`), injected into steps via Cucumber's
  PicoContainer object scope.
- `harness/` is the only package that knows Kafka exists.
- `config/` (the properties files under `src/test/resources/config/`) is the
  only coupling to a specific deployment of the system under test.

## What to touch for each kind of change

| Change | What is touched |
|---|---|
| New city variant to test | One CSV row in `testdata/city-cases.csv` |
| New reference city | One CSV row |
| Threshold retuned | Boundary rows in `testdata/city-cases.csv`; no scenario changes |
| Topic renamed | `config/test-*.properties` only |
| New payload field | `model/` plus one passthrough scenario |
| New behaviour | A feature file; steps usually already exist |
| Kafka client upgraded | `harness/` only |
| Point at DSV's real service | `config/test-*.properties` only |

## Why this table is the point of the design

Every row resolves to a single layer. That is only true if the rules above
hold — the moment a step definition reaches into Kafka directly, or a
threshold value gets hard-coded into a feature file's step text instead of
read from the CSV, the table stops being accurate and a "one CSV row" change
starts requiring a Java edit and a Gherkin edit in lockstep.

Two consequences worth calling out explicitly:

- **Test data is the single source of truth** (TEST_SUITE_SPEC.md section
  5.1). `testdata/city-cases.csv` drives the Gherkin Examples, the expected
  outcome, and — for the boundary rows — the argument that a threshold is a
  real, deliberate decision rather than an artefact of the algorithm. No
  expected value should ever be written a second time anywhere else; a
  second copy is a drift risk, not a convenience.
- **Layer 4 is the only planned coupling to the system under test.**
  Pointing the suite at a different deployment — a colleague's branch, a
  staging environment, DSV's eventual real service — should be a matter of
  editing `config/test-*.properties` (or adding a `test-<env>.properties`
  and passing `-Dsuite.env=<env>`) and nothing else. If any other layer needs
  to change to point at a different environment, that is itself a defect in
  the suite's layering, not a one-off exception.

## Reviewing a change

When reviewing a pull request against this suite, the fastest correctness
check is: does the diff match the row its intent claims? A PR titled "add
Kraków as an encoding edge case" that also touches `harness/RawPublisher.java`
is worth a second look — either the harness was genuinely missing a
capability (state that in the PR description) or the change crept outside
its layer.
