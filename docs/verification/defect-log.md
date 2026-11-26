# Scope A Implementation Defect Log

**Project:** PayGuard TR — reconciliation core
**Specification baseline:** architecture revision 0.3.1 (frozen) plus the five authorised preflight corrections
**Last updated:** 23 November 2026

---

## Scope of this log

This log records defects **in the implementation**, found by testing the code against the
specification. Each entry was discovered by a check whose expected value was transcribed from
`requirements.md`, not from program output.

Defects found during the four architecture review rounds (0.1 → 0.2 → 0.3 → 0.3.1) were defects in
**documents**, not in code. They are listed separately in §7 so the two categories are never
confused.

**Five implementation defects were found.** Two were found by the author's own testing; three were
found by an independent implementation audit that read the frozen specification and probed the code
directly. The split matters, and §8 draws the conclusion from it.

| ID | Defect | Requirement violated | Found by | Status |
|---|---|---|---|---|
| DEF-001 | Exposure reported for an uncomputable quantity | §4.4 | Equivalence partitioning over `Optional` states | Fixed |
| DEF-002 | Limitation note written to the wrong field, and a test that endorsed it | FR-05.4 | Reading program output | Fixed |
| DEF-003 | Raw `NullPointerException` escaping the module; null history silently accepted | FR-08 | Independent audit | Fixed |
| DEF-004 | BR-07b enforced by the factory but not by validation | BR-07b, INV-12 | Independent audit | Fixed |
| DEF-005 | Currency gate examined one amount per side, not all four | FR-03, FR-08 | Independent audit | Fixed |

---

## 1. DEF-001 — exposure reported for an uncomputable quantity

| | |
|---|---|
| **Severity** | High — a wrong number reported to a merchant as if it were right |
| **Found by** | `AmountAtRiskTest.absentBecauseUncomputable` |
| **Clause** | requirements.md §4.4, `PAYMENT_WITHOUT_ORDER` row |
| **Regression** | `regression/DefectRegressionTest$Def001ExposureAbsence` |

### Failing behaviour

A captured payment with no matching order, and no refund amount recorded by the gateway, returned
`amountAtRisk = Optional.of(collectedAmount)` — a confident figure of 1 250.00 TRY.

### Expected behaviour

§4.4 specifies the exposure for `PAYMENT_WITHOUT_ORDER` as *present when both known*:
`collectedAmount.absoluteDifference(refundedAmount)`, and **absent if either amount is unknown**.

### Root cause

`Exposure.collectedLessRefunded` treated a missing refund amount as a refund of zero. The code
documented the substitution in a comment — *"Nothing refunded recorded: the whole collected amount
stands as exposure"* — which is exactly the inference the specification forbids. An absent field
means the gateway did not say; it does not mean the gateway said zero.

This is the same conflation revision 0.3 removed from the specification: one field carrying both a
value and an absence. The specification had already been corrected; the implementation reintroduced
the defect one level below it.

### Why the first pass missed it

The original test checked `REFUNDED` + `ABSENT` — a fully refunded orphan, where both amounts *are*
known and a legitimate zero is correct. That case passed. The case where one amount is unknown,
which is the common one in production, was never constructed.

### Fix

`Exposure.collectedLessRefunded` returns `Optional.empty()` when the refunded amount is absent.
Severity is unaffected (INV-08 derives it from the finding type), so the finding stays `HIGH` and
the merchant is simply not given a number PayGuard cannot stand behind.

---

## 2. DEF-002 — limitation note in the wrong field, and a test that endorsed it

| | |
|---|---|
| **Severity** | Low in effect, high in instructiveness |
| **Found by** | Reading the demonstration output |
| **Clause** | requirements.md FR-05.4 |
| **Regression** | `regression/DefectRegressionTest$Def002LimitationPlacement` |

### Failing behaviour

Every finding produced without transaction history carried the reason
`duplicate_detection_unavailable_no_history` — including a clean `MATCHED`. The most common verdict
in real data arrived with a reason attached that was not a reason for it.

