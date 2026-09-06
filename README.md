# booking-enrichment-test-suite

Independent, standalone black-box BDD test suite for the booking
city-enrichment service. It verifies that a booking published to
`booking.raw` is enriched against the reference city list and routed to the
correct downstream topic — asserting only through the service's external
Kafka interface and its published contract schemas.

Full specification: `TEST_SUITE_SPEC.md` in the sibling `DSV_Test_V_0.1`
directory this suite was generated from. `docs/TEST_STRATEGY.md`,
`docs/TAGGING_GUIDELINE.md` and `docs/MAINTENANCE.md` in this repository are
the working reference for day-to-day use.

## The black-box boundary

| Aspect | Definition |
|---|---|
| Entry point | `booking.raw` |
| Observation points | `booking.enriched`, `booking.flagged` |
| Control surface | Service configuration at startup (broker, topics, city source) |
| Readiness | `GET /ready` on the service |
| Forbidden | Any import of a system-under-test class |

This suite has **no compile dependency on the service artifact**
(`com.cozentus:city-enrichment`). `pom.xml` carries a comment to that effect
at the top of its dependency section — its absence is deliberate, and it is
what makes the black-box claim physical rather than promised: a test that
tries to import a service class simply does not compile. The suite states
what must come out for a given input, never how the correction is derived.
Threshold behaviour (the confidence floor, the distance cap) is probed
empirically from either side of the boundary — see the boundary pairs in
`testdata/city-cases.csv` (`TC-11`/`TC-13`, `TC-06`/`TC-14`, `TC-12`) — never
read from a constant.

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker, for the `local` and `ci` profiles (not required for `external` —
  see below)
- Ports `9092` (Kafka) and `8081` (service readiness), both overridable —
  see `src/test/resources/config/test-local.properties`

## Environments

| Profile | Broker | Service | Topics | Use |
|---|---|---|---|---|
| `local` | `docker-compose.override.yml` | **started by the suite**, one instance per scenario | created and deleted per scenario | Developer machine |
| `ci` | docker-compose in the pipeline | **started by the suite**, one instance per scenario | created and deleted per scenario | Every commit |
| `external` | supplied endpoint | **started by someone else**, already running | the deployment's own, used verbatim | Against a real deployment |

Switching environments is a matter of which `config/test-*.properties` file
is active (`-Dsuite.env=<name>`, defaulting to `local`) — see
`docs/MAINTENANCE.md`.

**What each profile assumes:**

- `local` — Kafka is started by `docker-compose.override.yml`; the *suite*
  starts the service, one instance per scenario, from `service.jar.path`, on
  an ephemeral port of its own choosing. Topics are provisioned per scenario
  and deleted afterwards, so scenarios are fully isolated from each other and
  from any other run. Nothing but the broker has to be running before
  `mvn test`.
- `ci` — same as `local`, the suite starting the service included. The
  pipeline's job is to clone and build the service, not to run it. Timeouts
  stay identical to `local` on purpose (`test-ci.properties`) — a busier
  runner is a pipeline problem, not a reason to widen a contract timeout.
- `external` — the suite starts nothing and creates nothing. It reads no
  `service.jar.path` (the key is not in `test-external.properties`), polls
  the configured readiness URL, uses the configured topic names verbatim, and
  neither creates nor deletes a topic. **Scenario isolation by unique topic is
  unavailable**, so the suite prefixes every booking id it publishes with
  `RUN-<uuid8>-`, seeks its consumers to the end of each topic on assignment
  so a long-lived topic's history is not replayed, and ignores anything not
  carrying this run's prefix. An absence assertion is weaker evidence here
  than on `local`: it is a bounded observation on a topic this run does not
  own.

Select a profile with one flag or one environment variable:

```bash
mvn test -Dsuite.env=external -Dcucumber.filter.tags="@smoke"
SUITE_ENV=external ./run-tests.sh smoke
```

### External profile configuration

Every value the `external` profile reads, and nothing else. Defaults come from
`src/test/resources/config/test-external.properties`; a system property of the
same name beats the file, so a single run can be pointed elsewhere without an
edit (`-Dkafka.bootstrap.servers=…`).

