# Test Strategy & Suite Design
## City Enrichment Before Publishing to TMS

**Owner:** Test Automation Engineer
**Approach:** Black box, system / end-to-end
**Scope of this document:** what is tested, how it is tested, and what is deliberately excluded

---

## 1. Objective

- Validate that a booking published to `booking.raw` is correctly enriched against the reference city list and routed to the correct downstream topic
- Verify exclusively through the application's external Kafka interface
- Prove behaviour, not implementation

---

## 2. System Under Test

- A minimal reference application was built for this exercise, as no system was supplied
- It consumes `booking.raw`, corrects city names against the reference list, and routes the result
- The test scope was written from the problem statement **before** the implementation, so the suite reflects the contract rather than the code

**Assumptions made explicit (raised by email before the walkthrough):**

- Flagged bookings are routed to a dedicated `booking.flagged` topic rather than carried as a status on `booking.enriched`
- Match confidence is a configurable threshold, treated as a business decision, not a hard-coded constant
- Near-equal candidate matches are rejected rather than resolved by arbitrary tie-break

---

## 3. System Boundary

| Aspect | Definition |
|---|---|
| Entry point | `booking.raw` |
| Observation points | `booking.enriched`, `booking.flagged` |
| Method | Publish in, consume out, assert on the message contract |
| Constraint | No internal Java class is invoked by any test |

---

## 4. Test Scope

### 4.1 In Scope — Functional End-to-End

**City correction (data-driven)**

- Exact match
- Casing variations
- Leading / trailing whitespace
- Typos within tolerance
- Typos beyond tolerance
- City absent from reference list
- Blank value
- Field missing entirely

**Booking-level enrichment behaviour**

- Both cities resolve → ENRICHED
- One city fails → FLAGGED, failing field named
- Both fail → both reasons captured
- Original values preserved alongside corrected values
- Non-city fields pass through unchanged
- Unknown extra field tolerated

**Routing and negative assertion**

- Enriched booking reaches `booking.enriched` only
- Flagged booking reaches `booking.flagged` only
- Flagged booking confirmed **absent** from `booking.enriched` via bounded poll

**Message contract and Kafka semantics**

- Message key preserved from `booking.raw` through to the output topic — downstream partitioning depends on it
- Correlation and trace headers propagated, so a booking can be followed across systems
- Message timestamp preserved or deliberately reset, with the choice stated
- Ordering maintained within a partition for bookings sharing a key
- Consumer offset committed only after successful processing — a failure mid-processing results in redelivery, not silent loss

### 4.2 In Scope — Non-Functional End-to-End

**Resilience**

- Malformed JSON does not halt the consumer; the next valid message still processes
- Duplicate delivery of the same `bookingId` produces an identical outcome with no corruption

**Volume smoke (correctness at scale)**

- 1,000 mixed bookings — all accounted for, correct enriched / flagged split, no message loss
- Framed as correctness at scale, not performance measurement

### 4.3 Out of Scope

| Excluded | Reason |
|---|---|
| Unit tests (matcher, enricher) | White box; developer-owned |
| Component integration, context wiring | White box; developer-owned |
| Load and throughput | Requires dedicated environment — k6 or JMeter Kafka plugin |
| Backpressure and consumer lag | Partitioning and monitoring concern, not suite-level |
| Broker / downstream failure injection | Toxiproxy with Testcontainers |
| Schema evolution | Schema registry with compatibility modes |
| Contract testing against TMS | No TMS contract supplied — Pact or JSON-schema assertion |
| Security, TLS, broker authentication | Environment concern |

---

## 5. Test Approach

### 5.1 BDD for the functional layer

- Cucumber JVM with Gherkin; Scenario Outlines driven by Examples tables
- Feature files readable by a business analyst — scenarios become a shared artefact, not a QA-only one
- Living documentation published from the feature files, so spec, tests and documentation stay in sync by construction

