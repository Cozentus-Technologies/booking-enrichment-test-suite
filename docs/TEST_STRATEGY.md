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

### 5.2 Plain JUnit for non-functional

- Volume and resilience tests are written in JUnit, not Gherkin
- Wrapping a 1,000-message throughput run in business language adds ceremony without adding clarity

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

**By test type**

```
@functional  @resilience  @volume  @smoke  @regression
```

**By feature area**

```
@city-correction  @routing  @flagging  @passthrough  @serialisation
```

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
