# Testability and Verification Architecture

> **Nothing in this document reports a test result.** No test has been run, because no code has been written.
> Every table below is a *plan*. Coverage figures, defect counts and mutation scores must be measured and
> pasted from real tool output. Inventing any of them would be fabrication, and in a verification course it
> would also be the exact failure the course is about.
>
> **Expected values are derived from the specification, not from the implementation.** The procedure that makes
> this more than a slogan: the 80-cell decision-table test data is transcribed from requirements §4.2 *before*
> any rule class exists, so the suite fails comprehensively on day one and each rule is written to turn cells
> green. If a test ever fails and the resolution is to change the expected value, that change must be made in
> requirements §4.2 first and the test re-transcribed — never the other way round. A test whose expectation was
> copied from observed output verifies nothing except that the code is deterministic.

## 1. Why this core is unusually testable

`reconcile` is a total, pure function with an injected clock (ADR-002, INV-01, INV-02). Three consequences
decide the whole test design:

1. **Boundaries are exact, not flaky.** The grace-period boundary is a value comparison, not a race against a real clock, so a test at exactly `gracePeriod` is deterministic.
2. **The input space is enumerable.** 8 payment states × 10 order states = 80 combinations, all specified in requirements §4.2. Totality is therefore checkable, not aspirational.
3. **Three invariants are properties, not examples.** INV-06 (content-identical repetitions are inert), INV-09 (permuting the record list does not change the verdict) and INV-12 (a discrepancy leaves grace exactly once) are universally quantified statements, testable by generating transaction histories and repeated evaluations.

No mocking framework is needed for any core test. That is a design outcome, not a convenience.

## 2. Requirements traceability matrix

