# Patch Report 0.3.1 — Consistency Cleanup Before Freeze

**No architecture changed.** No requirement, `FindingType`, ADR, API, class, algorithm or feature was added.
Everything in the frozen list is untouched. This patch corrects wording and two behavioural statements that
still described revision 0.2.

## Why the revision-0.3 audit reported a false pass

Two mechanical faults, both worth recording because they are the kind that recur.

**1. The audit was positive-only.** It asked *"is `recordIdentity` used?"* and never *"does `eventIdentity`
appear anywhere live?"* A check of that shape cannot fail when old and new terminology coexist — which is
exactly the state the package was in. It passed twenty-five assertions while eighteen live violations sat in
the files.

**2. Several `str.replace` calls silently no-opped.** `ADR-006`'s `EventNormalizer` line, for instance, wrapped
differently in the file than in my replacement string, so the replace matched nothing, returned the input
unchanged, and reported success. Python's `str.replace` has no failure mode. Every replacement in this patch
asserts that its target was found, and the run aborts if not.

The same line-wrap problem appeared once more during this patch — a `revision 0.2's` marker split across a
newline defeated the paragraph classifier — which is why the audit below normalises whitespace before matching.

---

## Corrections

### 1. FR-01 terminology — *terminology*

| | |
|---|---|
| **File** | `requirements.md` FR-01 |
| **Found** | Description: "Given one payment observation, one order observation, **an event list** and a policy". Inputs: `ReconciliationInput` (payment, order, **events**, policy, `evaluatedAt`) |
| **Corrected** | "…a **transaction history** and a policy". Inputs: (payment, order, **`history`**, policy, `evaluatedAt`) |

Behaviour unchanged.

### 2. FR-07 `amountAtRisk` — *behavioural contradiction*

| | |
|---|---|
| **File** | `requirements.md` FR-07 Behaviour |
| **Found** | "`amountAtRisk` is the gateway's `refundedAmount` where available, **else zero**" |
| **Corrected** | Defers to §4.4 as authoritative: `gateway_only` → the gateway's `refundedAmount` when available; `shop_only` → `Optional.empty()`, because the gateway refunded nothing and what the customer is owed is a shop-side figure PayGuard does not hold; `partial_vs_full` → `Optional.empty()` in Scope A |

This was the overloaded zero surviving in the one place it most mattered — `shop_only` is the direction where
the customer is owed money. Severity and `FindingType` unchanged.

### 3. Live grace-ceiling references — *behavioural contradiction*

| File | Found | Corrected |
|---|---|---|
| `requirements.md` §4.1 | `WITHIN_GRACE_PERIOD` (FR-04, **subject to the ceiling**) | `WITHIN_GRACE_PERIOD` (FR-04) |
| `requirements.md` §4.2 preconditions | "grace period has either elapsed or been **overridden by the ceiling**" | "grace period **has elapsed**" |
| `SAD.md` §5.5 | demo runs "a discrepancy evaluated twice **to show the grace ceiling**" | "**a history row contradicting the queried status**" — a scenario that exercises live behaviour |
| `SAD.md` §10 | "boundary values on the grace period, **its ceiling**, both stale thresholds" | "…on the grace period, both stale thresholds" |
| `SAD.md` §13.1 OI-05 | "Grace period, **ceiling** and both stale thresholds are guesses" | "Grace period, **clock-skew tolerance** and both stale thresholds are guesses" |
| `domain-model.md` FindingType | `WITHIN_GRACE_PERIOD` — "…too recently to call it a problem, **and the grace ceiling has not been reached**" | "…too recently to call it a problem" |

FR-33's future MVP ceiling is untouched: it is deliberately Scope B.

### 4. Refunded-orphan explanation — *behavioural contradiction*

