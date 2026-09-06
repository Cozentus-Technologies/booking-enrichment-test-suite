# AI-assisted review log

Source: `TEST_SUITE_SPEC.md` section 12 ("AI-assisted workflow") in
`DSV_Test_V_0.2`, which names five workflow stages and five guardrails. This
log is the evidence that the guardrails were actually followed, not a claim
that a tool was used: every row below cites a commit hash, a committed
document, or an explicit statement that no such evidence exists.

Two of the sourced decisions below live in the service repository
(`DSV-test-assignement`, commit hashes prefixed accordingly) rather than in
this one, because they concern the matching contract and the published
schemas this suite tests against, not this suite's own code. Both are cited
by path/hash there, read-only, and nothing in that repository was changed to
produce this log.

## Scenario derivation

*Generate scenarios from the specification; reviewed and pruned before
commit.*

| Proposed | Decision | Reason |
|---|---|---|
| Generate `city_correction.feature` from `testdata/city-cases.csv` rather than authoring it by hand, one scenario per CSV row. | **Accepted** | Cucumber cannot load an `Examples` table from an external file, so generating from the CSV was the only way to keep the CSV as the single source of truth (`TEST_SUITE_SPEC.md` section 5.1) instead of transcribing values twice. Commit `fbd2909`. |
| Generate one `Scenario` per CSV row instead of one `Scenario Outline` with a shared `Examples` table. | **Accepted** | Gherkin has no way to tag an individual `Examples` row, and every case needs its own `@TC-nn` to be traceable, filterable and countable in `scenario-coverage.md`. Commit `fbd2909`. |
| Hand-author 42 further scenarios (TC-21..TC-75) alongside the 20 generated ones, reusing the generator's step vocabulary. | **Accepted with change** | The generator and the hand-authored files had drifted onto two different phrasings for the same intent (e.g. `"lands on the ... route"` vs `"lands on the ... topic"`). The generator was changed to speak the hand-authored vocabulary, and `FeatureGeneratorTest` pins both the canonical phrasing and the absence of the superseded one. Commit `9adf31c`. |

Thin spot: the git history in this repository does not show a discrete
"proposed a scenario, it was pruned before commit" moment — only the
accepted, committed shape. If scenarios were dropped during drafting, no
record of what was cut survives in this repository.

## Test data generation

*Systematic misspelling variants — substitution, transposition, deletion,
doubling, phonetic, keyboard adjacency; boundary rows chosen deliberately.*

| Proposed | Decision | Reason |
|---|---|---|
| `testdata/city-cases.csv` as the single committed source of city-correction test data, with two explicit sentinels (`<ABSENT>` for a field omitted from the JSON, bare `null` for a JSON `null` literal) distinct from the empty string. | **Accepted** | Without the sentinels, "field omitted", "field null" and "field empty string" (TC-17) collapse into cases a CSV cannot express. Commit `fbd2909`. |
| Boundary pairs straddling the confidence threshold (`TC-11`/`TC-13`, `TC-06`/`TC-14`, `TC-12`) rather than only clearly-inside/clearly-outside cases. | **Accepted, human-chosen** | `TEST_SUITE_SPEC.md` section 12 names this explicitly as a human-owned judgement ("boundary rows chosen deliberately"); README.md's black-box boundary section cites these exact pairs as how threshold behaviour is probed from outside rather than read from a constant. |

## Gap analysis

*Analyse the data table and catalogue for missing cases; judgement on scope
versus noise.*

| Proposed | Decision | Reason |
|---|---|---|
| Add token-set comparison to the matching contract (SPEC.md section 5 step 4), matching a short input against a reference's individual tokens as well as its full name. | **Accepted, and the spec amended** | Without it, scenario BKG-13 was unsatisfiable: `"Delh"` is 5 edits from `"new delhi"`, so only `"Delhi"` ever matched and the booking enriched instead of flagging ambiguous. `DSV-test-assignement` commit `f1ae235` records the amendment (spec section 5 step 4) alongside three related sanity-table corrections made in the same pass: the self-contradictory "Pne" row resolved to Pune (d=1), a second "Delh" row added, and `destinationConfidence` corrected from 0.96 to 0.933. |
| Add a `"status": {"enum": ["ENRICHED"]}` field to `booking-enriched-v1.schema.json` (CH-09) so a flagged payload could never validate against the enriched schema. | **Rejected** | `SPEC.md` section 4 defines no `status` field — the enrichment outcome travels only as the `x-enrichment-status` header — and `APP_CHANGE_SPEC.md` section 3 explicitly forbids changing the payload shapes in `SPEC.md` section 4. Inventing the field to satisfy CH-09 would itself have violated the spec it was meant to protect. The same guarantee was achieved structurally instead: both schemas set `additionalProperties: false` and require fields the other payload shape does not carry, so a flagged payload genuinely fails the enriched schema's `required` check and vice versa. Documented in `DSV-test-assignement/contracts/README.md` ("Deviation from CH-09") and in the `$comment` of `booking-enriched-v1.schema.json` itself — not from a commit message, since this is a standing design note rather than a single change. |

## Suite review

*Second pass over scenarios, steps and assertions for gaps, redundancy and
weak assertions; final call on what stays.*