| Req | Technique | Test class (planned) | Evidence for the report |
|---|---|---|---|
| FR-01 Classify a pair | Decision table, EP | `DecisionTableTest` (80 cases), `ReconciliationEngineTest` | The 80-cell table with expected versus actual |
| FR-02a Order-total disagreement | BVA | `AmountComparisonTest` | Boundary table at tolerance −1/=/+1 |
| FR-02b Over-collection with instalments | EP | `InstallmentAllowanceTest` | Allowed with `taksit`>0, flagged with `taksit`=0 |
| FR-02c Under-collection | BVA, EP | `AmountComparisonTest` | Flagged even with instalments present — the allowance is one-directional |
| FR-03 Currency mismatch | EP, negative | `CurrencyRuleTest`, `MoneyTest` | Mismatch flagged; absent or null currency skipped, not flagged, not thrown |
| FR-04.1 Time taxonomy | Static check + EP | `TimeSourceTest` | No rule reads `observedAt` or `retrievalSequence` (INV-11) |
| FR-04.2 Grace period | **BVA** | `GracePeriodTest` | Boundaries at `gracePeriod` −1ms/=/+1ms; zero age; both domain times absent |
| FR-04.2 Non-suppression | **Property** | `GraceSuppressionTest` | 100 re-evaluations with fresh retrieval times and advancing `evaluatedAt`: grace is left exactly once and never re-entered (INV-12) |
| FR-05.1 Record identity | EP | `RecordIdentityTest` | Provider row id where available; derived digest otherwise; digest stable under a retrieval-metadata change |
| FR-05.2 Identical duplicates | Property | `RepetitionInvariancePropertyTest` | Adding or removing content-identical repetitions does not change the finding (INV-06) |
| FR-05.3 Same-identity conflict | EP, **permutation** | `ConflictingIdentityTest` | `INSUFFICIENT_DATA` in **every** permutation — never a positional choice |
| FR-05.4 Distinct captures | EP, permutation | `DuplicatePaymentTest` | `DUPLICATE_PAYMENT` in every permutation; a five-times repeat never triggers it; **an empty history never triggers it, and `decisionPath` records the limitation** |
| FR-06 Authoritative state | **EP, decision table** | `AuthoritativeStateTest` | A `FAILED` status with a capture record yields `INSUFFICIENT_DATA`, never `CAPTURED`; a `CAPTURED` status with empty history still yields the table verdict |
| FR-06 Presentation order | **Property** | `OrderIndependencePropertyTest` | All permutations for small n, sampled for larger n, one distinct result |
| FR-07 Refund divergence (reduced) | EP, decision table | `RefundDivergenceTest` | Three directions; `shop_only` and `partial_vs_full` carry an **absent** amount |
| FR-08 Input validation | **Negative** | `InputValidationTest`, `MoneyTest` | One row per rejected input with the field named; `plus` overflow raises; clock-skew boundary |
| INV-01 Totality **over the valid domain** | Exhaustive | `TotalityTest` | All 80 combinations of *valid* inputs return exactly one `FindingType`; malformed inputs raise, and are covered by `InputValidationTest` rather than here |
| INV-02 Purity | Review + repetition | `PurityTest` | Same input twice, same output; no clock dependency |
| INV-03 No mutation | Property | `ImmutabilityTest` | Inputs unchanged after the call |
| INV-04 Currency safety | Negative | `MoneyTest` | Throws on cross-currency |
| INV-05 No floating point | Static check | build rule | No `double`/`float`/`BigDecimal` in the money path |
| INV-06 **Repetition invariance** | **Property** | `RepetitionInvariancePropertyTest` | Generated-input result. **Conflicting same-identity records are excluded from the property** — they are not duplicates and must yield `INSUFFICIENT_DATA` |
| INV-07 Unknown → INSUFFICIENT_DATA | EP | `UnknownStatusTest` | Both the `UNKNOWN` payment row and the `UNKNOWN` order column |
| INV-08 **Severity from type alone** | EP | `SeverityTest` | Severity is identical for one `FindingType` across a present amount, an absent amount and a zero amount |
| INV-09 Permutation invariance | **Property** | `OrderIndependencePropertyTest` | Generated-input result |
| INV-10 No dependencies | Build check | `pom.xml` | Dependency tree showing test scope only |
| INV-11 No operational time or retrieval order in rules | Static check + mutation | `TimeSourceTest` | Mutating `observedAt` and `retrievalSequence` across otherwise-identical inputs must not change the verdict |
| INV-12 Non-suppression | Property | `GraceSuppressionTest` | As FR-04.2 above |
| INV-13 **Single source of gateway state** | EP, **property** | `AuthoritativeStateTest` | For a fixed authoritative status, varying the history across every non-contradictory shape never changes the selected table cell |
| §4.4 `amountAtRisk` per type | EP | `AmountAtRiskTest` | One case per `FindingType`: present, absent, or present-and-zero, exactly as §4.4 specifies |

Every requirement and every invariant has at least one planned test. A requirement with no test is a
requirement nobody has agreed to.

## 3. Equivalence partitioning

Partitions chosen so that one representative of each is expected to behave identically.

**Payment status** — 8 classes, one per enum value. Not arbitrary: each drives a different row of the decision
table, so the enum *is* the partition.

**Order status** — 10 classes, one per enum value, `UNKNOWN` included.

**Amount relationship** (requested versus order total):

| Class | Representative | Expected |
|---|---|---|
| Equal | both 125000 | no amount finding |
| Within tolerance | diff ≤ tolerance | no amount finding |
| Requested above total, beyond tolerance | +1 beyond | `AMOUNT_MISMATCH` |
| Requested below total, beyond tolerance | −1 beyond | `AMOUNT_MISMATCH` |

**Collected versus requested** (the instalment case — this partition exists only because PayTR documents it):

| Class | Representative | Expected |
|---|---|---|
| Collected equals requested, no instalments | 125000 / 125000, `taksit`=0 | no finding |
| Collected exceeds requested, instalments present | 131250 / 125000, `taksit`=3 | **no finding** (FR-02) |
| Collected exceeds requested, no instalments | 131250 / 125000, `taksit`=0 | finding |
| Collected below requested | 120000 / 125000 | finding |