| | |
|---|---|
| **File** | `requirements.md` §4.3 |
| **Found** | "`REFUNDED` + `ABSENT`/`DRAFT` → `amountAtRisk` is zero because it was refunded, and §4.4's severity function therefore **returns LOW**" |
| **Corrected** | "`amountAtRisk` is **present and zero** — `collected.absoluteDifference(refunded)` — because the money was returned. **Severity remains HIGH**, since severity is a function of `FindingType` alone (INV-08): the money is back, but the two systems' records diverged, which the merchant should see" |

Decision-table cell unchanged; it remains `PAYMENT_WITHOUT_ORDER`.

### 5. `failure-mode-analysis.md` — *behavioural contradiction*

**FM-01** was "Duplicate callback delivery", detecting duplicates by `(merchantOrderRef, eventId)` — a model in
which callbacks enter the core. Rewritten as **"The same transaction record retrieved more than once"**, whose
trigger is now the real one: overlapping discovery windows re-retrieve rows **by design** (ADR-014), so
repetition is routine rather than exceptional. Deduplication is by provider-specific `recordIdentity`;
content-identical repetitions are inert under INV-06. PayTR's callback retry is retained as **provider/domain
background** with an explicit note that the retry lands on the merchant's own Notification URL and that a
callback is never a record entering the core.

**FM-03** said "**the latest event by occurrence decides**", which revision 0.3 forbids. Rewritten as
**"Transaction history contradicting the authoritative status"**: `PaymentObservation.status` from the query
API selects the gateway state always (INV-13); history is evidence with three permitted uses; where the two are
mutually impossible **neither wins** and the result is `INSUFFICIENT_DATA` with reason
`gateway_history_contradicts_queried_state`. Tests updated to `AuthoritativeStateTest`.

A sweep of the file for `EventNormalizer`, event ordering, event-list, arrival-order and latest-event wording
returns nothing.

### 6. Terminology cleanup — *terminology*

| File | Found | Corrected |
|---|---|---|
| `SAD.md` §5 component diagram | `EventNormalizer` node, "dedupe + order by occurredAt" | `RecordNormalizer`, "dedupe by recordIdentity, canonical presentation order" |
| `SAD.md` §10 | "duplicated and permuted **event lists**" | "repeated and permuted **transaction histories**" |
| `SAD.md` §13.2 | "Is **event** normalisation deterministic? … ties break on **`eventIdentity`**" | "Is **record** normalisation deterministic? … ties break on **`recordIdentity`**" |
| `SAD.md` §13.2 | "duplicate and out-of-order **events** … four invariants" | "repeated and out-of-order **transaction records** … five invariants (INV-13 added)" |
| `requirements.md` NFR-18 | "O(n log n) in **events**" | "O(n log n) in **transaction records**" |
| `testing-strategy.md` §1 | "**Two** invariants are properties … INV-06 (**deduplication does not change the verdict**)" | "**Three** invariants … INV-06 (content-identical repetitions are inert), INV-09, **INV-12**" |
| `testing-strategy.md` §4 | heading "**Event list size**"; "0 (no **events**…)" | "**Transaction-history size**"; "0 (no history — classify from the observations alone, and record that duplicates were undetectable)" |
| `testing-strategy.md` §6 | "`null` payment, order, **events** or policy" | "`null` payment, order, **`history`** or policy" |
| `testing-strategy.md` §6 | "Empty **event list** \| Valid; classify from the observations alone" | "Empty **transaction history** \| Valid; classify from the authoritative status alone, and record in `decisionPath` that duplicate captures were undetectable" |
| `engineering-standards.md` checklist | "permutation of the **event list**" | "permutation of the **transaction-record list**" |
| `adr/ADR-006` | "`EventNormalizer` deduplicates by event identity … deduplication never changes the verdict" | "`RecordNormalizer` groups by `recordIdentity` … adding or removing content-identical repetitions never changes the verdict", with the overlapping-window rationale |

### 7. ADR history — *historical and intentionally retained*

**ADR-008** keeps its original text verbatim beneath the superseded / do-not-implement warning. Unchanged.