### 5.2 Gherkin for the non-functional tiers too

- Volume and resilience are Gherkin, in `features/nonfunctional/volume.feature` and
  `resilience.feature`, not JUnit. An earlier draft of this document argued the
  opposite and the suite was built the other way; this section records what was
  actually built and why.
- The argument for JUnit was that wrapping a 1,000-message run in business
  language adds ceremony. What decided it against was tagging: the report,
  the traceability matrix and every `-Dcucumber.filter.tags` profile are driven
  from scenario tags, and a JUnit test carries none of them. Splitting the
  suite across two runners would have meant two coverage stories and two ways
  to select a slice.
- Plain JUnit is still used, but for the suite's own helpers - the CSV loader,
  the feature generator, the schema validator, the guards. Those test the
  suite, not the service, so they are not the white-box unit tests section 4.3
  excludes.

### 5.3 Spec-driven generation

- A written specification is the source of truth for scenarios, step definitions and test data
- Scenarios are generated from that spec rather than hand-written case by case
- When the reference list or a rule changes, the spec changes and the suite is regenerated — not fifteen files edited by hand

---

## 6. AI-Assisted Workflow

| Stage | Use | Human ownership |
|---|---|---|
| Scenario derivation | Generate scenarios and edge cases from the spec | Reviewed and pruned before commit |
| Test data generation | Systematic misspelling variants — substitution, transposition, deletion, phonetic, keyboard-adjacency | Threshold-boundary rows chosen deliberately |
| Gap analysis | Analyse the data table and scope for missing edge cases | Judgement on what is in scope vs noise |
| Suite review | Second pass over scenarios, steps and assertions for gaps, redundancy, weak assertions | Final call on what stays |
| Risk analysis | Identify failure modes common to this pattern, then map coverage onto them | Risk model owned by the test engineer |

**Guardrail statement**

- AI drafts; the test engineer reviews and owns the output
- Generated expectations are verified by confirming the test fails when the behaviour is deliberately broken
- No generated case is committed unreviewed

---

## 7. Tagging Guideline

Every scenario carries one tag from each axis.

These axes are enforced by `TagDiscipline`, which fails the build on a scenario
that does not satisfy them. The lists below are the ones it holds; if the two
ever disagree, `TagDiscipline` is the authority and this document is stale.

**By test type** - exactly one

```
@functional  @contract  @resilience  @volume
```

`@smoke` and `@regression` are *not* test types. They were listed here as such,
which made the one-type-tag rule treat them as alternatives to `@functional` -
so no scenario could be both, and `run-tests.sh smoke` selected nothing while
reporting success. They are a separate selection axis, below.

**By selection** - how a run is sliced, orthogonal to what a scenario tests

```
@fast  @slow  @nightly  @smoke  @regression  @quarantine
```

**By feature area** - at least one

```
@city-correction  @routing  @flagging  @passthrough
@message-contract  @schema  @encoding  @dates
```

`@serialisation` was listed here and exists nowhere in the suite; the areas it
was meant to cover are `@schema` and `@encoding`.

**By priority**

```
@critical  @high  @medium  @low
```

**By execution profile**

```
@fast      under 5s, runs on every commit
@slow      requires broker startup
@nightly   long-running — volume suite
```

**By traceability**

```
@TC-04  @REQ-ENRICH-01
```

**Example**

```gherkin
@functional @city-correction @critical @fast @TC-04
Scenario: Misspelled origin is corrected before publishing
```

**Rules**

- Exactly one type tag
- Exactly one priority tag
- At least one feature tag
- Traceability tag mandatory
- Undisciplined tagging rots within a sprint — the rules are enforced in review

**Execution profiles**

```bash
mvn test -Dcucumber.filter.tags="@smoke"
mvn test -Dcucumber.filter.tags="@functional and @critical"
mvn test -Dcucumber.filter.tags="@city-correction"
mvn test -Dcucumber.filter.tags="@nightly"
mvn test -Dcucumber.filter.tags="not @slow"
```

