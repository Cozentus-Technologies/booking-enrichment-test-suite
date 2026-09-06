# GAPS

What a walkthrough deck would ideally show, and what this repository does not
support. Written against the code, not against the specifications, and not
softened. Where the answer is favourable it is still stated as measured rather
than as claimed.

## 1. Checks the deck would like to make, and the honest answer

| Question | Answer | Evidence |
|---|---|---|
| Is there a process boundary between suite and service, or do they share a JVM? | **Real process boundary.** The suite launches `java -jar` through `ProcessBuilder`. | F-04 |
| Are there white-box tests contradicting the black-box claim? | **No.** Zero test files import a service internal package, and the build file carries no dependency on the service artifact. | F-02, F-03 |
| Do tags exist, and do they form a usable taxonomy? | **Yes, and the build enforces it.** Five axes, with unknown `@requires-` tags rejected. | F-20, F-21 |
| Is there traceability from requirement to case to result? | **Yes.** The matrix is generated from the features and fails the build when stale. | F-28 |
| Is a report generated, and what does it contain? | **Yes**, and it is produced for failing runs too. Every figure traces to `report-data.json`. | F-19, F-27 |
| Is contract/schema validation implemented? | **Yes**, three JSON schemas validated against real messages. | F-25 |
| Is there a mutation or self-verification mechanism? | **Yes**, 15 fixtures via a contract stub, all matching their expected set. | F-26 |

## 2. Genuine gaps

### 2.1 Not measured, and the report says so rather than inventing a value

`automation_stability` and `defect_leakage` need history across runs; with fewer
than two recorded runs both render **"not measured"**. `mean_time_to_detect` is
likewise absent when nothing failed. These are real holes in the metric set, not
capabilities. The deck must not present them as measured.

### 2.2 Volume is correctness at scale, not performance

The volume tier asserts that nothing is lost, duplicated or misrouted across
1,000 bookings. **No throughput or latency figure is asserted anywhere**, and
none may appear on a slide. Load and backpressure testing would need a dedicated
environment (k6, or the JMeter Kafka plugin).

### 2.3 Excluded by decision, and therefore absent

Broker and downstream failure injection (would need Toxiproxy with
Testcontainers), schema evolution across real versions (would need a schema
registry with compatibility modes), contract testing against the real TMS (no
TMS contract was ever supplied), and code coverage — which a black-box suite
cannot produce and which belongs to the development team's own unit tests.

### 2.4 DEF-111 — routing exclusivity has one mechanism, not two

The suite specification claims two independent mechanisms protect topic
exclusivity. The mutation check disproves it: `mut-double-publish` leaked a
flagged booking onto the enriched topic while TC-42 stayed green. This is why
the report says *Proceed with conditions*. The deck must not claim two
mechanisms.

### 2.5 Reference data is eight cities

Every matching case is proven against 8 cities. Nothing here demonstrates
behaviour against a real gazetteer; UN/LOCODE is roughly 110,000 entries, at
which both the matching strategy and the test approach change materially.

### 2.6 The confidence threshold has no named owner

The strategy calls the threshold a business decision needing an owner and a
tuning process. Neither exists. The suite is built so this costs nothing to
change - it states the value nowhere - but the decision is unowned.

### 2.7 Two mutation results tables

`docs/MUTATION_CHECK.md` carries the generated table plus a labelled archive of
an earlier real-service run, kept because three other documents cite it. The
specification asks for one.

## 3. Slides reduced as a result

- **Slide 5 (Coverage)** states scenario counts and data-row counts only. No
  coverage percentage of the service's code appears, because none can be
  produced from a black-box suite.
- **Slide 7 (Execution and reporting)** quotes the measured wall-clock of a full
  run and the per-profile scenario counts. It does not quote a throughput figure.
- **Slide 9 (Proving the suite works)** describes only the mutation mechanism
  that exists and was executed. It does not claim the two independent routing
  mechanisms the specification asserts, because 2.4 disproves them.
- **Slide 10** lists DEF-111 as open rather than presenting the suite as clean.
