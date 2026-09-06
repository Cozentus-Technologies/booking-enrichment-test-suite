# Mutation Check

> **Generated file.** Written by `bash run-mutation-check.sh`, which runs the
> suite once per fixture and reads the Cucumber report each run leaves behind.
> Editing it by hand is pointless: the next run overwrites it.

## 1. Why this exists

A black-box suite asserts on behaviour it never derived the rules for. That is
the point of the design, and it also means a green run proves nothing on its
own: a suite whose assertions are too weak passes against a broken service just
as happily as against a correct one.

**A suite that cannot fail proves nothing.** The only way to know this one can
fail is to break a rule on purpose, one at a time, and confirm that the exact
scenarios protecting that rule go red - no more, no fewer. An expected scenario
that stays green means the suite is not checking what it claims to. A scenario
outside the expected set going red means the suite is coupled to something it
should not know about. Both are findings, and both are recorded below rather
than tidied away.

## 2. How the rule is broken, without touching the service

The service is the system under test. Editing it is out of bounds here, and
impossible against a service somebody else operates - which is the situation
this suite is written for. So the check runs against a **contract stub**
(`src/main/java/com/cozentus/enrichment/stub/ContractStub.java`) that takes the
service's place: same three topics, same command-line flags, same `GET /ready`
endpoint, and no matching logic of its own. It replays a fixture of exact
input-to-output mappings taken from the published schemas in
`src/test/resources/contracts/`.

The suite is not aware the stub exists. It is selected purely by
`-Dservice.jar.path=target/contract-stub.jar`, and which fixture it replays
comes from the `STUB_FIXTURE` environment variable, so `ServiceController`
needs no knowledge of the substitution.

Breaking one rule is then a few lines of JSON on top of the baseline fixture.
The baseline is the control: a run against it is fully green, which is what
makes a red result under any other fixture attributable to that fixture alone.

## 3. Running it

```bash
bash run-mutation-check.sh          # baseline plus every mutation fixture
bash run-mutation-check.sh mut-silent   # one fixture, for a re-check
```

It needs a broker on the address in `test-local.properties` and nothing else.
The real service jar is never read, so the check runs on a machine that has
never had the service repository on it.

## 4. Results

| Fixture | Turned red | Stayed green | Verdict | Run date |
|---|---|---|---|---|
| `baseline` | none | all 99 case(s) in the slice; nothing failed | as expected | 2026-09-06 |
| `mut-floor-lowered` | TC-77, TC-177 | TC-01, TC-02, TC-05, TC-76, TC-176 | as expected | 2026-09-06 |
| `mut-floor-raised` | TC-76, TC-176 | TC-01, TC-02, TC-03, TC-04, TC-05, TC-77 | as expected | 2026-09-06 |
| `mut-cap-short` | TC-78, TC-178 | TC-01, TC-02, TC-03, TC-06, TC-79 | as expected | 2026-09-06 |
| `mut-no-collapse` | TC-05, TC-80, TC-105, TC-180 | TC-01, TC-04, TC-06, TC-07 | as expected | 2026-09-06 |
| `mut-field-swap` | TC-22, TC-23, TC-29, TC-47, TC-113, TC-115, TC-117, TC-177 | TC-13, TC-15, TC-17, TC-46, TC-77 | as expected | 2026-09-06 |
| `mut-precedence` | TC-48 | TC-01, TC-12, TC-29, TC-46, TC-47 | as expected | 2026-09-06 |
| `mut-const-confidence` | TC-06, TC-07, TC-08, TC-09, TC-10, TC-11, TC-12, TC-76, TC-78 | TC-01, TC-02, TC-03, TC-04, TC-05, TC-80 | as expected | 2026-09-06 |
| `mut-fabricated-id` | TC-50 | TC-22, TC-51, TC-52, TC-54, TC-55 | as expected | 2026-09-06 |
| `mut-normalised-original` | TC-22, TC-23, TC-46, TC-50 | TC-30, TC-41, TC-70 | as expected | 2026-09-06 |
| `mut-reverse-order` | TC-39 | TC-30, TC-31, TC-35, TC-53 | as expected | 2026-09-06 |
| `mut-silent` | TC-32, TC-63 | none | as expected | 2026-09-06 |
| `mut-const-key` | TC-21, TC-30, TC-35, TC-36, TC-52 | none | **differs** | 2026-09-06 |
| `mut-double-publish` | TC-32, TC-34 | TC-30, TC-31, TC-33, TC-41, TC-42 | as expected | 2026-09-06 |
| `mut-route-swap` | TC-60 | TC-61 | as expected | 2026-09-06 |

14 of 15 run(s) turned exactly their expected set red.


### What the columns mean

- **Turned red** - every scenario in that run's slice that failed, hooks
  included. A scenario that could not start is not a pass.
- **Stayed green** - the controls: scenarios in the same slice that the
  mutation must not disturb. A mutation that turns its slice entirely red has
  proved nothing about precision.