---

## 8. Test Data Strategy

- Test data defined in **one place**, external to code — CSV or JSON, not hard-coded in Java
- The same table drives three consumers: Gherkin Examples, the volume generator, and expected results
- Single source of truth, no drift between layers
- A business analyst can add a city variant without touching code

**Illustrative structure**

| caseId | input | expected | outcome |
|---|---|---|---|
| TC-01 | Mumbai | Mumbai | ENRICHED |
| TC-02 | MUMBAI | Mumbai | ENRICHED |
| TC-03 | `  mumbai  ` | Mumbai | ENRICHED |
| TC-04 | Mumbi | Mumbai | ENRICHED |
| TC-05 | now delhi | New Delhi | ENRICHED |
| TC-06 | Bangalor | Bangalore | ENRICHED |
| TC-07 | Warsaw | — | FLAGGED |
| TC-08 | Mumbaiii | — | FLAGGED |
| TC-09 | `""` | — | FLAGGED |
| TC-10 | *(field absent)* | — | FLAGGED |

- Rows TC-04 and TC-08 sit deliberately on either side of the confidence threshold — together they prove the threshold is a real decision, not an arbitrary number

---

## 9. Test Design Principles

- Poll with timeout — no fixed sleeps anywhere in the suite
- Unique topic names or consumer groups per test for isolation
- Results correlated by `bookingId`, never by arrival order — partitioned delivery is unordered by design
- Field-level assertions, not whole-payload string comparison
- Test data builders so each scenario states only what it varies
- A single reusable Kafka harness — publish, poll-until, drain, assert-empty
- Deterministic, order-independent, re-runnable via a single command

---

## 10. Reporting

- Allure or Cucumber HTML report, generated on every run
- Actual Kafka message attached to each step, so failures are self-diagnosing
- Failure output names the case ID, the input booking, the actual output, and the topic it landed on
- Headline metrics: overall pass rate, **critical pass rate reported separately**, execution duration, scenarios by tag

**Coverage note**

- Code coverage cannot be reported from a black box suite
- What is reported is **scenario coverage against this scope document** — cases executed versus cases defined
- Code coverage belongs to the developer's unit tests via JaCoCo

---

## 11. Execution Environments

- Same suite runs against an embedded broker locally and a real cluster in CI, switched by profile
- Fast functional suite on every commit; volume and slow suites nightly
- Pipeline definition committed alongside the tests

**Prerequisites to run the suite**

- Java version and build tool version stated explicitly
- Whether Docker is required, and which profile avoids it
- Ports used by the embedded broker, and how to override them
- Single documented command to run everything; documented commands per tag profile

**Execution time budget**

- Fast functional suite: under two minutes — this is the target the `@fast` tag exists to protect
- Full suite excluding volume: under five minutes
- Volume suite: nightly, no interactive time budget
- A suite that outgrows its budget gets re-tagged or re-scoped, not tolerated

---

## 12. Suite Quality Assurance

- **Mutation-style sanity check** — the application is deliberately broken (threshold raised to 0.99) and the suite is confirmed to go red. A suite that cannot fail proves nothing
- **Flaky test policy** — detect, quarantine, fix. No blanket retries masking real defects
- **Test pyramid honesty** — this suite sits at the top of the pyramid by design, with unit coverage assumed below it

---

## 13. Entry and Exit Criteria

**Entry criteria**

- Application builds and starts successfully
- Topics exist or are created by the harness
- Reference city list is loaded and available to the application
- Test data table is present and parses cleanly
- Broker reachable under the selected profile

**Defect handling**

- A failing scenario is triaged before it is retried — never suppressed by a blanket retry
- Defects are raised against the application with the case ID, input booking and observed output attached
- A failure caused by the test rather than the application is fixed in the suite and noted, so the distinction stays visible

**Exit criteria**

