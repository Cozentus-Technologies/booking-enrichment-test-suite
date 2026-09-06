# AUDIT

Every claim on every slide, re-read against `FACTS.md` and against the code or
command output the fact cites. A claim is **verified** only if the source says
what the slide says.

| # | Claim on the slide | Fact | Verified |
|---|---|---|---|
| 1 | Flagged bookings go to a dedicated `booking.flagged` topic | F-06 | Yes — `test-local.properties:31-33` |
| 1 | Confidence floor 0.85 plus an edit-distance cap | F-10 | Yes — `CityMatcher.java:21-26` |
| 1 | Ties are rejected as ambiguous, never tie-broken | F-10 | Yes — `AMBIGUOUS_*_CITY` in the flag-reason enum, scenario-backed |
| 1 | A real broker and a real process boundary | F-04, F-05 | Yes — `ProcessBuilder` + `KafkaProducer` |
| 2 | Suite and service are separate projects and repositories | F-01 | Yes |
| 2 | No dependency on the service artifact; 0 imports of its internals | F-02, F-03 | Yes — grep returned 0 |
| 2 | They meet through `java -jar` and a Kafka broker | F-04, F-05 | Yes |
| 3 | Topic names `booking.raw` / `.enriched` / `.flagged` | F-06 | Yes |
| 3 | Per-scenario topics and consumer group under `local` | F-07 | Yes — observed on the broker as `booking.raw.<hex8>` |
| 3 | Names used verbatim under `external`, nothing created | F-08 | Yes — run with the jar absent from disk |
| 3 | Tag counts in the caption (58 / 37 / 10 / 7 / 8 / 13) | F-20 | Yes — `grep | sort | uniq -c` |
| 4 | In-scope list | F-14, F-25 | Yes — each item maps to a feature file that exists |
| 4 | Out-of-scope list with reasons | GAPS 2.2, 2.3 | Yes |
| 5 | 99 declared scenarios expanding to 101 executions | F-13 | Yes — 96 `Scenario:` + 3 `Scenario Outline:`; cucumber.json = 101 |
| 5 | Per-file counts (50 / 12 / 8 / 7 / 6 / 6 / 5 / 5) | F-14 | Yes |
| 5 | CSV holds 50 rows, 9 columns | F-15 | Yes |
| 5 | The four quoted CSV rows | F-15 | Yes — quoted verbatim from the file |
| 5 | Every case stated for origin and destination | F-16 | Yes — enforced by `TestDataLoaderTest.everyCaseIsMirrored` |
| 6 | Only `harness/` imports `org.apache.kafka`, 3 of 11 files | F-05 | Yes — per-package grep |
| 6 | Maintenance points (one CSV row / one feature file / harness only) | F-15, F-05 | Yes |
| 7 | `mvn clean test -Pkafka` exit 0 in 4 min 09 s | F-18 | Yes — captured from the run |
| 7 | 282 tests, 0 failures, 0 errors, 101 scenarios | F-18 | Yes |
| 7 | smoke 7, contract 8, functional 38, full 95 | F-32 | Yes — each profile executed, count parsed from cucumber.json at this commit |
| 7 | Six artefacts produced | F-19 | Yes — `ls target/` |
| 7 | A run selecting no scenario fails the build | F-24 | Yes — exit 1 observed |
| 8 | Mutation check verifies generated expectations | F-26 | Yes |
| 8 | Boundary rows measured rather than assumed | F-26, GAPS | Yes — the notes in `city-cases.csv` record observed values |
| 8 | Build guards fail if a gap reopens | F-21, F-22, F-23, F-28 | Yes — five guards, observed in run output |
| 9 | 15 fixtures, baseline green over 99 cases, all 15 matched | F-26 | Yes — `docs/MUTATION_CHECK.md`, generated from the runs |
| 9 | The five fixtures quoted and their red sets | F-26 | Yes — rows copied from the generated table |
| 9 | The check runs with the service jar absent | F-26 | Yes — verified during the check |
| 10 | Boundary enforced by the build | F-02, F-03 | Yes |
| 10 | Runs against a service it did not start, jar absent | F-08 | Yes |
| 10 | Report computes its figures; none is a literal | F-27 | Yes — `100%` appears in the template only inside CSS |
| 10 | Traceability regenerated and guarded | F-28 | Yes |
| 10 | 14 s to abort on a dead broker | F-31 | Yes — timed |
| 10 | DEF-111 open; one mechanism, not two | GAPS 2.4 | Yes — `mut-double-publish` left TC-42 green |
| 10 | Two metrics render "not measured" | GAPS 2.1 | Yes — `report-data.json` shows null |
| 10 | Verdict "Proceed with conditions" | F-27 | Yes — `report-data.json` |

## Claims removed before the deck was built

| Claim considered | Why it was removed |
|---|---|
| A throughput or messages-per-second figure | The suite asserts none. Any number would have been measured incidentally, not by a test. GAPS 2.2 |
| A code-coverage percentage | Not producible from a black-box suite. GAPS 2.3 |
| "Two independent mechanisms protect routing exclusivity" | The specification claims it; the mutation check disproves it. GAPS 2.4 |
| Automation stability and defect leakage as measured values | Both render "not measured" below two recorded runs. GAPS 2.1 |
| An overall assessment score | It exists, but it is an assessment artefact rather than a property of the suite, and the brief asked for repository facts |

## Status by slide

Fully verified, no reduction: **1, 2, 3, 5, 6, 7, 8, 9, 10**.

Reduced: **slide 4** — the out-of-scope column originally carried five exclusions
with effort estimates. The estimates were removed because nothing in the
repository supports them.

Slides where fewer than half the intended claims were verifiable: **none**.