**Age against grace period** — 3 classes: below, equal, above.

**Transaction-history shape** — 8 classes: **empty** (duplicates undetectable, limitation recorded); single
record; repeats of one identity with identical content (collapse, FR-05.2); same identity with conflicting
content (`INSUFFICIENT_DATA`, FR-05.3); several distinct non-capture records; two distinct capture records
(`DUPLICATE_PAYMENT`, FR-05.4); records retrieved out of order; two distinct records sharing an `occurredAt`
(exercises the `recordIdentity` tie-break).

**Record identity source** — 3 classes: provider-supplied row identifier; derived content digest (PayTR's
history service supplies no row identifier); digest stability under a retrieval-metadata change — the same row
re-retrieved by an overlapping window must digest identically.

**Authoritative status versus history** — 4 classes, the partition INV-13 exists for: status and history
consistent; status `CAPTURED` with empty history (normal — the table verdict stands); **status `FAILED` or
`ABSENT` with one or more capture records** (contradiction → `INSUFFICIENT_DATA`); history richer than the
status but not contradicting it (for example a refund row with a `PARTIALLY_REFUNDED` status — no
contradiction, table verdict stands).

**`amountAtRisk` presence** — 3 classes, the partition that revision 0.2 could not express: a computed amount;
a computed **zero** amount (a fully refunded orphan payment); an **absent** amount (`CURRENCY_MISMATCH`,
`STATUS_CONFLICT`, `REFUND_DIVERGENCE(shop_only)`). Severity must be identical across all three for one
`FindingType` (INV-08).

**Currency pairing** — 2 classes: same, different.

**Test mode** — 2 classes: live, `test_mode=1` (BR-09; live data can contain test transactions because PayTR
sends `test_mode=1` also when a test is run in live mode).

## 4. Boundary value analysis

The boundaries worth testing, with the values to use.

### Grace period (FR-04)

Reference instant = `max(transactionOccurredAt, businessStateChangedAt)`, where `businessStateChangedAt` is the
order's creation time in Scope A. **No retrieval time appears here**, which is the point of the correction.

| Case | Condition | Expected |
|---|---|---|
| Just inside grace | `discrepancyAge = gracePeriod − 1ms` | `WITHIN_GRACE_PERIOD` |
| **On the boundary** | exactly `gracePeriod` | evaluated normally — equal counts as *outside* |
| Just outside grace | `gracePeriod + 1ms` | evaluated normally |
| Zero age | 0 | `WITHIN_GRACE_PERIOD` |
| Both domain times absent | neither available | grace cannot apply; evaluate normally |
| Domain time after evaluation, within skew | `occurredAt = evaluatedAt + clockSkewTolerance − 1ms` | accepted |
| **On the skew boundary** | exactly `clockSkewTolerance` | accepted |
| Beyond skew | `clockSkewTolerance + 1ms` | `InvalidReconciliationInputException` |

The boundary direction is a decision, not a detail: equal to the grace period counts as **outside**. Writing it
down is what makes an off-by-one arguable in review rather than accidental in code.

**The `graceCeiling` boundaries from revision 0.2 are gone**, with the ceiling itself (FR-04.3). Three cases
removed.

**The non-suppression test is the one that matters most**, because it is the test that would have caught the
revision 0.1 defect. Procedure: construct a discrepant pair with fixed domain times; evaluate it 100 times with
`evaluatedAt` advancing and a *fresh* `observedAt` each time. Assert that `WITHIN_GRACE_PERIOD` is returned
until the grace period elapses and **never again afterwards** — grace is left exactly once. Under the old
`max(observedAt, lastModifiedAt)` behaviour this fails on iteration two. The assertion is now stronger than
revision 0.2's, because without a ceiling the property is unconditional.

### Stale thresholds (BR-05, BR-07)