- All functional and non-functional scenarios pass
- No message loss in the volume run
- Suite executes end to end with a single command
- Every excluded area documented with rationale
- Report generated with critical pass rate at 100%

---

## 14. Applying This Without Source Access

The exercise required building the application as well as the suite. In a real engagement the service would arrive from another team with a topic contract and no source access. The approach is unchanged, because nothing in it depends on seeing the implementation.

- The scope was derived from the problem statement before any implementation existed
- Every assertion is made against the message contract — topics, payload fields, keys, headers
- No test references an internal class, method or configuration value
- Threshold behaviour is probed empirically, from either side of the boundary, rather than read from a constant
- The only additions needed against a third-party service would be an agreed message contract, a test environment, and a named owner for the confidence threshold

This is the point of the black box boundary: the suite is portable to the real system as written.

---

## 15. Beyond This Exercise

Observations relevant to a production deployment of this pattern:

- **Master data at scale** — eight cities is trivial; UN/LOCODE is roughly 110,000 entries. Both the matching strategy and the test approach change materially at that scale
- **Confidence threshold ownership** — a business decision, not a technical one. Requires a named owner, a tuning process, and an agreed cost model for a wrong auto-correction versus a false flag
- **Operational path for flagged bookings** — who reviews them, against what SLA, and whether corrections feed back into master data
- **Multilingual and transliterated city names** — a real logistics feed will carry diacritics, non-Latin script and transliteration variants

---

## 16. Known Limitations

A reader cannot otherwise tell a deliberate boundary from unfinished work. This
section separates the two.

### 16.1 Out of scope by decision

Each of these is excluded because it needs infrastructure this exercise does not
have, not because it was overlooked. Section 4.3 gives the full table; the
headline exclusions are:

| Excluded | What it would need |
|---|---|
| Performance and throughput | A dedicated environment; k6 or the JMeter Kafka plugin. The volume tier asserts correctness at scale and no throughput figure anywhere |
| Broker and downstream failure injection | Toxiproxy with Testcontainers |
| Schema evolution across versions | A schema registry with compatibility modes. What exists here is a suite-level stand-in: two committed fixtures and a checker, which is not the same thing |
| Contract testing against TMS | A published TMS contract, which was never supplied. Pact or a shared schema |
| Code coverage | Nothing: it is not measurable from a black-box suite and belongs to the development team's own unit tests. What this suite reports is scenario coverage against `spec/test-cases.yaml` |
| Security, TLS, broker authentication | An environment concern, not a suite-level one |

### 16.2 Accepted rather than fixed

These are known and open. They are recorded here rather than closed quietly.

- **DEF-111 - routing exclusivity has one mechanism, not two.** `TEST_SUITE_SPEC`
  section 7.5 claims TC-32 (behavioural) and TC-42 (structural) protect topic
  exclusivity independently. They do not. TC-42 validates a flagged payload in
  isolation, where routing cannot affect it, and TC-40 only ever publishes a
  booking that enriches successfully, so no leaked payload reaches it. The
  structural backstop is real but it is not a second independent check of
  routing. This is why the release recommendation is *proceed with conditions*
  rather than *proceed*.
- **The confidence floor has no named owner.** The strategy says the threshold is
  a business decision requiring an owner and a tuning process. Neither exists.
  The suite is built so this costs nothing to change - it states the value
  nowhere, and `ThresholdGuard` fails the build if anyone encodes it - but the
  decision itself is still unowned.
- **`TC-43` passes for a reason its name does not describe.** The enriched schema
  sets `additionalProperties: false`, so it does not tolerate an extra field.
  The scenario passes because the service drops unrecognised fields before
  publishing. The assertion is correct and worth keeping; the case title reads
  as though the schema were permissive, and it is not.
- **Reference list size.** Every matching case is proven against eight cities.
  Nothing here says how the strategy behaves against a real gazetteer, and
  section 15 explains why that is a different problem rather than a bigger one.
