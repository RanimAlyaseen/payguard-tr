# Scope A Implementation Summary

**Project:** PayGuard TR — reconciliation core
**Specification baseline:** architecture revision 0.3.1 (frozen) + five authorised preflight corrections
**Last updated:** 23 November 2026, after the final verification patch
**Declared production runtime dependencies:** zero (INV-10 holds; JUnit and JaCoCo are test-scope only)

> **Action required before submission.** The JUnit and JaCoCo figures in §3 and §4 were measured
> before this patch. Three defects were fixed and three test classes added since, so both must be
> re-measured: run `mvn clean test` and `mvn jacoco:report` and replace them. The 728 standalone
> checks in §3 *are* current.

---

## 1. What changed in this patch

An independent implementation audit read the frozen specification and probed the code directly. It
found three implementation defects that the author's own 683-check suite had passed over — and in
one case had actively certified as correct. All three are fixed, with regression tests.

| ID | Defect | Clause |
|---|---|---|
| DEF-003 | Raw `NullPointerException` escaped the module; null history silently accepted as empty | FR-08 |
| DEF-004 | BR-07b enforced by the static factory but not by validation, so a caller could move the grace anchor | BR-07b, INV-12 |
| DEF-005 | Currency gate read one amount per side; a cross-currency input threw `CurrencyMismatchException` instead of returning `CURRENCY_MISMATCH` | FR-03, FR-08 |

Also in this patch:

- **Regression package.** `src/test/java/com/payguard/core/regression/DefectRegressionTest` — one
  nested class per defect, DEF-001 to DEF-005, each naming the requirement and the old wrong
  behaviour. The stronger tests in the EP, negative and property suites were kept.
- **`testMode` resolved as a documentation clarification**, not a code change. BR-09's
  "merchant-facing" qualifier places suppression in the application layer; Scope A classifies
  identically either way, which `rules/TestModeNeutralityTest` now pins across eight state pairs.
  No new `FindingType`.
- **Specification-oracle reproducibility.** `verification/.../SpecTableChecker.java` re-reads
  `requirements.md` §4.2 and compares all 80 cells against the committed `SpecDecisionTable`. It
  reports 66 fixed, 12 threshold-conditional, 2 agreement, 0 mismatches. It is **not** the original
  generator — that was an ad-hoc script and was not kept, which the file says in its own javadoc.
  It is a checker written afterwards so the provenance claim is runnable rather than asserted.
- **Two documentation corrections.** `requirements.md` §4.3 "Fourteen cells" → "Twelve cells"
  (confirmed by the checker). `report-evidence.md` no longer claims line coverage was complete for
  DEF-001, since JaCoCo ran only after the fix and reports per class, not per line.

---

## 2. Files

| Area | Files | Lines |
|---|---|---|
| Production source (`src/main/java`) | 35 | 2 276 |
| Test source (`src/test/java`) | 31 | 3 245 |
| Standalone verification harness (`verification/`) | 5 | — |

Packages under `com.payguard.core`: `model`, `policy`, `engine`, `rules`, `result`, `error`, `demo`
— plus test packages `spec`, `engine`, `model`, `rules`, `result`, `regression`.

**28 JUnit 5 test classes**, 126 test methods (110 `@Test`, 15 `@ParameterizedTest`,
1 `@RepeatedTest`), plus three specification-data helpers: `SpecDecisionTable` (generated from
requirements §4.2), `SpecFixtures`, `SpecPermutations`.

---

## 3. Verification results

**Three different measurements. They are never merged, and only the first is a JUnit result.**

| # | Measurement | What it counts | Status |
|---|---|---|---|
| 1 | **JUnit executions** (Surefire) | Test methods executed by `mvn clean test`, parameter expansions included | **Pending re-run** — the 278 figure predates this patch |
| 2 | **Standalone harness checks** | Individual assertions in a dependency-free runner over the same specification fixtures, used because JUnit could not be fetched in the build environment | **728 / 728**, measured |
| 3 | **Specification-table checks** | Committed expected cells compared against `requirements.md` §4.2 by `SpecTableChecker` | **80 cells, 0 mismatches**, measured |

Measurement 2 is not a JUnit result and must never be reported as one. Measurement 3 checks the
test *fixture* against the specification document, not the implementation against the fixture.

### Standalone specification harness — current, measured

`evidence/specification-verification-run.txt`

| Metric | Value |
|---|---|
| Groups | 31 |
| Checks executed | 728 |
| Passed | **728** |
| Failed | **0** |
| Exit code | 0 |

This harness exists because JUnit could not be fetched in the environment where the code was
written. **It is not a JUnit result and must never be reported as one.** It executes the same
specification fixtures so that real pass/fail numbers exist during development.

### Specification table check — current, measured

`evidence/spec-table-check.txt` — 80 cells, 66 fixed / 12 conditional / 2 agreement, **0
mismatches** against `requirements.md` §4.2. Result: `SPEC TABLE OK`, exit code 0.

`SpecTableChecker` re-parses the markdown table on every run, so editing §4.2 without updating the
committed fixture is caught rather than silently diverging. It is **not** the original generator —
that script was ad-hoc and was not retained, which the file states in its own javadoc.

### JUnit 5 — **must be re-run**

The last measured run, before this patch, was `Tests run: 278, Failures: 0, Errors: 0, Skipped: 0`
with `BUILD SUCCESS`. Three test classes and three production fixes have landed since, so that
figure is stale. Re-run and record the new one:

```bash
cd core
mvn clean test
```

### Demonstration — current

`evidence/demo-run.txt` — eight scenarios execute and print finding, severity, action, exposure,
reasons and decision path.

### Finding-type reachability — current

