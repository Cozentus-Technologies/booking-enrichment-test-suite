# Mutation Check

Source: TEST_SUITE_SPEC.md section 11.1. Run before any submission or release,
in addition to a normal green `mvn test -Pkafka` (or `./run-tests.sh full`).

## 1. Why this exists

A black-box suite asserts on behaviour it never derived the rules for itself
(TEST_SUITE_SPEC.md section 1.3) — it states what must come out for a given
input, never how the service computes it. That is the point of the design,
but it also means a green run proves nothing on its own: a suite whose
assertions are too weak, whose steps silently swallow the wrong exception, or
whose "Then" only checks that *something* arrived rather than the right
thing, passes against a broken service just as happily as against a correct
one.

**A suite that cannot fail proves nothing.** The only way to know this suite
can fail is to break the service on purpose, one specific way at a time, and
confirm the exact scenarios that mechanism is supposed to protect actually go
red — no more, no fewer. If an unrelated scenario also fails, the suite is
coupled to something it should not know about. If the expected scenario
stays green, the suite is not really checking what it claims to check, and
the underlying assertion (or the step that produces it) needs to be fixed
before the suite can be trusted again.

This is also the mechanism that keeps AI-assisted scenario generation honest
(TEST_SUITE_SPEC.md section 12): "every generated expectation is verified
through the mutation check" is not a suggestion, it is how a generated
scenario earns the right to be committed.

## 2. What "the suite" means here

The mutation check exercises the **suite's own assertions**, not the suite's
resilience to a broken *build*. Each row below is a one-line change to the
service's matching, routing, or message-handling logic — never to the test
suite itself, never to `TEST_SUITE_SPEC.md`'s scenario catalogue, and never
to a threshold in `testdata/city-cases.csv` (a real threshold change would
require the CSV and CLAUDE.md's non-negotiables to move together, in the
service repository, which is a different exercise entirely).

## 3. The seven mutations and what must go red

| # | Mutation | Expected failures |
|---|---|---|
| 1 | Jaro-Winkler floor raised to 0.99 | TC-06…TC-12 |
| 2 | Distance cap forced to 0 | All fuzzy correction cases |
| 3 | Flagged bookings also published to enriched | TC-32, TC-34, TC-42 |
| 4 | Message key set to a constant | TC-35, TC-36 |
| 5 | Inbound headers dropped | TC-37 |
| 6 | Canonical casing not applied | TC-02, TC-03 |
| 7 | Original values omitted from metadata | TC-25, TC-44 |

Notes on reading the table:

- "All fuzzy correction cases" (row 2) means every TC in `city_correction.feature`
  that relies on step 4 of SPEC.md section 5 rather than an exact match after
  normalisation — i.e. TC-06 through TC-12 at minimum; TC-01…TC-05 (exact
  matches) and TC-13…TC-20 (already-flagged cases) are expected to **stay
  green**, since neither the correction path nor a flag reason changes when
  the distance cap is forced to zero. If one of those unexpectedly turns red
  too, that is itself a finding — see section 5.
- Row 3 turns red on two independent mechanisms at once: TC-32 is the
  behavioural negative assertion (routing_and_flagging.feature), TC-42 is the
  structural one (schema_contract.feature — the `booking-enriched-v1` schema
  constrains `status` to `ENRICHED`, so a flagged message routed there fails
  schema validation as well as the routing assertion). TC-34 (exactly one
  output topic) fails because the booking now lands on both.
- Row 7 is exactly what section 1 shows in miniature: TC-25 is the
  behavioural assertion (`booking_enrichment.feature`) and TC-44 is the
  schema-level one (`schema_contract.feature`) for the same underlying
  contract — the enrichment metadata retaining both original values.

## 4. Procedure

Run each mutation in isolation. Never stack two at once — if two mutations
are combined and the expected scenarios go red, it is no longer possible to
attribute the failure to either one specifically.

1. **Mutate.** In the service repository (not this one), make the single
   smallest source change that produces the row's mutation — e.g. change the
   Jaro-Winkler floor constant, or force the distance cap method to return
   `0`. Do not touch this suite, `TEST_SUITE_SPEC.md`, or `testdata/city-cases.csv`.
2. **Rebuild.** Repackage the service jar (`mvn -q package -DskipTests` in
   the service repo is sufficient — the mutation check is about behaviour,
   not the service's own unit tests, which are white-box and out of scope
   per TEST_SUITE_SPEC.md section 13).
3. **Run the affected slice.** Start the mutated service and run only the
   tag slice that covers the expected failures, e.g.:
   ```bash
   mvn test -Pkafka -Dtest=RunCucumberTest \
     -Dcucumber.filter.tags="@TC-06 or @TC-07 or @TC-08 or @TC-09 or @TC-10 or @TC-11 or @TC-12"
   ```
   Running the full suite works too and is more thorough (it also confirms
   nothing *unexpected* went red), but the full suite is slower and the
   affected slice is enough to confirm the specific mechanism.