### Expected behaviour

FR-05.4 places the limitation in **`decisionPath`** and nowhere else. `reasons` explains the
finding; `decisionPath` records what was examined.

### Why the first pass missed it — the more serious half

The original assertion read:

```java
check("empty history records the limitation",
      noHistory.reasons().contains("duplicate_detection_unavailable_no_history"));
```

That was written by looking at what the implementation did, not at what FR-05.4 says. It passed,
and would have passed for as long as the defect existed, because it was the defect restated as an
assertion. A 681-check suite otherwise derived from the specification did not catch it. Reading the
program's output as a user would did.

### Fix

The `ctx.reason(...)` call was removed; the `ctx.note(...)` call remains. The test now asserts the
specification's placement *and* that a clean match carries no reasons at all — the stronger claim
FR-05.4 implies.

---

## 3. DEF-003 — raw `NullPointerException` escaping the module

| | |
|---|---|
| **Severity** | High — a contract violation at the module boundary |
| **Found by** | Independent implementation audit |
| **Clause** | FR-08 |
| **Regression** | `regression/DefectRegressionTest$Def003TypedValidation` |

### Failing behaviour

Three distinct problems under one heading:

1. `new ReconciliationInput(null, order, history, policy, evaluatedAt)` threw a raw
   `NullPointerException` from `Objects.requireNonNull` in the record's compact constructor.
2. A **null** history was silently normalised to an empty list, so a caller's bug produced a
   valid-looking verdict instead of an error.
3. Nineteen further `requireNonNull` guards across `PaymentObservation`, `OrderObservation`,
   `TransactionRecord` and `ReconciliationPolicy` had the same escape. A null `merchantOrderRef`
   would also have reached `.isBlank()` and raised an NPE from inside `validated()`.

### Expected behaviour

FR-08 requires malformed input to raise `InvalidReconciliationInputException` **naming the offending
field**, and states that no untyped exception escapes the module. Separately, FR-05.4 makes an
*empty* history valid — it limits duplicate detection, it is not malformed. Empty and null are
different inputs and must produce different outcomes.

### Root cause

`Objects.requireNonNull` is the reflexive way to guard a record component, and it is the wrong one
here: it produces an exception type the module's own contract forbids. Validation was happening at
construction, where only an untyped error is available, instead of at the reconciliation boundary,
where a typed one is.

The null-history normalisation was a convenience default of the same family as DEF-001 — treating
*absent* as a benign value rather than as an error.

### Why the suite missed it

The same way DEF-002 was missed, and this is the part worth recording. `InputValidationTest`
contained:

```java
@DisplayName("a null payment or order is rejected at construction")
void nullObservations() {
    assertThrows(NullPointerException.class, () -> new ReconciliationInput(null, ...));
}

@DisplayName("a null history is normalised to empty rather than rejected")
void nullHistoryIsNormalised() { assertDoesNotThrow(...); }
```

Both assertions were transcribed from the implementation's behaviour rather than from FR-08. They
did not merely fail to catch the defect — they **documented it as correct**, in a test class whose
entire purpose is FR-08 compliance, with `@DisplayName` text asserting the wrong contract.

### Fix

- The compact constructors no longer call `Objects.requireNonNull`; they only make defensive copies.
- `ReconciliationEngine.validate` checks every component — payment, order, policy, `evaluatedAt`,
  history, and each history element — raising `InvalidReconciliationInputException` with the field
  name.
- `validated()` on each model type checks its own required fields before any method is called on
  them.
- A null history is preserved as null by the record and rejected by the engine; an empty history
  remains valid.

---

## 4. DEF-004 — BR-07b enforced by the factory but not by validation

| | |
|---|---|
| **Severity** | High — defeats INV-12 and reopens the defect the grace ceiling was invented to prevent |
| **Found by** | Independent implementation audit |
| **Clause** | BR-07b, INV-12 |
| **Regression** | `regression/DefectRegressionTest$Def004BusinessStateTime` |

