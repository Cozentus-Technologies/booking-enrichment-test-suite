# Traceability Matrix

Maps every `@TC-nn` scenario in `src/test/resources/features/` to the
specification clause it was derived from, its feature file, its priority
(`@critical`/`@high`/`@medium`/`@low`) and its type
(`@functional`/`@contract`/`@resilience`/`@volume`).

The TC list below was derived by scanning the feature files for `@TC-nn`
tags — the same catalogue `CoverageReporter` derives at report time — not
typed out by hand. Two spec documents are cited:

- **TEST_SUITE_SPEC.md**, this suite's own specification. Section 7.x is the
  scenario catalogue; each subsection below corresponds to one of its
  feature files.
- **SPEC.md**, the *service's* specification (owned by the service
  repository). Sections 4 (payload shapes), 5 (matching contract) and 6
  (flag reasons) are cited wherever a scenario asserts a rule that
  originates there. Not every scenario has a SPEC.md clause — TEST_SUITE_SPEC.md
  section 13 puts some suite behaviour (schema evolution, encoding/date
  round-tripping, oversized-payload handling) outside anything SPEC.md
  states explicitly; those rows say so rather than inventing a citation.

## `city_correction.feature` is generated — read this first

`city_correction.feature` is **not** committed; it is generated at build
time (`process-test-classes`, via `FeatureGenerator`) from
`src/test/resources/testdata/city-cases.csv`. TC-01 through TC-20 below
therefore trace to **two** places at once:

1. The CSV row with the matching `caseId` — the row is the source of the
   expected outcome, the tags, and the note explaining why the case exists.
2. SPEC.md section 5's matching contract and its sanity table, which is what
   every row's expected outcome is ultimately checked against.

Regenerate with `mvn process-test-classes`; do not hand-edit the
generated file (a build step overwrites it). A new case is added as a CSV
row, never as a change to this matrix or to `FeatureGenerator` itself.

---

## 1. City correction — `functional/city_correction.feature`

TEST_SUITE_SPEC.md §7.1. Every row also traces to `testdata/city-cases.csv`
by `caseId` (identical to the TC id) and to SPEC.md §5.

| TC | Scenario | Priority | Type | SPEC.md clause |
|---|---|---|---|---|
| TC-01 | origin "Mumbai" is corrected to Mumbai | critical | functional | §5 step 3 (exact match) |
| TC-02 | origin "MUMBAI" is corrected to Mumbai | critical | functional | §5 step 2–3 (case-fold, exact match); §5 sanity table `MUMBAI` |
| TC-03 | origin "mumbai" is corrected to Mumbai | critical | functional | §5 step 2–3 (case-fold, exact match) |
| TC-04 | origin "  Mumbai  " is corrected to Mumbai | high | functional | §5 step 2 (trim); §5 sanity table `  New   Delhi ` |
| TC-05 | origin "New   Delhi" is corrected to New Delhi | high | functional | §5 step 2 (collapse internal whitespace) |
| TC-06 | origin "Mumbi" is corrected to Mumbai | critical | functional | §5 step 4 (fuzzy, d=1, name length > 6 ⇒ cap 2); §5 sanity table `Mumbi` |
| TC-07 | origin "now delhi" is corrected to New Delhi | critical | functional | §5 step 4 (token matching via `delhi`); §5 sanity table `now delhi` |
| TC-08 | origin "Bangalor" is corrected to Bangalore | critical | functional | §5 step 4 (fuzzy, d=1); §5 sanity table `Bangalor` |
| TC-09 | origin "kolkatta" is corrected to Kolkata | high | functional | §5 step 4 (fuzzy, d=1, doubled letter) |
| TC-10 | origin "Ahmedabd" is corrected to Ahmedabad | high | functional | §5 step 4 (fuzzy, d=1, deletion) |
| TC-11 | origin "Pne" is corrected to Pune | critical | functional | §5 step 4 (short name, length ≤ 6 ⇒ cap 1, JW ≥ 0.85); §5 sanity table `Pne`; boundary pair with TC-13 |
| TC-12 | origin "Delh" is corrected to New Delhi | critical | functional | §5 step 4 (token matching, least obvious rule per §5 note); §5 sanity table `Delh` (base list) |
| TC-13 | origin "Pn" is flagged | critical | functional, flagging | §5 step 4/6 (d=2 > cap 1 ⇒ no match); §6 `UNMATCHED_ORIGIN_CITY`; boundary pair with TC-11 |
| TC-14 | origin "Mumbaiii" is flagged | critical | functional, flagging | §5 step 4/6 (d=3 > cap 2 ⇒ no match); §6 `UNMATCHED_ORIGIN_CITY`; boundary pair with TC-06 |
| TC-15 | origin "Warsaw" is flagged | high | functional, flagging | §5 step 6 (no candidate); §6 `UNMATCHED_ORIGIN_CITY`; §5 sanity table `Warsaw` |
| TC-16 | origin "Lisbon" is flagged | medium | functional, flagging | §5 step 6 (no candidate); §6 `UNMATCHED_ORIGIN_CITY` |
| TC-17 | origin "" is flagged | high | functional, flagging | §5 step 1 (missing — empty); §6 `MISSING_ORIGIN_CITY`; §5 sanity table `""` |
| TC-18 | origin "   " is flagged | medium | functional, flagging | §5 step 1 (missing — whitespace-only) |
| TC-19 | absent origin field is flagged | high | functional, flagging | §5 step 1 (missing — field absent) |
| TC-20 | null origin is flagged | medium | functional, flagging | §5 step 1 (missing — JSON `null`) |