**ADR-015**'s **current decision section** now uses `recordIdentity`, `retrievalSequence`, "records" and
`conflicting_records_same_identity` throughout. Its Context section still describes the revision-0.2
terminology, which is its purpose; the one ambiguous sentence was marked "revision 0.2's tie-break…" so it
reads as historical without context.

**ADR-016** likewise: three Context and Consequences sentences that necessarily name the old design were marked
with an explicit revision reference.

**ADR-003**'s two rejected alternatives were reworded to "Keep **revision 0.2's** `minus()` …", since naming a
rejected option inevitably names the thing rejected.

No historical review evidence was rewritten to remove old words.

### 8. Document counts — *terminology*

| | |
|---|---|
| **File** | `SAD.md` supporting-document list |
| **Found** | "`adr/` — **thirteen** architectural decision records" |
| **Corrected** | "`adr/` — **16 ADR records, 15 active + 1 superseded (ADR-008)**" |

---

## Live-specification consistency audit

Methodology changed, since the previous one was unsound. The audit now:

- searches for **retired terms**, not for correct ones, so coexistence is detectable;
- classifies by **paragraph** rather than by line, because a historical marker and its subject are often on
  different lines;
- **normalises whitespace before matching**, because a marker split across a newline defeated the classifier
  once during this very patch;
- treats `review-response-r2.md`, `review-response-r3.md` and the superseded body of ADR-008 as historical by
  file, and everything else as live unless its paragraph declares otherwise.

Terms searched: `graceCeiling`, `firstDetectedAt`, `PaymentEvent`, `PAYMENT_EVENT`, `eventIdentity`,
`EventNormalizer`, `receivedSequence`, `event list`, `subject to the ceiling`, `overridden by the ceiling`,
`severity LOW`, `last event`, `latest event`, `determines gateway state`, `Money.minus`, `minus()`.

| Classification | Count | Verdict |
|---|---|---|
| **LIVE — must conform to 0.3** | **0** | **No violations** |
| HISTORICAL — explicitly describing an earlier revision | 55 | Allowed |
| SUPERSEDED ADR — ADR-008 body under its warning | 3 | Allowed |

### Reviewed individually and found acceptable

Three uses of the bare word "events" in live ADR prose, where it carries its ordinary English meaning — a thing
that happened — and not the retired type name. A bare noun over-matches, so these are reported rather than
silently excluded:

| Location | Text | Judgement |
|---|---|---|
| `ADR-004` Consequences | "being down briefly delays detection rather than **losing events**" | Ordinary English, describing why polling is resilient |
| `ADR-007` Reconsider-when | "PayGuard starts receiving an ordered stream of provider **events**" | A hypothetical future provider capability |
| `ADR-016` Alternatives | "**Event-source** the gateway state … PayGuard owns none of the **events**" | Naming the rejected design pattern |

### Frozen architecture — verified untouched

| Item | Verified |
|---|---|
| 8 `PaymentStatus`, 10 `OrderStatus` | PASS |
| 80-cell decision table, uniform row width | PASS |
| 16 `FindingType`s, §4.4 covering every one with no extras | PASS |
| INV-01 … INV-13 contiguous | PASS |
| `ReconciliationDemo.main` | PASS |
| `PaymentObservation.status` authoritative; history as evidence | PASS |
| `Optional<Money>` exposure; severity from `FindingType` alone | PASS |
| No `Money.minus()`, no Scope-A `graceCeiling` in any live text | PASS |
| 16 ADR records, ADR-008 superseded | PASS |
| Links resolve; code fences balanced; 11 diagrams | PASS |

---

## Readiness

### Scope A: **READY FOR IMPLEMENTATION**

Zero live-specification contradictions. The architecture is frozen as listed, and the package is one
authoritative design.

Scope B unchanged: NOT READY, gated on discovery precision (OI-10, OI-11), rate limits (OI-06), backfill
bounds (OI-12), refund structure (OI-13) and, decisively, demand evidence (OI-02).