### Failing behaviour

`OrderObservation.of(...)` pins `businessStateChangedAt` to the order's creation time, as BR-07b
requires. But `OrderObservation` is a record, so its canonical constructor is public, and
`new OrderObservation(ref, status, total, createdAt, someOtherInstant, ...)` was accepted by the
engine without complaint.

### Expected behaviour

BR-07b fixes `businessStateChangedAt` to the order creation time throughout Scope A, because
WooCommerce exposes no per-transition timestamp and `date_modified` moves on edits that are not
state changes.

### Root cause and why it matters

This is not cosmetic. The removal of the grace ceiling in revision 0.3 was justified by INV-12:
discrepancy age increases strictly with `evaluatedAt`, so a discrepancy cannot be suppressed
indefinitely. That argument holds only if the caller cannot move the anchor. A caller able to
advance `businessStateChangedAt` on each poll could reset the age to zero every time and keep a real
discrepancy inside the grace window forever — **exactly the failure the ceiling once existed to
prevent, reintroduced through an unguarded constructor.**

The invariant was enforced in the convenient path and left unenforced in the reachable one.

### Fix

`OrderObservation.validated()` rejects any observation whose `businessStateChangedAt` differs from a
present `createdAt`, naming the field. The engine calls `validated()`, so the check sits on the path
every input takes. The grace ceiling was **not** reintroduced.

---

## 5. DEF-005 — currency gate examined one amount per side

| | |
|---|---|
| **Severity** | High — an untyped arithmetic failure in place of a finding |
| **Found by** | Independent implementation audit |
| **Clause** | FR-03, FR-08 |
| **Regression** | `regression/DefectRegressionTest$Def005CurrencyGate` |

### Failing behaviour

With requested = TRY, collected = EUR and order total = TRY, the priority-3 currency gate read
`PaymentObservation.currency()` — which reports the requested amount's currency — saw TRY on both
sides, and passed. Reconciliation then continued to the amount rule, where
`Money.absoluteDifference` raised `CurrencyMismatchException`, which propagated out of
`reconcile(...)`.

So a cross-currency input produced an exception instead of the `CURRENCY_MISMATCH` finding the
decision model defines for it.

### Expected behaviour

FR-03 requires amount currencies to be resolved **before** any amount comparison, and the gate sits
at priority 3 precisely so that comparing across currencies never happens. FR-08 independently
forbids an untyped failure escaping in place of a finding.

### Root cause

`PaymentObservation.currency()` is a convenience accessor that answers "what currency is this
payment in?" by returning the first currency it finds — requested, else collected. That is a
reasonable answer to a question the gate should not have been asking. A payment does not have *a*
currency; it has up to three amounts, each with one, and the order contributes a fourth. The gate
compared representatives instead of the actual set.

### Fix

`CurrencyMismatchRule` now collects the currency of every present monetary field —
`payment.requested`, `payment.collected`, `payment.refunded`, `order.total` — and reports
`CURRENCY_MISMATCH` if more than one distinct currency appears, naming each field and its currency
in the reasons. Fewer than two amounts present still skips the check, as before.

---

## 6. `testMode` — resolved as a documentation clarification, not a code change

`PaymentObservation.testMode` exists and no production rule reads it, which looked like an
unimplemented requirement. BR-09 states that a test-mode transaction *never produces a
merchant-facing finding; it is recorded and tagged.*

The qualifier **merchant-facing** resolves it. Scope A is a classification core with no publication
layer, and INV-01 requires exactly one finding for every valid input — so it has no way to produce
nothing. Suppressing a verdict here would mean returning a *wrong classification*, not withholding a
notification.

The division consistent with the frozen documents is therefore:

- Scope A classification is independent of `testMode`;
- `PaymentObservation.testMode` carries the tag BR-09 requires;
- deciding what reaches a merchant belongs to the application layer that consumes findings (Scope B).