4. **Compare.** Check `target/scenario-coverage.md` (or the JUnit/Cucumber
   report directly) against the row's "Expected failures" column:
   - Every expected TC is red → the mechanism is proven.
   - An expected TC stayed green → the suite is not actually checking that
     behaviour; the assertion or step needs strengthening before the suite
     can be trusted, and this is a suite defect, not a service one
     (TEST_SUITE_SPEC.md section 10.3).
   - A TC outside the expected set also went red → the suite (or the
     mutation) is coupled to more than intended; investigate before
     recording a result.
5. **Restore.** Revert the source change in the service repository (`git
   checkout -- src/main/java` or equivalent) and rebuild the jar before
   moving to the next mutation or resuming normal work. A mutation left in
   place is a broken service masquerading as the current baseline.

Repeat for all seven rows. The check is run before any submission or
release, per TEST_SUITE_SPEC.md section 11.1's opening line — it is not a
one-time exercise performed once when the suite was first written.

## 5. What a bad result means

- **Expected TC stayed green:** fix the suite (the assertion is too weak, or
  the step is asserting the wrong thing), re-run this mutation, and only then
  move on. Do not "fix" the mutation to make the existing assertion pass —
  the mutation is deliberately wrong; the suite is supposed to notice.
- **An unexpected TC went red:** work out why before recording anything.
  Either the mutation touched more of the service's behaviour than intended
  (make it smaller and re-run), or the suite has an undocumented coupling
  between two scenarios that this table did not anticipate — worth a note in
  the Results table either way.
- **A TC that never runs on `local` (e.g. `@slow`/`@nightly`) is in the
  expected set:** confirm the tag slice actually included it before treating
  a green result as valid.

## 6. Results

Filled in by whoever runs the check, with the date and the environment
(`local` / `ci` / `external`) it was run against. Leave rows blank rather
than guessing — an empty cell here is honest; a fabricated one is not.

| # | Mutation | Expected failures | Observed | Run date | Run by |
|---|---|---|---|---|---|
| 1 | Jaro-Winkler floor raised to 0.99 | TC-06…TC-12 | | | |
| 2 | Distance cap forced to 0 | All fuzzy correction cases | | | |
| 3 | Flagged bookings also published to enriched | TC-32, TC-34, TC-42 | | | |
| 4 | Message key set to a constant | TC-35, TC-36 | | | |
| 5 | Inbound headers dropped | TC-37 | | | |
| 6 | Canonical casing not applied | TC-02, TC-03 | | | |
| 7 | Original values omitted from metadata | TC-25, TC-44 | | | |

No results are recorded above. This table is a template for whoever runs the
check next, not a claim that it has already been run.

## Results — run 2026-09-06 against city-enrichment at commit 9860876

All seven mutations were applied one at a time to the service source, the jar
rebuilt, the affected tag slice run, and the service restored with
`git checkout` before the next. The service repository was verified clean
afterwards.

| # | Mutation | Expected to go red | Observed |
|---|---|---|---|
| 1 | Jaro-Winkler floor raised to 0.99 | TC-06…TC-12 | **Exactly TC-06…TC-12.** TC-01 stayed green |
| 2 | Distance cap forced to 0 | All fuzzy correction cases | **TC-06…TC-12 red; TC-01…TC-05 green** |
| 3 | Flagged bookings also published to enriched | TC-32, TC-34, TC-42 | TC-32, TC-34 red. **TC-42 did NOT fire** — see below |
| 4 | Message key set to a constant | TC-35, TC-36 | TC-35, TC-36 **and** TC-30, TC-31 |
| 5 | Inbound headers dropped | TC-37 | **Exactly TC-37** |
| 6 | Canonical casing not applied | TC-02, TC-03 | TC-02, TC-03 **and** TC-06, TC-07 |
| 7 | Original values omitted from metadata | TC-25, TC-44 | TC-25, TC-44 **and** TC-40 |

Seven of seven were caught. The precision matters as much as the catching:
mutation 2 turned every fuzzy case red while leaving all five exact, casing and
whitespace cases green, which is a suite discriminating rather than collapsing.

### Mutation 3 falsified a claim in the specification

TEST_SUITE_SPEC section 7.5 states that TC-42 is a second, independent mechanism
catching a flagged booking that reaches the enriched topic — "two mechanisms
catching the same class of defect on the critical path". It is not, as built:

- TC-42 validates a flagged payload against the enriched schema in isolation, so
  routing does not affect its outcome.
- TC-40 only publishes a booking that enriches successfully, so no flagged
  payload ever reaches its topic to be caught.

Routing exclusivity therefore rests on TC-32 and TC-34 alone. Raised as DEF-111,
the only open defect in `defects.csv`.

Mutation 7 showed the schema **does** work as a second mechanism where it is
wired to: TC-40 caught null enrichment metadata that TC-25 was aiming at.