Two separate thresholds, each needing three cases, measured from the same reference instant as grace so they
are equally unsuppressable:

| Threshold | Cases |
|---|---|
| `staleAuthorizationThreshold` | −1ms → `AUTHORIZATION_PENDING`; exactly → `STALE_AUTHORIZATION`; +1ms → `STALE_AUTHORIZATION` |
| `staleInProgressThreshold` | −1ms → `IN_PROGRESS_PENDING`; exactly → `STALE_IN_PROGRESS`; +1ms → `STALE_IN_PROGRESS` |

### Amount tolerance (FR-02)

| Case | Difference in minor units | Expected |
|---|---|---|
| Below | `tolerance − 1` | no finding |
| **On the boundary** | exactly `tolerance` | no finding |
| Above | `tolerance + 1` | `AMOUNT_MISMATCH` |
| Zero tolerance, zero difference | 0 / 0 | no finding |
| Zero tolerance, one unit | 1 / 0 | `AMOUNT_MISMATCH` |
| **Under-collection just inside** | `collected = requested − tolerance` | no finding |
| **Under-collection on the boundary** | `collected = requested − tolerance − 1` | `AMOUNT_MISMATCH`, reason `under_collected` |
| **Under-collection with 12 instalments** | same, `taksit`=12 | `AMOUNT_MISMATCH` — the instalment allowance is one-directional and must not excuse under-collection |

### Money (ADR-003)

| Case | Value | Expected |
|---|---|---|
| Zero | 0 | valid |
| One minor unit | 1 | valid |
| Negative | −1 | `InvalidReconciliationInputException` |
| Maximum | `Long.MAX_VALUE` | valid |
| Overflow on addition | `Long.MAX_VALUE` plus 1 minor unit | `MoneyOverflowException`; **never** a wrapped value |
| Difference at the maximum | `absoluteDifference(0, Long.MAX_VALUE)` | succeeds, returns `Long.MAX_VALUE` |

**Revision 0.3 removed two planned cases that could not exist.** `Money` is a **non-negative** amount and
`minus()` no longer exists (ADR-003 rev 0.3), so a `Long.MIN_VALUE − 1` case was untestable under the model —
no instance can hold a negative value, so no subtraction can underflow from one. `absoluteDifference` cannot
overflow either, because for two non-negative longs the difference is at most `Long.MAX_VALUE`. One overflow
case remains, on `plus`.

That one still earns its place: it is the case most likely to be skipped and the one a mutation test would
punish. It is unrealistic for an order total, but a corrupted payload could carry it, and silent wraparound in a
money type is indefensible. `Math.addExact` detects it; the test checks that the result is translated to a typed
domain error rather than escaping as a raw `ArithmeticException`.

### Currency with an absent observation (FR-03)

| Case | Expected |
|---|---|
| Both present, same currency | proceed to amount comparison |
| Both present, different currency | `CURRENCY_MISMATCH` |
| Payment `ABSENT`, order has TRY | **not** a mismatch; skip to the decision table |
| Order `ABSENT`, payment has TRY | **not** a mismatch; skip |
| Payment present with null currency (PayTR omits `currency` on failure) | **not** a mismatch; skip; must not throw |
| Both absent | skip; the table yields `INSUFFICIENT_DATA` |

This partition exists because revision 0.1 said only "currency check precedes all amount comparison", which
left open whether an absent observation was a mismatch, a skip or an error. Three different implementations
would all have satisfied the old wording.

### Instalment count

| Case | Value | Expected |
|---|---|---|
| None | 0 | excess collected is a finding |
| Minimum instalment | 1 | excess allowed |
| Documented maximum | 12 | excess allowed |
| Above documented maximum | 13 | valid input, excess allowed, but flagged in `reasons` as unexpected |
| Negative | −1 | `InvalidReconciliationInputException` |

PayTR documents `taksit` as 0 to 12. Rejecting 13 outright would make PayGuard brittle against a provider
change, so it is accepted and noted — a deliberate asymmetry between "invalid" and "unexpected".