| Key | Default | Required | What it is |
|---|---|---|---|
| `kafka.bootstrap.servers` | `external-broker.example.com:9092` | **yes** | The deployment's broker. The default is a placeholder, not a working value. |
| `topic.raw.prefix` | `booking.raw` | **yes** | The deployment's raw topic, used **verbatim** — no `.<scenarioId>` suffix on this profile. |
| `topic.enriched.prefix` | `booking.enriched` | **yes** | The deployment's enriched topic, verbatim. |
| `topic.flagged.prefix` | `booking.flagged` | **yes** | The deployment's flagged topic, verbatim. |
| `service.readiness.url` | `http://external-service.example.com:8081/ready` | **yes** | Polled before any scenario publishes. Placeholder default. |
| `consumer.group.prefix` | `city-enrichment-suite-external` | no | The suite's own identity on the broker; a per-scenario suffix is appended. |
| `await.timeout.seconds` | `30` | no | Bounded poll for a message to appear on an output topic. |
| `absence.window.seconds` | `15` | no | How long an absence claim observes for. |
| `readiness.timeout.seconds` | `60` | no | How long readiness is polled before the run gives up. |
| `volume.timeout.seconds` | `300` | no | Ceiling for a volume run to drain both output topics. |
| `sample.data.path` | `../DSV-test-assignement/data/bookings-sample.jsonl` | volume runs only | Fixture data, read from the local checkout rather than from the deployment. |
| `sample.oracle.path` | `../DSV-test-assignement/data/bookings-sample.jsonl.expected.json` | volume runs only | The oracle for the above. |

`service.jar.path` is **absent from this profile on purpose** and is never
read. Its absence is what makes "the suite starts nothing" checkable rather
than promised: a code path that reached for it would otherwise succeed quietly
against whatever jar happened to be on the machine, and the suite would end up
reporting on a process it started instead of on the deployment.

Check the environment before running anything against it:

```bash
SUITE_ENV=external ./run-tests.sh preflight
```

It prints broker reachability, whether the three configured topics exist and
whether readiness answers 200 — each with the value it used — and exits
non-zero if any of them is missing. Without it a missing topic shows up as
scenario after scenario timing out on a message that was never going to
arrive, which reads as a service defect.

### Excluded by environment

Four scenarios — `TC-29`, `TC-46`, `TC-47`, `TC-48` — need the service started
against an extended reference city list, because ambiguity only exists when
two candidates are equally valid. A black-box suite can only arrange that by
starting the service itself, so they carry `@requires-service-config` and are
excluded under `external`.

They are reported as **excluded by environment**, which is not the same as
*not selected by the tag filter*: they were selected, they could not be run
here, and they did not fail. Cucumber reports them as skipped, the run prints
a summary naming each, and the same summary is written to
`target/environment-exclusions.txt`. Run them under `-Dsuite.env=local`.

## Running the suite

```bash
./run-tests.sh preflight    # no scenarios              — broker, topics, readiness
./run-tests.sh smoke        # @smoke                    — under 60s
./run-tests.sh functional   # @functional and @critical  — under 2 minutes
./run-tests.sh contract     # @contract                  — schema + compatibility
./run-tests.sh full         # not @nightly                — everything except volume, under 5 minutes
./run-tests.sh nightly      # @nightly                   — volume/slow suite, unbounded budget
```

Each subcommand is a thin wrapper around
`mvn test -Dcucumber.filter.tags="..."`; run the underlying Maven command
directly for a narrower slice, for example a single case:

```bash
mvn test -Dcucumber.filter.tags="@TC-32"
mvn test -Dcucumber.filter.tags="not @quarantine"
```

Against the CI environment:

```bash
mvn test -Dsuite.env=ci -Dcucumber.filter.tags="@functional and @critical"
```

Reports land in `target/cucumber-reports/` (HTML, JSON, JUnit XML) and
`target/allure-results/` (`allure serve target/allure-results` for the Allure
UI). `target/scenario-coverage.md` is generated by `CoverageReporter` after a
run and reconciles executed scenarios against the catalogue in
TEST_SUITE_SPEC.md section 7 — see section 8.3/8.4 there for its exact
format and the coverage position (scenario coverage, not code coverage).

## Layered architecture

```
Layer 1   FEATURE FILES        business language, topic names only
             |
Layer 2   STEP DEFINITIONS     translate intent to harness calls
             |
Layer 3   HARNESS + DATA       Kafka mechanics, polling, correlation
             |
Layer 4   CONFIGURATION        broker, topics, timeouts, service control
```