This was made explicit in the documentation and pinned by `rules/TestModeNeutralityTest`, which
proves that toggling the flag alone changes neither type, severity, action, exposure nor reasons
across eight representative state pairs. No new `FindingType` was introduced and no rule changed.

This matters in production, not only in a sandbox: PayTR sets `test_mode=1` both in test mode and
when a test transaction runs against a live account, so live merchant data contains test-mode rows.

---

## 7. Architecture-review defects — documents, not code

Listed only so the two categories stay distinct. These were found and fixed during revisions 0.2,
0.3 and 0.3.1, **before any code existed**, and must not be presented as implementation defects.

| Revision | Defect | Nature |
|---|---|---|
| 0.1 self-review | Decision table claimed totality at 64 cells while the vocabulary gave 72; `DRAFT` was in the vocabulary but absent from the table | Counting / completeness |
| 0.1 self-review | PayTR refund endpoint labelled verified but sourced only from third-party API directories | Citation over-claim |
| 0.2 | Discrepancy age measured from a retrieval time, so every poll restarted the grace window — more monitoring produced less detection | Behavioural logic |
| 0.2 | Severity derived partly from `amountAtRisk`, demoting a refund divergence with no measurable amount to `LOW` | Behavioural logic |
| 0.2 | `PaymentEvent` carried both "notification received" and "history row" under one name | Modelling |
| 0.3 | Grace ceiling removed: its guarantee depended on caller-held state a pure core does not have | Architectural consistency |
| 0.3.1 | 18 live sections still carried revision-0.2 terminology after an audit wrongly reported passing | Cross-document consistency |

The 0.3.1 entry has a mechanical root cause worth stating as a testing lesson: the audit asked *"is
the new term present?"* and never *"is the old term still present?"* A check of that shape cannot
fail while old and new terminology coexist. It passed 25 assertions while 18 violations sat in the
files. Every replacement in the project now asserts its target was found before it is applied.

---

## 8. What found what — and the conclusion to draw from it

| Technique | Implementation defects found |
|---|---|
| Decision-table testing (80 cells, both threshold branches, 104 cell checks) | 0 |
| Boundary value analysis (tolerance, grace, both stale thresholds) | 0 |
| Property testing (permutation, repetition, purity) | 0 |
| Equivalence partitioning over *states of meaning* rather than values | **DEF-001** |
| Reading the program's own output as a user | **DEF-002** |
| Independent audit re-reading the specification and probing the boundary | **DEF-003, DEF-004, DEF-005** |

The distribution is the finding.

A 683-check suite, derived from the specification and passing completely, missed three defects —
and in the case of DEF-003 it contained two assertions that **actively certified the wrong
behaviour**, inside the very test class responsible for that requirement. The suite was not weak:
it covers all 80 cells, every boundary, and four invariants as properties. It was *written by the
same person who wrote the implementation*, and it inherited that person's assumptions wholesale.

Four of the five defects share one shape — **absent treated as a benign value**:

| Defect | The conflation |
|---|---|
| DEF-001 | Unknown refund amount read as a refund of zero |
| DEF-003 | Null history read as an empty history |
| DEF-005 | One currency read as *the* currency, when three fields could disagree |
| 0.2 review | `amountAtRisk` zero meaning both "nothing at risk" and "cannot tell" |

DEF-004 is the fifth and a different shape: an invariant enforced on the convenient path and left
unenforced on the reachable one.

The practical lesson is that **an oracle written by the implementer converges with the
implementation**, and no amount of discipline inside that loop fully prevents it. The three defects
the author's own suite missed were found by a reader who had the specification and no stake in the
code. The structural defence used for the decision table — generating expected values mechanically
from the requirements document, now verifiable by running `SpecTableChecker` — worked exactly where
it was applied: the 80-cell table contained no defects at all. Every defect in this log is in code
whose expectations were hand-written.