## 2. Booking-level enrichment — `functional/booking_enrichment.feature`

TEST_SUITE_SPEC.md §7.2.

| TC | Scenario | Priority | Type | SPEC.md clause |
|---|---|---|---|---|
| TC-21 | Both cities resolve, enriched with both corrected | critical | functional | §5 (matching, applied independently to both fields); §4 enriched booking shape |
| TC-22 | Origin resolves, destination fails ⇒ flagged naming destination | critical | functional, flagging | §2 whole-booking rule (either city fails ⇒ whole booking flagged); §6 `UNMATCHED_DESTINATION_CITY` |
| TC-23 | Both cities fail ⇒ two reasons, origin then destination | high | functional, flagging | §6 ("`reasons` is a list; both cities failing yields two entries, ordered origin then destination") |
| TC-24 | A valid city does not rescue a booking with one invalid city | critical | functional, flagging | §2 whole-booking rule ("TMS never receives a half-enriched booking") |
| TC-25 | Enrichment metadata retains both original values | high | functional | §4 enriched booking shape (`originalOrigin`, `originalDestination`); mutation table row 7 |
| TC-26 | shipper, mode, requestedDate pass through unchanged | critical | functional, passthrough | §4 ("All raw fields unchanged except origin/destination") |
| TC-27 | An unrecognised extra field does not break processing | medium | functional, passthrough | §4 (raw booking shape; robustness to unspecified fields is implied, not asserted verbatim in SPEC.md) |
| TC-28 | A confidence score is present for each corrected city | medium | functional | §4 enriched booking shape (`originConfidence`, `destinationConfidence`); §5 step 4 (confidence definition) |
| TC-29 | Ambiguous input flagged with candidates in reference order | high | functional, flagging | §5 step 5 (ambiguity, reference-file order); §6 `AMBIGUOUS_DESTINATION_CITY`; §5 sanity table `Delh` (list + `Delhi`) |

## 3. Routing — `functional/routing_and_flagging.feature`

TEST_SUITE_SPEC.md §7.3.