- No Kafka API call appears in a step definition (enforced in review).
- Steps hold no state — all per-scenario state lives in `ScenarioContext`.
- `harness/` is the only package that knows Kafka exists.
- `config/` is the only coupling to the system under test.

### Maintenance consequence

| Change | What is touched |
|---|---|
| New city variant to test | One CSV row |
| New reference city | One CSV row |
| Threshold retuned | Boundary rows in the CSV; no scenario changes |
| Topic renamed | `config/` only |
| New payload field | `model/` plus one passthrough scenario |
| New behaviour | Feature file; steps usually already exist |
| Kafka client upgraded | `harness/` only |
| Point at DSV's real service | `config/` only |

Full rationale in `docs/MAINTENANCE.md`.

## Test data

`src/test/resources/testdata/city-cases.csv` is the single source of truth
for city-correction expectations — columns `caseId, input, expected,
outcome, reason, tags, note`. It drives the data-driven Scenario Outline in
`city_correction.feature` and, per TEST_SUITE_SPEC.md section 5.1, is
intended to also drive the volume generator so no expected value is ever
written twice. Three CSV conventions worth knowing before editing it:

- `expected` is empty whenever `outcome` is `FLAGGED`; `reason` is empty
  whenever `outcome` is `ENRICHED`.
- The `input` column uses two sentinels a step definition must special-case:
  the literal text `<ABSENT>` means the city field is omitted from the JSON
  payload entirely (as opposed to present-and-empty), and the unquoted
  literal text `null` means the field is serialised as a JSON `null` (as
  opposed to the three-character string `"null"`).
- `tags` are written by hand per row, not derived — see
  `docs/TAGGING_GUIDELINE.md` for the axes every scenario (and, by
  convention, every data row) is expected to carry.

## Tagging

Every scenario carries one tag from `@functional`/`@contract`/
`@resilience`/`@volume`/`@smoke`/`@regression`, one priority tag, at least
one feature-area tag, and exactly one `@TC-nn` traceability tag. A scenario
may also carry one *environment* tag — today only `@requires-service-config`,
see "Excluded by environment" above. Full rules and enforcement in
`docs/TAGGING_GUIDELINE.md`.

## Repository layout

See `TEST_SUITE_SPEC.md` section 2 for the full annotated tree. At the top
level:

```
pom.xml                          no dependency on the service artifact — see above
run-tests.sh                     the five tag-profile entry points
docker-compose.override.yml      broker only, for suite-driven local/CI runs
.github/workflows/test-suite.yml CI pipeline definition
docs/                            strategy, tagging, maintenance, mutation check, traceability
src/test/java/.../tests/         RunCucumberTest, steps/, harness/, contract/, model/, data/, support/
src/test/resources/              features/, testdata/, contracts/, config/, junit-platform.properties, allure.properties
```


## CI status

`.github/workflows/test-suite.yml` runs on demand only for now. Two things must
be true before restoring the push and pull-request triggers:

1. **The suite has tests.** `src/test/java` is empty by design; a push-triggered
   run today can only fail.
2. **A token exists that can read the service repository.** The service repo is
   private, and a workflow's default `GITHUB_TOKEN` is scoped to its own
   repository, so it cannot clone the service to build and start it. Add a
   repository secret `SERVICE_TOKEN` — a PAT or deploy key with read access —
   then uncomment the `push:` and `pull_request:` triggers.

A badge that is red because nothing exists yet teaches nobody anything, so the
workflow says so rather than failing on every commit.


## Reports

After a run:

| Report | Path |
|---|---|
| Cucumber HTML | `target/cucumber-reports/index.html` |
| Scenario coverage | `target/scenario-coverage.md` |
| Allure | `allure generate target/allure-results --clean -o target/allure-report` |
| JUnit XML | `target/cucumber-reports/cucumber.xml` |

**Always run with `mvn clean`.** Allure results accumulate across runs rather than
replacing each other, so without a clean the report shows failures from earlier
runs alongside the current one. That is how a mutation experiment's deliberate
failures ended up in an otherwise green report during development.

**Critical pass rate is the release gate** and is reported separately in
`scenario-coverage.md`; overall pass rate is informational.