### Transaction-history size

0 (no history — classify from the observations alone, and record that duplicates were undetectable), 1,
2 content-identical, 2 distinct, and a larger n for the property tests.

## 5. Decision table testing

The full table is requirements §4.2: **8 payment states × 10 order states = 80 cells**, all specified.

**Structure.** A JUnit 5 `@ParameterizedTest` fed by a `@MethodSource` that yields all 80 combinations with
their expected `FindingType`. The table lives in the test as data, mirroring the specification, so a
specification change shows up as a test change.

```
@ParameterizedTest(name = "{0} payment + {1} order -> {2}")
@MethodSource("decisionTable")
void classifiesEveryStateCombination(PaymentStatus p, OrderStatus o, FindingType expected)
```

**Why this earns its place.** It directly checks INV-01 (totality): if a combination is unhandled the test
fails rather than silently returning null. And it makes the *priority* rules checkable — the cells where two
rules could both apply are exactly where the priority list in requirements §4.1 is exercised.

**Second table: rule priority.** Separate cases constructing inputs where several rules apply at once, to
assert which wins. For instance a cross-currency pair that is *also* within the grace period and *also* has
duplicate captures — a single case that pins three priority decisions at once.

## 6. Negative testing

| Input | Expected |
|---|---|
| `null` input | `InvalidReconciliationInputException` naming the argument |
| `null` payment, order, `history` or policy | Same, naming the field |
| Negative amount | Same, naming the field |
| Negative instalment count | Same |
| Domain time beyond `evaluatedAt + clockSkewTolerance` | Same |
| Non-positive `gracePeriod` | Same |
| Negative tolerance | Same |
| Cross-currency `Money.plus` | `CurrencyMismatchException` |
| `Money.plus` overflow | `MoneyOverflowException`, never a wrapped value |
| Unrecognised **payment** status | **Not an exception** — `INSUFFICIENT_DATA` (INV-07) |
| Unrecognised **order** status, e.g. a plugin-defined custom status | **Not an exception** — `INSUFFICIENT_DATA` (INV-07) |
| Empty transaction history | Valid; classify from the authoritative status alone, and record in `decisionPath` that duplicate captures were undetectable |
| Same-identity records with conflicting content | **Not an exception, and not a positional choice** — `INSUFFICIENT_DATA`, identically in every permutation (FR-05.3) |
| **History contradicting the authoritative status** | **Not an exception, and not resolved by precedence** — `INSUFFICIENT_DATA`, reason `gateway_history_contradicts_queried_state` (FR-06) |
| **Empty transaction history** | Valid; the table verdict stands on the authoritative status, and `decisionPath` records that duplicates were undetectable |
| Present observation with a null currency | Valid; currency check skipped (FR-03) |

The rows that carry the design are the ones that are *not* exceptions. **Five** separate things must be
distinguished: malformed input (throw); a value we cannot interpret (`INSUFFICIENT_DATA`); two records
contradicting each other about identity (`INSUFFICIENT_DATA`, different reason); **two provider endpoints
contradicting each other** (`INSUFFICIENT_DATA`, a third reason); and surprising-but-valid data such as an
instalment count above the documented maximum, or an empty history (accept, note in `reasons`). A suite that
threw on all five would be describing a much worse design than this one.

Note how the valid domain and totality fit together here. FR-08 defines the domain boundary; INV-01 claims
exactly one `FindingType` **within** it. Revision 0.2's wording — "every input yields exactly one
`FindingType`" — contradicted FR-08's exceptions, so `TotalityTest` is scoped to valid inputs and the
malformed cases live in `InputValidationTest`.

**Explicit assertion:** no `NullPointerException` and no raw `ArithmeticException` escapes the module. Every
rejection is a typed exception naming the offending field, because an operator reading a log needs the field,
not a stack trace.

## 7. Regression testing strategy

The strategy is the part most often hand-waved, so it is stated as a procedure:

1. **Every defect found becomes a test before it is fixed.** The test is named for the defect
   (`regression_DEF003_installmentExcessFlaggedWrongly`), fails on the unfixed code, and passes after the fix.
2. **Regression tests live in a separate source package** (`regression/`) so the count is visible and they are
   never "cleaned up" during refactoring.
3. **The defect log is the index.** Each entry carries: how it was found, the wrong behaviour, the root cause,
   the fix, and the test that now covers it.
4. **The full suite runs on every change.** With a pure core this costs seconds, so there is no excuse for a
   subset.
5. **A specification change requires a decision-table change first**, then the implementation. Changing code
   until the test passes inverts the dependency and is how a decision table silently stops being the
   specification.

For the report, the defect log is the highest-value artefact after the decision table, because it shows
testing finding things rather than confirming what was already believed.

## 8. JUnit 5 test structure

```
src/test/java/com/payguard/core/
├── model/
│   ├── MoneyTest                      BVA on amounts, plus-overflow, cross-currency, equality
│   └── StatusMappingTest              every enum value accounted for (8 payment, 10 order)
├── engine/
│   ├── DecisionTableTest              @ParameterizedTest over all 80 cells
│   ├── RulePriorityTest               several rules applying at once, incl. duplicates above grace
│   ├── TotalityTest                   INV-01, over the VALID domain; UNKNOWN row and column included
│   ├── AuthoritativeStateTest         FR-06, INV-13: status decides the cell; contradiction detected
│   ├── PurityTest                     INV-02: repeated calls, no clock
│   ├── TimeSourceTest                 INV-11: observedAt and retrievalSequence cannot change a verdict
│   └── ImmutabilityTest               INV-03
├── rules/
│   ├── AmountComparisonTest           FR-02 incl. instalment partitions
│   ├── CurrencyRuleTest               FR-03
│   ├── GracePeriodTest                FR-04.2 BVA: grace boundary, clock skew
│   ├── GraceSuppressionTest           INV-12: grace is left exactly once, never re-entered
│   ├── StaleThresholdTest             authorisation and in-progress pairs
│   ├── InstallmentAllowanceTest       FR-02b, one-directional
│   ├── DuplicatePaymentTest           FR-05.4 vs FR-05.2; empty history detects nothing
│   ├── RefundDivergenceTest           FR-07, three directions, absent amounts
│   └── UnknownStatusTest              INV-07, both axes
├── history/
│   ├── RecordIdentityTest             FR-05.1: provider row id, derived digest, digest stability
│   ├── RecordNormalizerTest           FR-05.2, FR-06 presentation order
│   ├── ConflictingIdentityTest        FR-05.3 across every permutation
│   ├── RepetitionInvariancePropertyTest  INV-06: content-identical repetitions are inert
│   └── OrderIndependencePropertyTest  INV-09 over permutations
├── result/
│   ├── SeverityTest                   INV-08: severity from FindingType alone
│   └── AmountAtRiskTest               §4.4: present / absent / present-and-zero per type
├── validation/
│   └── InputValidationTest            FR-08, all negative rows
└── regression/
    └── (one test per logged defect)
```

JUnit 5 features that pull their weight here: `@ParameterizedTest` with `@MethodSource` for the decision
table and for the per-type `amountAtRisk` cases; `@Nested` to group a rule's partitions readably;
`assertThrows` for the typed exceptions; `@DisplayName` so the report can quote test names that read as
specifications. Property tests can be written
with a generator loop in plain JUnit — adding a property-testing library would violate ADR-002's
no-dependency rule for anything beyond test scope, which is acceptable, but plain loops keep it simpler.

## 9. JaCoCo coverage, and what it cannot tell you

**Measurement.** JaCoCo via the Maven plugin, line and branch coverage, report generated in the build. Figures
in the report must be pasted from the generated HTML or XML output.