`evidence/finding-type-reachability.txt` — all **16 of 16** declared finding types are produced.
This is reachability of declared outcomes, **not** code coverage.

---

## 4. Coverage — must be re-measured

The last measured figures, before this patch:

| Counter | Whole module | Excluding `ReconciliationDemo` |
|---|---|---|
| Line | 80.1% (572 of 714) | 95.0% (572 of 602) |
| Branch | 81.9% (203 of 248) | 86.0% (203 of 236) |
| Method | 81.9% (118 of 144) | 92.2% (118 of 128) |

```bash
mvn jacoco:report     # target/site/jacoco/index.html
```

The earlier analysis found that of 142 missed lines, 112 were `ReconciliationDemo` — a `main` that
prints for a human to read — and that the remaining ten unreached methods were all declared-but-
unused API surface rather than rule logic. That analysis should be redone against the new report,
since this patch added validation branches that the new negative tests exercise.

---

## 5. Defects

Five implementation defects, all fixed, all with regression tests. Full entries in `defect-log.md`.

| ID | Defect | Found by | Status |
|---|---|---|---|
| DEF-001 | Exposure reported for an uncomputable quantity (§4.4) | Equivalence partitioning over `Optional` states | Fixed |
| DEF-002 | Limitation note in `reasons` instead of `decisionPath` (FR-05.4) | Reading program output | Fixed |
| DEF-003 | Raw NPE escaping the module; null history accepted (FR-08) | Independent audit | Fixed |
| DEF-004 | BR-07b unenforced on the public constructor (BR-07b, INV-12) | Independent audit | Fixed |
| DEF-005 | Currency gate read one amount per side (FR-03, FR-08) | Independent audit | Fixed |

Four of the five share one shape — **an absent value treated as a benign one**: an unknown refund
read as zero, a null history read as empty, one currency read as *the* currency. DEF-004 is the
exception: an invariant enforced on the convenient path and left unenforced on the reachable one.

Two of them — DEF-002 and DEF-003 — were *certified as correct* by assertions written from the
implementation rather than the specification. That is the single most useful finding in this
project, and `defect-log.md` §8 develops it.

---

## 6. Quality gate

| Gate | Status |
|---|---|
| Production source compiles under Java 21 with `-Xlint:all` | Clean — no errors, no warnings |
| JUnit suite compiles against the Jupiter API | Clean (28 classes) |
| `mvn clean test` passes | Passed before this patch; **must be re-run** |
| Standalone specification verifier passes | 728 / 728 |
| All 80 decision-table cells pass | Yes, plus 24 threshold-branch checks |
| Specification table matches `requirements.md` §4.2 | Verified by `SpecTableChecker`, 0 mismatches |
| Five explicit regression defects pass | Yes — `regression/DefectRegressionTest` |
| Every Scope-A FR has tests | FR-01 to FR-08 |
| EP, BVA, decision-table, negative, regression testing represented | Yes |
| No rule reads retrieval time | `TimeSourceTest`, 12 checks |
| No rule depends on history order | `OrderIndependencePropertyTest`, all 24 permutations |
| Queried payment status authoritative | `AuthoritativeStateTest` (INV-13) |
| Identical record repetition inert | `RepetitionInvariancePropertyTest`, to 10 repetitions |
| Conflicting same-identity records → `INSUFFICIENT_DATA` | All 6 permutations |
| Currency arithmetic safe | `MoneyTest`; DEF-005 closed |
| Instalment excess matches FR-02 | Allowed with instalments, flagged without, never excuses a shortfall |
| Scope-A grace has no ceiling | `GraceSuppressionTest`: window opens once, closes once over 100 re-polls |
| `Money.minus()` does not exist | Verified by reflection |
| **No raw NPE escapes for FR-08 cases** | **DEF-003 fixed; verified** |
| **No cross-currency arithmetic exception escapes** | **DEF-005 fixed; verified** |
| **BR-07b enforced** | **DEF-004 fixed; verified** |
| No external integration in Scope A | Zero runtime dependencies; no network, database or file I/O in the core |
| Demo executes | 8 scenarios, `evidence/demo-run.txt` |
| JaCoCo report generated | Yes, before this patch; **must be regenerated** |
| No fabricated metrics | Every number here came from a run whose output is in `evidence/` |
| No architecture drift | No FindingType, decision-table cell, status value or ADR was changed |

---

## 7. Remaining limitations

1. **JUnit and JaCoCo figures are stale** and must be re-measured. See the box at the top.
2. **PIT mutation testing not run.** Optional, and the strongest remaining addition: it measures
   whether tests *detect* injected faults rather than merely executing code, which is the gap all
   five defects fell through.
3. **Duplicate detection depends on supplied history.** With none, duplicates are undetectable; the
   limitation is recorded in the decision path (FR-05.4) rather than passed over.
4. **`businessStateChangedAt` is the order creation time in Scope A** (BR-07b), now enforced. Scope
   B, with webhook capture, can do better.
5. **The partial-versus-full refund direction reports no amount.** Scope A has no shop-side refund
   figure to compare against, so the exposure is absent rather than guessed.
6. **`Severity.LOW` is unmapped.** No finding type maps to it; retained deliberately for Scope B and
   documented as unmapped rather than quietly deleted.
7. **Ten methods are unreached by tests** and the honest remedy is deletion rather than new tests —
   five interface `name()` accessors and four helpers the design declared before the implementation
   discovered it did not want them. Not done here, because the architecture is frozen.
8. **`SpecTableChecker` is not the original generator.** The original was ad-hoc and not kept. The
   checker provides equivalent assurance going forward but cannot retroactively prove how the
   committed file was first produced.