- **Verdict** - whether the red set is exactly the expected set. Anything else
  is written up in section 5.

A `@TC-nn` covering a Scenario Outline is listed red if any of its rows is red.

## 5. Findings

Where observed and expected differ, the observation wins. The expected set is a prediction about the suite; a difference is what the check is for.

### `mut-const-key`

- expected red: TC-35, TC-36, TC-52
- observed red: TC-21, TC-30, TC-35, TC-36, TC-52
- red but not expected: TC-21, TC-30
- expected but stayed green: none

TC-30 and TC-21 are declared controls, and they are expected to fail anyway. B-7 rewrote the key assertion in MessageContractSteps to locate its message by payload, but the routing step that runs before it (RoutingSteps, via MessageCollector.receivedFor) still correlates by key. A service that emitted the wrong key therefore fails every scenario that awaits a message, and reports it as nothing arrived rather than as a wrong key. That is a finding about the harness, not about these two scenarios, and it is the same spread the 2026-09-06 run against the real service saw on TC-30 and TC-31.


## 6. What each run covered

### `baseline`

- slice: `not @never`
- expected red: none
- the remediation spec predicted: nothing; the baseline is the control

Every scenario in the suite. @never matches no tag, so the expression selects all of them.

### `mut-floor-lowered`

- slice: `@TC-77 or @TC-177 or @TC-76 or @TC-176 or @TC-01 or @TC-02 or @TC-05`
- expected red: TC-77, TC-177
- the remediation spec predicted: TC-77

TC-177 is the destination mirror of TC-77, added by work package B after section 4's table was written. A confidence floor is a property of the matcher, not of a field, so a lowered floor necessarily moves both. Expecting only TC-77 would be expecting the suite to be blind on one field.

### `mut-floor-raised`

- slice: `@TC-76 or @TC-176 or @TC-77 or @TC-01 or @TC-02 or @TC-03 or @TC-04 or @TC-05`
- expected red: TC-76, TC-176
- the remediation spec predicted: TC-76

TC-176 is the destination mirror, for the same reason as mut-floor-lowered. TC-01 to TC-05 are the controls: exact matches never consult the floor.

### `mut-cap-short`

- slice: `@TC-78 or @TC-178 or @TC-79 or @TC-01 or @TC-02 or @TC-03 or @TC-06`
- expected red: TC-78, TC-178
- the remediation spec predicted: TC-78

TC-178 is the destination mirror. TC-79 is the reject side of the same cap and must stay green: narrowing a cap cannot start accepting something it already rejected.

### `mut-no-collapse`

- slice: `@TC-05 or @TC-105 or @TC-80 or @TC-180 or @TC-01 or @TC-04 or @TC-06 or @TC-07`
- expected red: TC-05, TC-80, TC-105, TC-180
- the remediation spec predicted: TC-05, TC-80

TC-105 and TC-180 are the destination mirrors. The corrected city is still right in all four, so only the confidence assertion added by B-5 can see the mutation; TC-04 (padding only, no internal run) is the control that keeps trimming and collapsing apart.

### `mut-field-swap`

- slice: `@TC-22 or @TC-23 or @TC-29 or @TC-46 or @TC-47 or @TC-113 or @TC-115 or @TC-117 or @TC-177 or @TC-13 or @TC-15 or @TC-17 or @TC-77`
- expected red: TC-22, TC-23, TC-29, TC-47, TC-113, TC-115, TC-117, TC-177
- the remediation spec predicted: at least one destination case

The spec asked only for one destination case. The slice covers all three destination outcome classes (unmatched, missing, ambiguous) plus the two multi-reason scenarios, against origin controls that must not move. TC-23 and TC-47 are red because their second reason changes, not their first.

### `mut-precedence`

- slice: `@TC-48 or @TC-29 or @TC-46 or @TC-47 or @TC-12 or @TC-01`
- expected red: TC-48
- the remediation spec predicted: TC-48

The three genuine ambiguity scenarios are the controls: the mutation must break precedence without breaking ambiguity reporting, or the result says nothing about precedence.

### `mut-const-confidence`

- slice: `@TC-06 or @TC-07 or @TC-08 or @TC-09 or @TC-10 or @TC-11 or @TC-12 or @TC-76 or @TC-78 or @TC-01 or @TC-02 or @TC-03 or @TC-04 or @TC-05 or @TC-80`
- expected red: TC-06, TC-07, TC-08, TC-09, TC-10, TC-11, TC-12, TC-76, TC-78
- the remediation spec predicted: TC-06 to TC-12

TC-76 and TC-78 are boundary rows added by B-2 that also assert the fuzzy path, so they move with the rest. Every corrected city is still right, which is the point: nothing but the confidence assertion catches this.

### `mut-fabricated-id`

- slice: `@TC-50 or @TC-51 or @TC-52 or @TC-54 or @TC-55 or @TC-22`
- expected red: TC-50
- the remediation spec predicted: TC-50