**What to expect.** A pure core with a decision-table suite should reach high line and branch coverage
naturally, because there is no infrastructure code to leave uncovered. High coverage here is therefore *weak
evidence* — it is the expected outcome of the design, not an achievement of the testing.

**The limitations, stated for the report's discussion section:**

1. **Coverage measures execution, not correctness.** The 80-cell test could execute every line while asserting
   the wrong expected finding in half the cells. Coverage would be ~100% and the product wrong. The decision
   table's *content* is the evidence of correctness; coverage only shows nothing was left unexercised.
2. **100% branch coverage does not mean all paths.** Branch coverage counts each branch taken at least once,
   not every combination of branches. The rule chain's priority interactions are combinations, which is why
   `RulePriorityTest` exists separately.
3. **Coverage cannot see missing code.** A finding type nobody thought of, or a decision-table cell mapped to
   a plausible-but-wrong type, is invisible to coverage. This is the dominant risk in a classifier.
4. **Equivalence partitioning deliberately skips most inputs.** One representative per class gives full
   coverage while testing a vanishing fraction of the input space. That is sound practice *and* a limitation.
5. **Coverage says nothing about assertion strength.** A test that calls `reconcile` and asserts only
   non-nullity covers everything and verifies almost nothing. This is exactly what mutation testing exposes.
6. **Invariants are not line-shaped.** INV-06 and INV-09 are properties over many inputs; no line-coverage
   figure can express whether they hold.

Writing this discussion honestly is worth more marks than a high number, and it is also simply true.

## 10. Mutation testing with PIT (optional)

PIT mutates the bytecode — flipping comparison operators, changing boundaries, removing calls — and reports
how many mutants the tests kill. It answers the question coverage cannot: *are the assertions strong enough?*

The mutations most relevant here are the ones that would hit the boundary decisions directly: changing `>=`
to `>` on the grace-period comparison, or `>` to `>=` on the amount tolerance. If the BVA tests in §4 are
written properly, those mutants die. If they were written only at the obvious values, they survive, and that
survival is the single most useful piece of evidence about test quality the project could produce.

**Given the deadline this is optional.** If run, report the real score; if not run, say it was not run.
Reporting a mutation score without running PIT would be a fabricated result.

## 11. MVP test types — now versus later

| Type | Required now (Scope A) | Required for the MVP | Later |
|---|---|---|---|
| Unit (core) | **Yes — the whole deliverable** | Yes, unchanged | — |
| Property (invariants) | **Yes** (INV-06, INV-09) | Yes | — |
| Integration (database) | No | Yes — idempotent ingestion, transaction atomicity | — |
| Contract (adapters against recorded provider responses) | No | **Yes** — this is where provider changes surface | — |
| End-to-end (connect a store, run, see a finding) | No | Yes, one happy path plus one failure path | Broader |
| Security (cross-tenant, signature tampering, log redaction) | No | **Yes** — T-05, T-03, T-07 are release gates | Penetration test |
| Failure injection (provider 503, truncated pagination, killed mid-run) | No | **Yes** — FM-05, FM-06, FM-10 | Chaos testing |
| Concurrency (two runs on one store) | No | Yes — FM-12 | Load |
| Performance | Baseline only, recorded not promised | Call-volume bounds per run | Load and soak |

Three of the MVP rows are release gates rather than nice-to-haves: cross-tenant isolation (T-05 is Critical),
failure injection for FM-06 (a provider outage must not manufacture findings), and log redaction (T-07). Those
are the tests that protect against the failures that would end the product.

## 12. Evidence to collect for the SVV report

The professor's constraints: maximum seven pages, the student's own sentences and code, only relevant code
included, filename `STUDENTNUMBER_FIRSTNAME_LASTNAME.pdf`, submitted through Blackboard by 24 November.

Seven pages is tight for eight sections. Suggested allocation:

| Section | Pages | Content |
|---|---|---|
| Introduction and the system under test | 0.5 | What the reconciler does and why it is worth verifying |
| Requirements | 0.75 | The eight FRs, compressed to a table |
| Test design: EP and BVA | 1.25 | The partition and boundary tables — **high value, keep them** |
| Test design: decision table | 1 | The 80-cell table, or a representative extract with the full table described |
| Negative and regression testing | 0.75 | The negative table plus the regression procedure |
| Implementation and execution | 1 | Tool versions, how to run, selected test code only |
| Results, coverage and defects | 1.25 | **Real** JaCoCo figures, the defect log, and the coverage-limitations discussion |
| Conclusion and references | 0.5 | At least three credible references |

**Evidence that must be real and pasted from output:** JaCoCo line and branch percentages; the test count and
pass/fail summary; each defect with its root cause and its regression test; a mutation score only if PIT was
actually run.

**What to cut if it overruns:** the full 80-cell table (describe it and show a representative extract);
implementation code (include only two or three methods that illustrate a design decision); the system
description. **What not to cut:** the boundary tables and the coverage-limitations discussion, because the
assignment weights test design, JUnit effectiveness, defect analysis and discussion most heavily.

**Candidate references** (verify each before citing, and read it): ISO/IEC/IEEE 29119 on test techniques;
Myers, *The Art of Software Testing* for EP and BVA; Ammann and Offutt, *Introduction to Software Testing* for
coverage criteria and their limits; the JUnit 5 user guide; the JaCoCo documentation on what its counters
mean; and for the domain claims, PayTR's own documentation on callback retries and instalment amounts, which
is a primary source and unusually good material for justifying why a rule exists.

## 13. Recommended scope reduction for the deadline

The review invited a justified reduction. One is recommended, and it is justified by evidence rather than by
time pressure.

**Reduce FR-07 to divergence detection without amount arithmetic.** Keep the three directional cases
(`gateway_only`, `shop_only`, `partial_vs_full`) and the decision-table cells that produce them. Defer
partial-refund *amount* comparison to the MVP.

Why this one and not another: the deferred part depends on data whose structure is unverified. The Status
Inquiry response's `returns` block exists but its per-item layout is undocumented (UNK-11), and the Transaction
Detail service's only refund indicator is `islem_tipi=S|I` with no amount-level fields. Specifying six boundary
cases over a guessed field layout would mean treating unverified provider capability as proven behaviour, which
is precisely what the review's final self-review check forbids. Cutting it removes a dependency on a guess, not
merely a few hours of work.

**What is preserved:** three directional cases, a rule with real branching, and the refund rows of the decision
table. Test depth is unaffected in kind — only partial-amount boundary cases are lost, and the grace,
stale-threshold, under-collection, overflow and clock-skew boundaries added in this revision more than replace
them in number.

**What is explicitly not cut**, despite the deadline: the 80-cell decision table, the non-suppression property
test, the permutation-invariance property test, and the negative suite. Those four carry the marks, and three
of them exist because the review found defects they would have caught.

**Nothing else should be cut from Scope A.** The temptation under deadline pressure is to trim the property
tests because they look exotic. They are the cheapest tests in the suite — a loop and an assertion — and they
are the only ones that check the invariants the review rounds had to add.

**What revision 0.3 removed, which pays for what it added.** Out: three `graceCeiling` boundary cases, two
impossible `Money` cases (`Long.MIN_VALUE − 1` and subtraction overflow), the `firstDetectedAt` input and its
validation case, one policy value, and the whole "last record decides the state" logic path. In: four
authoritative-status partitions, three `amountAtRisk` presence cases, and one contradiction case. The net is
roughly even in test count and **simpler in the code under test**, which is the better trade on a two-day
deadline.

---

One more thing worth saying in the report: the instalment rule (FR-02) came from reading the provider's
documentation and noticing that the obvious comparison would be wrong, and the grace-period correction came
from noticing that polling would reset its own window. Both are verification findings made before any code
existed. That is a better story than any coverage number, and it is the story the assignment is actually
asking for.