| TC | Scenario | Priority | Type | SPEC.md clause |
|---|---|---|---|---|
| TC-30 | An enriched booking reaches the enriched topic | critical | functional, routing | §2 ("appears on exactly one of booking.enriched / booking.flagged") |
| TC-31 | A flagged booking reaches the flagged topic | critical | functional, flagging, routing | §2 (flag routing to a separate topic) |
| TC-32 | A flagged booking never reaches the enriched topic | critical | functional, flagging, routing | §2 (exclusivity); mutation table row 3 |
| TC-33 | An enriched booking never reaches the flagged topic | critical | functional, routing | §2 (exclusivity, other direction) |
| TC-34 | Every booking lands on exactly one output topic | critical | functional, routing | §2 (exclusivity, exhaustively); mutation table row 3 |

## 4. Message contract — `functional/message_contract.feature`

TEST_SUITE_SPEC.md §7.4.

| TC | Scenario | Priority | Type | SPEC.md clause |
|---|---|---|---|---|
| TC-35 | Message key is the bookingId on the enriched route | critical | functional, message-contract | §2 ("Message key: bookingId on all three topics"); mutation table row 4 |
| TC-36 | Message key is the bookingId on the flagged route | critical | functional, message-contract | §2 (message key); mutation table row 4 |
| TC-37 | A correlation-id header survives to the output message | high | functional, message-contract | Not specified in SPEC.md (header propagation is a suite-level contract expectation, not a §2–§7 clause); mutation table row 5 |
| TC-38 | Enrichment status header stamped correctly per route | medium | functional, message-contract | Not specified in SPEC.md; TEST_SUITE_SPEC.md §7.4 only |
| TC-39 | Bookings sharing a key retain their relative order | high | functional, message-contract | §7 (`InMemoryMessageBus` / synchronous delivery semantics — ordering is a consequence of the bus contract's per-topic queue) |

## 5. Schema contract — `functional/schema_contract.feature`

TEST_SUITE_SPEC.md §7.5. All rows also trace to `contracts/booking-enriched-v1.schema.json`
/ `contracts/booking-flagged-v1.schema.json`, which encode SPEC.md §4's
payload shapes as JSON Schema.

| TC | Scenario | Priority | Type | SPEC.md clause |
|---|---|---|---|---|
| TC-40 | Every enriched message validates against `booking-enriched-v1` | critical | contract | §4 enriched booking shape |
| TC-41 | Every flagged message validates against `booking-flagged-v1` | critical | contract | §4 flagged booking shape |
| TC-42 | A flagged message fails validation against the enriched schema | high | contract | §2 exclusivity, enforced structurally. There is no `status` enum: CH-09 asked for one and it was deliberately rejected, because SPEC section 4 defines no such field and inventing it would have changed a payload shape the spec forbids changing. Exclusivity comes from `additionalProperties: false` plus disjoint `required` sets, so a flagged payload genuinely fails this schema and an enriched payload genuinely fails the flagged one. See DEF-111. |
| TC-43 | An extra unrecognised field does not break schema conformance | medium | contract | §4. Note the mechanism: the schema sets `additionalProperties: false`, so it does **not** tolerate the extra field. The scenario passes because the service drops unrecognised fields before publishing, and what is asserted is that tolerance on the way in does not become a violation on the way out. |
| TC-44 | Enrichment metadata carries original and corrected values per city | high | contract | §4 enriched booking shape (`enrichment` block); mutation table row 7 |
| TC-45 | A schema change against the previous version is additive only | medium | contract | Not a SPEC.md clause — exercises `CompatibilityChecker` against the schema file's own git history (TEST_SUITE_SPEC.md §7.5) |

## 6. Resilience — `nonfunctional/resilience.feature`

TEST_SUITE_SPEC.md §7.6.

| TC | Scenario | Priority | Type | SPEC.md clause |
|---|---|---|---|---|
| TC-50 | A malformed payload is flagged as `MALFORMED_MESSAGE` | critical | resilience, flagging | §7 ("Parse failure → MALFORMED_MESSAGE to booking.flagged. Never throws out of the handler."); §4 (MALFORMED_MESSAGE shape) |
| TC-51 | A valid booking after a malformed one is still processed | critical | resilience, flagging | §7 ("must not stop subsequent deliveries") |
| TC-52 | A malformed message with no key uses the UNKNOWN fallback | medium | resilience, flagging | §4 ("bookingId is null and the message key is the Kafka key if present, else UNKNOWN") |
| TC-53 | A duplicate bookingId is processed each time it arrives | high | resilience, flagging | Out of scope per SPEC.md §2/TEST_SUITE_SPEC.md §13 (exactly-once is explicitly excluded); asserts at-least-once, non-deduplicating behaviour is intentional |
| TC-54 | An empty payload is flagged, not silently dropped | medium | resilience, flagging | §7 (never throws; parse failure ⇒ flagged) |
| TC-55 | A JSON array where an object is expected is flagged | low | resilience, flagging | §7 (parse failure ⇒ flagged, regardless of shape) |

## 7. Volume — `nonfunctional/volume.feature`

TEST_SUITE_SPEC.md §7.7.

| TC | Scenario | Priority | Type | SPEC.md clause |
|---|---|---|---|---|
| TC-60 | Every booking is accounted for under load | critical | volume, routing | §8.4 bulk profile (`enriched.size() + flagged.size() == N`) |
| TC-61 | Per-topic totals match the oracle after a bulk run | high | volume, routing | §8.4 (per-topic counts equal the oracle) |
| TC-62 | Per-reason totals match the oracle after a bulk run | high | volume, flagging | §8.4 (per-reason counts equal the oracle); §9 (oracle labelled by the real matcher) |
| TC-63 | No bookingId appears on both output topics after a bulk run | critical | volume, routing | §8.4 ("no bookingId on both topics"); §2 exclusivity at scale |
| TC-64 | Concurrent publishers lose no messages | high | volume, routing | §7 (`InMemoryMessageBus`/bus contract under concurrent publish — no message-loss guarantee stated explicitly beyond §8.4's aggregate check) |

## 8. Edge cases — `nonfunctional/edge_cases.feature`

TEST_SUITE_SPEC.md §7.8. Several rows here have no SPEC.md clause by
design — SPEC.md does not mandate a single outcome for these inputs, and the
scenario asserts predictable behaviour rather than a specified one
(TEST_SUITE_SPEC.md §7.8 note).

| TC | Scenario | Priority | Type | SPEC.md clause |
|---|---|---|---|---|
| TC-70 | Diacritics preserved intact on the flagged message | high | resilience, encoding | §5 step 6 (Kraków is not a reference city ⇒ `UNMATCHED_ORIGIN_CITY`); byte-identical preservation is not itself a SPEC.md clause |
| TC-71 | Non-Latin script survives the round trip without mangling | medium | resilience, encoding | As TC-70, for 東京 |
| TC-72 | requestedDate passes through byte-identical, no timezone shift | high | resilience, dates | §4 (`requestedDate` is a raw field carried through unchanged) |
| TC-73 | An invalid date string is passed through, not rejected | medium | resilience, dates | Not specified in SPEC.md; consistent with §4's opaque treatment of `requestedDate` |
| TC-74 | A very long shipper name is handled or rejected predictably | low | resilience, passthrough | Not specified in SPEC.md — no maximum length is stated for `shipper` |
| TC-75 | A payload near the broker message size limit behaves predictably | low | resilience, message-contract | Not specified in SPEC.md — broker limits are an infrastructure concern (TEST_SUITE_SPEC.md §13) |

---

## Coverage of this matrix

62 `@TC-nn` scenarios across 7 committed feature files plus the generated
`city_correction.feature`, spanning TEST_SUITE_SPEC.md sections 7.1–7.8 in
full. `CoverageReporter` (`support/CoverageReporter.java`) derives the same
62-scenario catalogue independently at report time by scanning these same
files, so this matrix and `target/scenario-coverage.md`'s "Defined" count
are expected to agree; if they ever diverge, one of the two was edited by
hand and is now wrong.