The other malformed scenarios are the controls: they assert the reason and nothing else, so they stay green and show that only the assertion B-6 added can see the invented id.

### `mut-normalised-original`

- slice: `@TC-22 or @TC-23 or @TC-50 or @TC-46 or @TC-41 or @TC-30 or @TC-70`
- expected red: TC-22, TC-23, TC-46, TC-50
- the remediation spec predicted: TC-22, TC-23, TC-50

TC-46 also asserts the original since B-4 and B-6, so it moves too. TC-41 is the control that matters: the normalised payload still validates against the flagged schema, so the schema layer cannot catch this and the field-level comparison has to.

### `mut-reverse-order`

- slice: `@TC-39 or @TC-30 or @TC-31 or @TC-35 or @TC-53`
- expected red: TC-39
- the remediation spec predicted: TC-39

TC-53 publishes two messages on one key as well, but they are identical, so reversing them is unobservable; it is the control proving the assertion is about order rather than about count.

### `mut-silent`

- slice: `@TC-63 or @TC-32`
- expected red: TC-32, TC-63
- the remediation spec predicted: TC-63, TC-32

TC-63 is the one B-7 was written for: before the poll result was asserted, it compared two empty key sets and passed against a service that had produced nothing at all.

### `mut-const-key`

- slice: `@TC-35 or @TC-36 or @TC-52 or @TC-30 or @TC-21`
- expected red: TC-35, TC-36, TC-52
- the remediation spec predicted: TC-35, TC-36, TC-52

TC-30 and TC-21 are declared controls, and they are expected to fail anyway. B-7 rewrote the key assertion in MessageContractSteps to locate its message by payload, but the routing step that runs before it (RoutingSteps, via MessageCollector.receivedFor) still correlates by key. A service that emitted the wrong key therefore fails every scenario that awaits a message, and reports it as nothing arrived rather than as a wrong key. That is a finding about the harness, not about these two scenarios, and it is the same spread the 2026-09-06 run against the real service saw on TC-30 and TC-31.

### `mut-double-publish`

- slice: `@TC-32 or @TC-34 or @TC-30 or @TC-31 or @TC-33 or @TC-41 or @TC-42`
- expected red: TC-32, TC-34
- the remediation spec predicted: TC-32, TC-34

Every positive assertion about the flagged route still passes, which is why TC-31 and TC-41 are the controls. TC-42 is the control that matters most: TEST_SUITE_SPEC section 7.5 claims it is a second, independent mechanism catching a flagged booking on the enriched topic, and DEF-111 says it is not, because it validates a flagged payload in isolation and never sees where that payload was published. This run re-tests that claim empirically. TC-42 staying green while the leak actually happens is the defect, not a passing control.

### `mut-route-swap`

- slice: `@TC-60 or @TC-61`
- expected red: TC-60
- the remediation spec predicted: TC-60

TC-61 is the count-only check and is the whole argument for the fixture: every total still matches, so it stays green while TC-60, which compares identities since B-8, does not.

---

## 7. Archive: seven mutations applied to the real service, 2026-09-06

Recorded before the contract stub existed, when the only way to break a
rule was to edit the service. Kept because `defects.csv` (DEF-111),
`spec/risks.yaml` and `docs/ai-review-log.md` all cite it. It is not a
result of the run above, and it is not regenerated: it is a dated record
of a different exercise, against `city-enrichment` at commit 9860876.

| # | Mutation | Expected to go red | Observed |
|---|---|---|---|
| 1 | Jaro-Winkler floor raised to 0.99 | TC-06 to TC-12 | Exactly TC-06 to TC-12; TC-01 stayed green |
| 2 | Distance cap forced to 0 | all fuzzy correction cases | TC-06 to TC-12 red; TC-01 to TC-05 green |
| 3 | Flagged bookings also published to enriched | TC-32, TC-34, TC-42 | TC-32, TC-34 red. TC-42 did **not** fire |
| 4 | Message key set to a constant | TC-35, TC-36 | TC-35, TC-36 and also TC-30, TC-31 |
| 5 | Inbound headers dropped | TC-37 | Exactly TC-37 |
| 6 | Canonical casing not applied | TC-02, TC-03 | TC-02, TC-03 and also TC-06, TC-07 |
| 7 | Original values omitted from metadata | TC-25, TC-44 | TC-25, TC-44 and also TC-40 |

Mutation 3 falsified a claim in the specification. TEST_SUITE_SPEC section
7.5 states that TC-42 is a second, independent mechanism catching a flagged
booking that reaches the enriched topic. It is not, as built: TC-42
validates a flagged payload against the enriched schema in isolation, so
where that payload was published does not affect its outcome. Routing
exclusivity rests on TC-32 and TC-34 alone. Raised as DEF-111. The
`mut-double-publish` row above re-tests the same claim against the stub.

Mutation 4's spread onto TC-30 and TC-31 is the same coupling the
`mut-const-key` row above runs into: the harness correlates an awaited
message by its key, so a wrong key reads as a missing message.