| Proposed | Decision | Reason |
|---|---|---|
| Map the suite's `@critical`/`@high`/`@medium`/`@low` priority tags onto Allure's `blocker`/`critical`/`normal`/`minor`/`trivial` severity vocabulary, written from a Cucumber `@Before` hook, then retried from `@After`. | **Rejected and removed** | The Allure Cucumber adapter finalises severity labels after all hooks have run, so a label written from either hook is discarded — neither took effect. Rather than leave non-functional code in place, it was removed; `@critical` is the only priority that overlaps Allure's own vocabulary and so is the only one that reaches Allure as a severity, documented in `docs/TAGGING_GUIDELINE.md`. The by-priority breakdown that matters for the release gate reads Cucumber tags directly in `scenario-coverage.md` and does not depend on Allure at all. Commit `f75e18e`. |
| Rely on `nothingArrivedFor()` (a single-sample check at the start of the absence window) to prove TC-32's routing-exclusivity assertion. | **Rejected in favour of a stronger implementation** | `HarnessSelfVerificationTest` was written specifically to prove this method can return `FALSE` when a message really is present, catches one arriving mid-window rather than only at the start, and judges absence per `bookingId` so another booking's message cannot mask this one's. Commit `1bfe9b6`. |
| Treat `TEST_SUITE_SPEC.md` section 7.5's claim of "two independent mechanisms" (TC-32 behavioural, TC-42 structural) enforcing routing exclusivity as verified because both scenarios pass. | **Rejected on review — logged as an open defect, not silently accepted** | Running mutation 3 (flagged bookings also published to the enriched topic) showed TC-32 turns red as expected but TC-42 does not: TC-42 validates a flagged payload against the enriched schema in isolation and never sees a payload that reached the wrong topic, so the claimed structural backstop does not hold. Recorded as `DEF-111` (Open, the only open defect in `defects.csv`) and as a risk in `spec/risks.yaml`, both dated 2026-09-06. `docs/MUTATION_CHECK.md` records the full mutation-3 result. |

## Risk analysis

*Identify failure modes common to enrichment pipelines, map coverage onto
them; risk model owned by the test engineer.*

| Proposed | Decision | Reason |
|---|---|---|
| Use a repository-scoped, read-only SSH deploy key to let the CI workflow clone the private service repository, instead of a personal access token. | **Accepted** | A deploy key is scoped to one repository and revocable without touching any person's account, unlike a PAT which carries a person's wider access; deploy keys had to be re-enabled organisation-wide first, which was escalated to the org admin rather than worked around. Commit `c9cc044`. The residual risk — a single credential that the pipeline must have to run the integration/nightly tiers at all — is carried forward explicitly in `spec/risks.yaml`, citing the credential's two near-misses in this same history: it was exported under the wrong variable name once (`fab2bee`) and did not exist yet once (`8d1df1b`), both producing a green run for the wrong reason rather than a red one. |
| Six risk entries covering: mutation-check results going stale between releases; TC-42's structural backstop not actually holding (DEF-111); a single external credential as a silent-skip single point of failure; no named owner for the confidence threshold; reference data three orders of magnitude below UN/LOCODE scale; and early suite-side defects only caught by running the harness live, not by review. | **Accepted as committed** | `spec/risks.yaml` is the risk register itself; every entry cites a specific commit or document rather than a generic template line, per its own stated sourcing rule. Not re-derived here — see that file directly for full wording, likelihood and impact ratings. |

Thin spot: `spec/risks.yaml`'s own header states risk entries cannot be
generated or derived from `cucumber.json` at all ("a risk is a judgement
about what could go wrong and why it matters, made by a person") — so unlike
the other four stages, there is no proposed-then-reviewed pair to show for
this one beyond the deploy-key decision above; the register itself is the
output of the judgement, not a draft that was accepted or rejected.

## Guardrails (`TEST_SUITE_SPEC.md` section 12 / catalogue F-14)

- **The tool drafts, the engineer owns.** Every accepted row above names the
  reason a person judged it correct — a spec conflict, an unsatisfiable
  scenario, a hook-timing fact verified by testing it — not "the tool
  produced it."
- **Nothing generated is committed unreviewed.** The rejected rows above
  (the Allure severity mapping, the `status` field, the unproven TC-42
  backstop) show review actually removing or flagging things that did not
  hold up, not just a record of what was kept.
- **Every generated expectation is validated by deliberate failure
  injection.** `docs/MUTATION_CHECK.md` records seven mutations run against
  the service on 2026-09-06 (commit `9860876`); all seven were caught,
  including the precision check that mutation 2 (distance cap forced to
  zero) turned exactly the fuzzy-correction cases red while leaving exact,
  casing and whitespace cases green rather than collapsing the whole suite
  to red. The same run is also what surfaced DEF-111 above — the mechanism
  did its job by finding a real gap, not just confirming the happy path.
- **No language model participates in reporting.** `5aa7f0a` and `6270794`
  describe the Test Execution Summary Report as templated over
  `report-data.json`, with every metric in `report/Metrics` "published with
  its formula beside its value so a reader can verify not trust," and five
  exit-criteria metrics that referenced formulas which did not exist yet
  were reported "not measured" rather than silently passing. Every
  published figure traces to a source file (`cucumber.json`,
  `defects.csv`, `spec/*.yaml`) and a stated formula in `report/Metrics`,
  never to a model's summary of them.
