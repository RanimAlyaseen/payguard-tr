# Requirements

**Scope tags:** `SVV` = in the academic Java core, due 24 Nov. `MVP` = commercial first release.
`FUTURE` = platform stage, deliberately not now.

**Status tags:** `CONFIRMED` (follows from the assignment or from verified provider behaviour),
`ASSUMPTION` (a design choice we are making that could be wrong), `NEEDS-VALIDATION` (depends on a customer or
provider fact we do not have).

---

## 1. Functional requirements — academic core (Scope A)

Eight requirements. This is the whole of the 24 November deliverable. Anything not listed here is out of scope
for the assignment, however desirable.

### FR-01 — Classify a payment/order pair

| | |
|---|---|
| **Description** | Given one payment observation, one order observation, a transaction history and a policy, return exactly one finding |
| **Rationale** | This is the product's core judgement; everything else is plumbing around it |
| **Inputs** | `ReconciliationInput` (payment, order, `history`, policy, `evaluatedAt`) |
| **Preconditions** | Input is non-null; policy is valid; amounts are non-negative |
| **Behaviour** | Evaluate rules in a fixed priority order; return the first that applies; `MATCHED` if none |
| **Postconditions** | Exactly one `FindingType`; inputs unmutated; `decisionPath` populated |
| **Acceptance** | Every cell of the decision table in §4 yields its specified finding; no input produces null or an exception other than the documented validation errors |
| **Priority** | Must |
| **Scope** | SVV, MVP (reused unchanged) |
| **Status** | CONFIRMED |

### FR-02 — Compare amounts with tolerance, allowing instalment excess

| | |
|---|---|
| **Description** | Flag `AMOUNT_MISMATCH` when the **requested** amount differs from the order total by more than the tolerance. Do not flag when only the **collected** amount exceeds the requested amount and an instalment count is present |
| **Rationale** | PayTR documents that the collected total can legitimately exceed the requested amount on instalments. Comparing the wrong pair of amounts would make the product cry wolf on a large share of real Turkish card payments |
| **Inputs** | `requestedAmount`, `collectedAmount`, `orderTotal`, `installmentCount`, `amountToleranceMinorUnits`, `allowInstallmentExcess` |
| **Preconditions** | All amounts same currency (else FR-03 applies first) |
| **Behaviour** | Three independent comparisons, in this order. **(a) Order agreement:** `abs(requested − orderTotal) > tolerance` → `AMOUNT_MISMATCH`, reason `order_total_disagrees`. **(b) Over-collection:** `collected > requested` → not a finding when `installmentCount > 0` and `allowInstallmentExcess`; otherwise `AMOUNT_MISMATCH`, reason `over_collected_without_installments`. **(c) Under-collection:** `collected < requested − tolerance` → `AMOUNT_MISMATCH`, reason `under_collected`, **regardless of instalment count** — the documented instalment allowance is one-directional, covering a collected total *above* the requested amount only. If more than one comparison fires, the reported `amountAtRisk` is the largest absolute difference and every reason is recorded |
| **Postconditions** | No finding raised for a legitimate instalment payment. Under-collection is never excused by instalments |
| **Acceptance** | Difference of exactly `tolerance` is accepted; `tolerance + 1` is flagged; a 12-instalment payment with excess collected is not flagged; the same excess with zero instalments is flagged; under-collection by `tolerance + 1` is flagged with 12 instalments present |
| **Priority** | Must |
| **Scope** | SVV, MVP |
| **Status** | CONFIRMED (provider behaviour verified) |

### FR-03 — Reject and report currency mismatch

| | |
|---|---|
| **Description** | When order and payment currencies differ, return `CURRENCY_MISMATCH` without attempting arithmetic |
| **Rationale** | PayTR supports five currencies; silently comparing across them would be a financial-loss bug |
| **Behaviour** | The check runs **only when both observations are present and each carries a currency**. If either side is `ABSENT`, or a present observation has no currency (PayTR omits `currency` on a failed callback — see `provider-capabilities.md` §1.1), the check is **skipped** and evaluation proceeds to the decision table; it is not a mismatch and not an error. When both currencies are present and differ → `CURRENCY_MISMATCH`, and no amount comparison is attempted. `Money` arithmetic across currencies throws (INV-04) |
| **Acceptance** | TRY order against EUR payment yields `CURRENCY_MISMATCH` and never an amount finding; an `ABSENT` payment against a TRY order is **not** a currency mismatch; a present payment with a null currency is not a currency mismatch and does not throw |
| **Priority** | Must |
| **Scope** | SVV, MVP |
| **Status** | CONFIRMED |

### FR-04 — Apply a grace period measured from domain time

**History.** Revision 0.1 computed age from `max(observedAt, lastModifiedAt)`, where `observedAt` is the
moment PayGuard *retrieved* the record, so every poll reset the window and a real discrepancy would sit inside
grace forever. Revision 0.2 fixed that and added a `graceCeiling` as a second safeguard. **Revision 0.3 removes
the ceiling**, for the reason set out in FR-04.3.

#### FR-04.1 — Four time concepts

Revision 0.2 carried five. Removing the ceiling removes one (`firstDetectedAt`). Of the four that remain, three
may influence a rule.

| Concept | Field | Origin | May a rule read it? |
|---|---|---|---|
| **Transaction occurrence** | `PaymentObservation.transactionOccurredAt` | The gateway's own record of when the transaction happened — PayTR `payment_date`, `YYYY-MM-DD hh:mm:ss`, from the Status Inquiry API | **Yes** |
| **Business-state change** | `OrderObservation.businessStateChangedAt` | **In Scope A this is the order's creation time, and nothing else.** See FR-04.3 | **Yes** |
| **Source observation / retrieval** | `*.observedAt` | When PayGuard fetched the record | **No — provenance only** |
| **Evaluation** | `ReconciliationInput.evaluatedAt` | The instant being evaluated for, injected (INV-02) | **Yes** |

`OrderObservation.lastModifiedAt` is retained for watermarking and diagnostics and is **not** a business-state
time: WooCommerce's `date_modified` advances on any edit, including a staff note, so using it would reintroduce
a restartable window by a second route.

#### FR-04.2 — The rule

| | |
|---|---|
| **Description** | A disagreement younger than the grace period yields `WITHIN_GRACE_PERIOD` |
| **Rationale** | The two systems are eventually consistent, so a disagreement seen seconds after checkout means nothing. iyzico's first webhook arrives 10–15 s after the attempt and PayTR retries after a minute |
| **Inputs** | `evaluatedAt`, `transactionOccurredAt`, `businessStateChangedAt`, `gracePeriod` |
| **Preconditions** | `gracePeriod` positive; no supplied domain time later than `evaluatedAt + clockSkewTolerance` (FR-08) |
| **Behaviour** | `referenceInstant = max(transactionOccurredAt, businessStateChangedAt)`, ignoring absent values; if both are absent the grace rule cannot apply and evaluation proceeds. `discrepancyAge = evaluatedAt − referenceInstant`. `discrepancyAge < gracePeriod` → `WITHIN_GRACE_PERIOD`; otherwise evaluate normally |
| **Postconditions** | For a fixed pair of observations, `discrepancyAge` is strictly increasing in `evaluatedAt`. No retrieval time and no arrival order appears in the computation (INV-11) |
| **Acceptance** | Boundaries at `gracePeriod − 1ms`, exactly `gracePeriod`, `gracePeriod + 1ms`; zero age; both domain times absent. **Non-suppression test:** re-evaluating the same discrepant pair 100 times with a fresh `observedAt` each time and an advancing `evaluatedAt` must leave grace exactly once and never return to it |
| **Priority** | Must · **Scope** SVV, MVP · **Status** CONFIRMED |

#### FR-04.3 — Why the grace ceiling is removed from Scope A

Revision 0.2 added a `graceCeiling` keyed on `firstDetectedAt`, to stop a *legitimate* business-state change
from restarting the window repeatedly. Review found that `firstDetectedAt` was associated with a finding key
containing the `FindingType`, so a discrepancy that changed type would obtain a new `firstDetectedAt` and
restart the ceiling. The guarantee was therefore false as specified.

The fix could be either a correctly-keyed discrepancy episode or removal. Removal is correct for Scope A, for
three reasons:

1. **The original defect is already fully fixed without it.** Polling cannot restart the window because
   neither domain time moves when PayGuard merely looks again. That is provable inside the pure core, needs no
   caller cooperation, and is now INV-12.
2. **The ceiling's guarantee cannot live in Scope A at all.** The core is pure and holds no state between
   calls, so episode identity would have to be maintained by the caller. An invariant whose truth depends on
   code outside the module is not an invariant of the module — it is a hope with a number attached. Keeping it
   would mean shipping exactly the unprovable guarantee this review objected to.
3. **In Scope A the residual risk does not exist.** `businessStateChangedAt` is the order's **creation time**,
   which never advances. So `referenceInstant` is fixed for a given pair and `discrepancyAge` is strictly
   increasing. There is nothing left for a ceiling to protect against.

**The risk is real only in the MVP**, where an adapter might supply a moving `businessStateChangedAt`. It is
recorded as FR-33 with the episode identity defined, so the MVP cannot reinvent it incorrectly.

**What this removes from Scope A:** one policy value, one input field, three boundary cases, and one invariant
that could not be proved. Net simplification, which is the outcome the review asked to prefer.

### FR-05 — Transaction-record identity and deduplication

**What a record is, and what it is not.** Revision 0.2 called these `PaymentEvent`, a name that implies a
notification stream and left open whether it meant webhook payloads, gateway history, or both. Review asked
for that to be settled. It is settled as follows, and the type is renamed to make the answer unmistakable.

`TransactionRecord` is **a row of the gateway's own transaction history** — in PayTR's case a row returned by
the Transaction Detail service for one `siparis_no`, carrying `islem_tipi`, amounts and `islem_tarihi`
([docs](https://dev.paytr.com/en/islem-dokumu)).

A webhook notification is **not** a `TransactionRecord` and does not enter the core. Under the approved
query-first architecture PayGuard does not receive PayTR's callback at all — it goes to the merchant's own
Notification URL — and iyzico's webhook is a **run trigger** only (ADR-004). Mixing the two concepts is what
produced the contradiction this revision fixes.

#### FR-05.1 — Identity is provider-specific and computed by the adapter

The core receives an opaque `recordIdentity` string and **never computes it**.

| Provider | Identity | Basis |
|---|---|---|
| PayTR | **Derived content digest** | The Transaction Detail service returns no row identifier. Digest over the significant fields of a row: `merchantOrderRef`, `islem_tipi` mapped to a canonical kind, amount, currency, `islem_tarihi` |
| iyzico | Provider identifier where the reporting service supplies one, else a digest by the same rule | **[U]** Whether its reporting rows carry a stable per-row identifier is unverified (UNK-12) |

**Derived content digest:** a stable hash over semantically significant fields only. Transport-level fields and
retrieval metadata are excluded, so re-retrieving the same history row digests identically. One shared digest
function, so two adapters cannot disagree about identity.

**Consequence:** with a derived digest, two rows differing in any significant field have *different* identities
by construction, so "same identity, different content" cannot arise for a digest-based provider. FR-05.3 is
reachable only where a provider supplies its own row identifier and reuses it across differing content.

#### FR-05.2 — Identical duplicates collapse

| | |
|---|---|
| **Description** | Records sharing a `recordIdentity` collapse to one for all downstream use |
| **Rationale** | Overlapping discovery windows (ADR-014) deliberately re-retrieve rows, so the same row arrives more than once by design |
| **Behaviour** | Group by `recordIdentity`; a group whose members are content-identical contributes one record. Retrieval count kept for audit |
| **Acceptance** | A list with one record repeated five times yields the same finding as the list with it once (INV-06) |
| **Priority** | Must · **Scope** SVV, MVP · **Status** CONFIRMED |

#### FR-05.3 — Conflicting records with the same identity are a data-integrity fault

| | |
|---|---|
| **Description** | If a group shares a `recordIdentity` but differs in a significant field, return `INSUFFICIENT_DATA`, reason `conflicting_records_same_identity`, recording every conflicting member |
| **Rationale** | There is no defensible basis for choosing between two records a provider claims are the same. Choosing by position makes the verdict depend on retrieval order; choosing by content invents a precedence the provider never stated |
| **Behaviour** | No record in a conflicting group contributes to duplicate detection or to evidence |
| **Acceptance** | Two same-identity records with different amounts yield `INSUFFICIENT_DATA` in **every** permutation of the input list |
| **Priority** | Must · **Scope** SVV, MVP · **Status** CONFIRMED |

#### FR-05.4 — Distinct capture records indicate a duplicate payment

| | |
|---|---|
| **Description** | Two or more records with **different** identities that both represent a capture for one `merchantOrderRef` → `DUPLICATE_PAYMENT` |
| **Rationale** | Two genuinely distinct captures means the customer was charged twice. This is the product's most valuable finding |
| **Behaviour** | Counted over the deduplicated set, so it is a property of the set and not of list order. **If no history is supplied, duplicate captures cannot be detected** — this is a documented limitation, not a silent gap, and `decisionPath` records that history was unavailable |
| **Acceptance** | Two distinct capture records → `DUPLICATE_PAYMENT` in every permutation; one record repeated five times → never; an empty history → never, with the limitation recorded |
| **Priority** | Must · **Scope** SVV, MVP · **Status** CONFIRMED |

### FR-06 — The authoritative gateway state, and what history is for

**This requirement contradicted ADR-004 in revision 0.2.** ADR-004 says a webhook payload is never the source
of truth and the architecture is query-first; FR-06 said "the last record in canonical order decides the
gateway state". Those cannot both hold. FR-06 is now the resolution.

| | |
|---|---|
| **Description** | `PaymentObservation.status`, obtained from the provider's query API, **is** the authoritative current gateway state. Transaction history is evidence and never overrides it |
| **Rationale** | The query API answers "what is the state now", which is the question the decision table asks. A history row answers "what happened once", which is a different question. Deriving current state by replaying history would reintroduce the event-sourced model the query-first architecture rejected, and would do it on data PayGuard cannot guarantee is complete |
| **Inputs** | `PaymentObservation.status`, the deduplicated record set |
| **Behaviour** | The decision table (§4.2) reads **only** `PaymentObservation.status`. History is used for exactly three things: **(a)** detecting two distinct capture records (FR-05.4); **(b)** `decisionPath` evidence and operator diagnostics; **(c)** deterministic presentation order. History is never consulted to determine, adjust or override the status |
| **Contradiction handling** | If the authoritative status and the history are mutually impossible — the clearest case being a status of `FAILED` or `ABSENT` alongside one or more capture records — the result is `INSUFFICIENT_DATA`, reason `gateway_history_contradicts_queried_state`, recording both. **The last record does not win, and neither does the status.** PayGuard reports that two endpoints of one provider disagree, which is what it actually knows |
| **Presentation order** | Sort by `occurredAt` ascending, tie-break on `recordIdentity` lexicographically. Both are set-intrinsic, so the order is a function of the set alone. `retrievalSequence` is audit-only and read by no rule (INV-11) |
| **Acceptance** | The verdict is identical for every permutation of the record list (INV-09); a `FAILED` status with a capture record yields `INSUFFICIENT_DATA` and never `CAPTURED`; a `CAPTURED` status with an empty history still yields the normal table verdict, with the missing history noted in `decisionPath` |
| **Priority** | Must · **Scope** SVV, MVP · **Status** CONFIRMED |

**Why this is reusable commercially, which the review asked to check.** The MVP obtains status from
`/odeme/durum-sorgu` and history from `/rapor/islem-dokumu` — two separate documented endpoints with different
shapes, one giving a current status and the other giving rows with no status field at all
(`provider-capabilities.md` §1.3, §1.4). The model matches that reality exactly: one authoritative snapshot,
plus optional corroborating history. A model in which history determined state would be unimplementable against
these endpoints, because the history service does not report status.

### FR-07 — Detect refund divergence (**reduced scope — see justification**)

| | |
|---|---|
| **Description** | Report `REFUND_DIVERGENCE` when the gateway's refund state and the order's refund state disagree in **either** direction. Scope A detects the divergence and its direction. It does **not** compare refunded amounts |
| **Rationale** | Both directions are real and have different remedies. Gateway refunded, shop not: the shop may ship goods for returned money. Shop refunded, gateway not: **the customer is owed money and has not received it**, which revision 0.1 missed entirely by mapping this cell to a type defined as the opposite direction |
| **Inputs** | Gateway refund state, order status, `refundedAmount` (recorded, not compared in Scope A) |
| **Behaviour** | Direction recorded in `reasons` as `gateway_only`, `shop_only`, or `partial_vs_full`. `amountAtRisk` follows §4.4, which is authoritative: `gateway_only` → the gateway's `refundedAmount` when available; `shop_only` → `Optional.empty()`, because the gateway refunded nothing and what the customer is owed is a shop-side figure PayGuard does not hold; `partial_vs_full` → `Optional.empty()` in Scope A, because the comparison needs the refund arithmetic this requirement defers |
| **Acceptance** | Each of the three directions yields `REFUND_DIVERGENCE` with the correct reason; a gateway refund matching an order already `REFUNDED` yields no finding |
| **Priority** | **Should** (reduced from Must) |
| **Scope** | SVV (divergence only), MVP (full amount reconciliation) |
| **Status** | NEEDS-VALIDATION — depends on UNK-11 |

**Justified scope reduction, as the review invited.** Refund *amount* reconciliation is deferred from Scope A
to the MVP for a reason that is not about time: the data it would need is unverified. The Status Inquiry
response's `returns` block exists, but its per-item structure is not documented (UNK-11), and the Transaction
Detail service's only refund indicator is `islem_tipi=S|I` with no amount-level refund fields
([docs](https://dev.paytr.com/en/islem-dokumu) — see `provider-capabilities.md` §1.4). Building
Scope A's rules on an assumed structure would be exactly the failure the review's final self-review check
forbids: treating unverified provider capability as proven behaviour.

What is preserved: three directional cases across the decision table, a rule with real branching, and honest
test depth. What is deferred: partial-refund amount arithmetic, which would otherwise need six boundary cases
built on a guessed field layout. This also removes one source of the type proliferation the audit in §4
would otherwise have produced.

### FR-08 — Validate input and fail explicitly

| | |
|---|---|
| **Description** | Reject malformed input with a typed error rather than producing a verdict |
| **Rationale** | A reconciler that guesses on bad data is worse than one that stops |
| **Behaviour** | These raise `InvalidReconciliationInputException` naming the offending field: a `null` required field; a negative amount; a negative instalment count; a domain time later than `evaluatedAt + clockSkewTolerance`; a non-positive `gracePeriod`; a negative `amountToleranceMinorUnits`. **Arithmetic overflow** in `Money.plus` raises `MoneyOverflowException` (a subclass of `PayGuardCoreException`): `Math.addExact` detects a `long` overflow rather than wrapping silently. An unrecognised provider status is *not* an input error — it yields `INSUFFICIENT_DATA` (INV-07) |
| **Acceptance** | Each negative case raises the typed exception naming the field; `Long.MAX_VALUE` plus one minor unit raises `MoneyOverflowException` and never returns a wrapped value; a domain time within `clockSkewTolerance` of `evaluatedAt` is accepted, one beyond it is rejected; no `NullPointerException` and no raw `ArithmeticException` escapes the module |
| **Note on the valid domain** | FR-08 defines the boundary of `reconcile`'s **valid domain**. INV-01's totality claim applies to that domain, not to malformed input — see INV-01 |
| **Priority** | Must |
| **Scope** | SVV, MVP |
| **Status** | CONFIRMED |

---

## 2. Functional requirements — commercial MVP (Scope B)

Summarised; each would be expanded before implementation, which is after the deadline.

| ID | Requirement | Priority | Status |
|---|---|---|---|
| FR-20 | Connect a store by storing read-scoped gateway and platform credentials | Must | CONFIRMED |
| FR-21 | **Shop-first sweep:** fetch orders changed since the last run (WooCommerce `modified_after`) | Must | CONFIRMED |
| FR-21b | **Gateway-first sweep:** enumerate gateway transactions over consecutive date windows of at most 3 days using the Transaction Detail service, with no order reference, and look up each returned `siparis_no` in the shop. This is the only way `PAYMENT_WITHOUT_ORDER` is detectable | **Must** | CONFIRMED (capability verified; field precision needs integration testing — UNK-09, UNK-10) |
| FR-21c | Chunk any window longer than 3 days, and track per-window completion so a partial sweep never implies absence | Must | CONFIRMED |
| FR-22 | **Confirm** each candidate by Status Inquiry on its order reference, to obtain canonical status, a precise `payment_date` and the `returns` block — because the discovery service carries no status field and a date-granular transaction date | Must | CONFIRMED |
| FR-23 | Persist every fetched record as an immutable observation with its retrieval time | Must | ASSUMPTION |
| FR-24 | Run reconciliation on a schedule and store findings | Must | CONFIRMED |
| FR-25 | Notify the merchant of new HIGH findings, batched | Must | ASSUMPTION |
| FR-26 | Let a user acknowledge, snooze or annotate a finding | Must | ASSUMPTION |
| FR-27 | Show the evidence behind a finding: both observations and the decision path | Must | CONFIRMED |
| FR-28 | Record an audit entry for every state change and every credential use | Must | CONFIRMED |
| FR-29 | Detect and record a missed scheduled run | Must | CONFIRMED |
| FR-30 | Agency view across multiple stores | Should | NEEDS-VALIDATION |
| FR-31 | Re-run reconciliation with a changed policy without losing prior findings | Should | ASSUMPTION |
| FR-32 | Ingest a verified iyzico webhook as a trigger, never as truth | Could | CONFIRMED |
| FR-33 | **If the MVP supplies a `businessStateChangedAt` that can advance after order creation, it must also maintain a discrepancy-episode identity and reinstate a grace ceiling.** Episode key is `(storeId, merchantOrderRef)` — **independent of `FindingType`**, so a discrepancy that changes type keeps one episode. An episode opens when the pair first becomes inconsistent and closes only when it becomes genuinely consistent (`MATCHED`). While an episode is open past the ceiling, `WITHIN_GRACE_PERIOD` may not be returned | Must, **if and only if** the trigger condition holds | CONFIRMED (see FR-04.3) |

FR-32 is `Could` on purpose: webhook signature verification needs per-account activation by iyzico, so it
cannot be a precondition of onboarding.

---

## 3. Non-functional requirements

Measurable where honest. Where a number would be invented, the requirement says what must be measured instead.

| ID | Attribute | Requirement | Acceptance | Scope |
|---|---|---|---|---|
| NFR-01 | Correctness | The core is a total, pure function satisfying INV-01 to INV-13 | Decision-table tests all pass; property test for INV-06 and INV-09 | SVV |
| NFR-02 | Correctness | No floating-point type appears in the money path | Static check: no `double`/`float`/`BigDecimal` in `model` package | SVV |
| NFR-03 | Testability | The core runs with no network, database, filesystem or clock access | Tests pass offline; no mocking framework needed for core tests | SVV |
| NFR-04 | Testability | Line and branch coverage measured by JaCoCo; the report discusses what coverage cannot show | Report includes the measured figures and the limitation discussion | SVV |
| NFR-05 | Reliability | Re-processing any ingested record is idempotent | Integration test ingests the same payload twice, one observation results | MVP |
| NFR-06 | Reliability | An interrupted run leaves no partial finding visible | Kill-mid-run test shows the run marked failed, no findings published | MVP |
| NFR-07 | Recoverability | A failed run is retried without duplicating findings | Retry test produces the same finding set | MVP |
| NFR-08 | Availability | PayGuard is a monitor, not a payment path. Its unavailability must never affect the merchant's checkout | Architecture review: no component sits in the merchant's request path | MVP |
| NFR-09 | Availability | Target to be set from pilot data, not asserted now. Measure: successful scheduled runs ÷ scheduled runs, per store | Metric exists and is reported before any SLA is offered | MVP |
| NFR-10 | Security | Provider credentials encrypted at rest, decrypted only in the adapter, never logged | Secret-scanning test over log output; code review checklist item | MVP |
| NFR-11 | Security | Every inbound webhook is signature-verified or rejected | Test with tampered signature returns rejection and records the attempt | MVP |
| NFR-12 | Security | No mutating provider endpoint is reachable from the codebase | Build-time check: the refund/cancel endpoints are absent from the adapter allowlist | MVP |
| NFR-13 | Privacy | No card number beyond the provider's masked value, no CVV, no full cardholder data stored | Data-model review; schema has no column for them | MVP |
| NFR-14 | Privacy | Personal data retention is bounded and configurable per store | Retention job exists and is tested | MVP |
| NFR-15 | Auditability | Every finding can be explained from stored observations alone, without re-querying the provider | Test reconstructs a finding from the database | MVP |
| NFR-16 | Auditability | Changing a policy does not rewrite historical findings | Findings store the policy version used | MVP |
| NFR-17 | Maintainability | Adding a gateway requires implementing one adapter interface and touches no rule code | Second adapter added without changes under `rules/` | MVP |
| NFR-18 | Performance | Reconciling one pair is O(n log n) in transaction records and allocates no I/O | Micro-benchmark on 10,000 pairs recorded as a baseline, not a promise | SVV |
| NFR-19 | Performance | Provider call volume per run is bounded and recorded; polling respects limits once UNK-01/02 are answered | Rate-limit configuration exists; run records call counts | MVP |
| NFR-20 | Scalability | Per-store work is independent, so stores can be processed in parallel | No shared mutable state across stores | MVP |
| NFR-21 | Usability | A finding states what happened, the money at risk, and what to do, without jargon | Five merchants read three findings unaided in the pilot | MVP |
| NFR-22 | Operability | Missed runs, provider failures and queue depth are visible | Dashboard exists before the pilot | MVP |

NFR-09 and NFR-19 deliberately refuse to state a number. Inventing "99.9% availability" or "polls every 30
seconds" before measuring would be a fabrication, and rate limits are unknown (UNK-01, UNK-02).

---

## 4. Business rules and the decision table

### 4.0 What the audit changed

Every cell was re-derived from the functional requirements rather than checked against the previous table.
Six defects came out of that, and the count itself was wrong twice.

| # | Defect in revision 0.1 | Correction |
|---|---|---|
| A-1 | **`OrderStatus` had no `UNKNOWN` value.** WooCommerce order statuses are extensible — plugins add custom ones — so an unrecognised order status is not a hypothetical. Without `UNKNOWN`, an adapter meeting a custom status would have to guess, violating INV-07 | `UNKNOWN` added. The grid is **8 × 10 = 80** cells, not 72 |
| A-2 | **`CAPTURED` payment against a `REFUNDED` order was mapped to `REFUND_NOT_REFLECTED`**, whose own definition is "the gateway shows a refund and the shop does not" — the exact opposite of this cell. The real meaning is that the shop refunded and the gateway did not, so **the customer is owed money and has not received it**. The most serious single error in the table | `REFUND_NOT_REFLECTED` replaced by `REFUND_DIVERGENCE`, which carries a direction in `reasons` |
| A-3 | **`DUPLICATE_PAYMENT` sat below `WITHIN_GRACE_PERIOD` in the priority list**, so two captures inside the grace window were reported as "too early to judge". A double charge is a double charge immediately; it does not converge | Priority reordered: duplicates, currency and insufficient-data all precede grace |
| A-4 | **`STALE_IN_PROGRESS` was used unconditionally**, though the name asserts a threshold has passed. A payment in progress for ten seconds is normal | Split into `IN_PROGRESS_PENDING` and `STALE_IN_PROGRESS`; the same split applied to authorisations |
| A-5 | **`AUTHORIZED` against an unpaid order returned `WITHIN_GRACE_PERIOD` at any age** (recorded as OI-04). A status meaning "too early to judge" cannot be true forever, and an authorisation that never captures holds funds on a customer's card | `AUTHORIZATION_PENDING` below threshold, `STALE_AUTHORIZATION` above. OI-04 closed |
| A-6 | **Eleven cells were `—`, meaning "handled by a later rule"**, which is not a specification | Every cell now carries either a finding type or `[A]`, which has one defined meaning (§4.3) |

### 4.1 Rule priority

Evaluated in order; the first match wins. Priority is itself a design decision, because several conditions can
hold at once.

| # | Rule | Why here |
|---|---|---|
| 1 | Input validation (FR-08) | Raises; does not return a finding |
| 2 | `INSUFFICIENT_DATA` — any of: either status `UNKNOWN`; conflicting same-identity records (FR-05.3); **history contradicting the authoritative status** (FR-06) | We cannot judge what we cannot read, and we must not pick a winner between two sources that disagree. Guessing here would be the worst failure |
| 3 | `CURRENCY_MISMATCH` (FR-03, when both currencies present) | Currencies do not converge with time, and comparing across them is meaningless rather than merely wrong |
| 4 | `DUPLICATE_PAYMENT` (FR-05.4) | **Moved above grace in this revision.** A second capture is a completed double charge, not an inconsistency awaiting settlement |
| 5 | `WITHIN_GRACE_PERIOD` (FR-04) | Everything below this line is a disagreement that *might* resolve itself. Everything above it will not |
| 6 | The state-pair decision table (§4.2) | |
| 7 | `AMOUNT_MISMATCH` (FR-02), for cells marked `[A]` | Only meaningful once the two sides agree on what state they are in |
| 8 | `MATCHED` | |

The line between 4 and 5 is the substantive one: rules above it concern facts that will not change by waiting;
rules below it concern states that may still converge.

### 4.2 The decision table — 80 cells

**Preconditions.** The table is reached only after rules 1 to 5. So: both statuses are recognised; no
same-identity conflict exists; currencies agree or one side is absent; there is no duplicate capture; and the
grace period has elapsed. It also assumes **both observations came
from successful, complete queries** — a failed or truncated fetch never reaches the core at all (FM-06, FM-10).

**Notation.** `[A]` = the two sides agree on state; proceed to the amount comparison of rule 7, then `MATCHED`.
`[T:x/y]` = conditional on age against the named stale threshold: `x` below, `y` at or above. No cell is blank.

| Payment ↓ / Order → | `ABSENT` | `UNKNOWN` | `DRAFT` | `AWAITING_PAYMENT` | `PAYMENT_UNCONFIRMED` | `PAID_UNFULFILLED` | `FULFILLED` | `CANCELLED` | `REFUNDED` | `FAILED` |
|---|---|---|---|---|---|---|---|---|---|---|
| `CAPTURED` | PAYMENT_WITHOUT_ORDER | INSUFFICIENT_DATA | PAYMENT_WITHOUT_ORDER | ORDER_UNPAID_DESPITE_PAYMENT | ORDER_UNPAID_DESPITE_PAYMENT | `[A]` | `[A]` | PAYMENT_AGAINST_CANCELLED_ORDER | REFUND_DIVERGENCE | STATUS_CONFLICT |
| `AUTHORIZED` | `[T:AUTHORIZATION_PENDING/STALE_AUTHORIZATION]` | INSUFFICIENT_DATA | `[T:AUTHORIZATION_PENDING/STALE_AUTHORIZATION]` | `[T:AUTHORIZATION_PENDING/STALE_AUTHORIZATION]` | `[T:AUTHORIZATION_PENDING/STALE_AUTHORIZATION]` | STATUS_CONFLICT | STATUS_CONFLICT | `[T:AUTHORIZATION_PENDING/STALE_AUTHORIZATION]` | STATUS_CONFLICT | `[T:AUTHORIZATION_PENDING/STALE_AUTHORIZATION]` |
| `FAILED` | MATCHED | INSUFFICIENT_DATA | MATCHED | MATCHED | STATUS_CONFLICT | ORDER_PAID_WITHOUT_PAYMENT | ORDER_PAID_WITHOUT_PAYMENT | MATCHED | REFUND_DIVERGENCE | MATCHED |
| `REFUNDED` | PAYMENT_WITHOUT_ORDER | INSUFFICIENT_DATA | PAYMENT_WITHOUT_ORDER | REFUND_DIVERGENCE | REFUND_DIVERGENCE | REFUND_DIVERGENCE | REFUND_DIVERGENCE | MATCHED | MATCHED | STATUS_CONFLICT |
| `PARTIALLY_REFUNDED` | PAYMENT_WITHOUT_ORDER | INSUFFICIENT_DATA | PAYMENT_WITHOUT_ORDER | REFUND_DIVERGENCE | REFUND_DIVERGENCE | REFUND_DIVERGENCE | REFUND_DIVERGENCE | REFUND_DIVERGENCE | REFUND_DIVERGENCE | STATUS_CONFLICT |
| `IN_PROGRESS` | `[T:IN_PROGRESS_PENDING/STALE_IN_PROGRESS]` | INSUFFICIENT_DATA | `[T:IN_PROGRESS_PENDING/STALE_IN_PROGRESS]` | `[T:IN_PROGRESS_PENDING/STALE_IN_PROGRESS]` | `[T:IN_PROGRESS_PENDING/STALE_IN_PROGRESS]` | STATUS_CONFLICT | STATUS_CONFLICT | `[T:IN_PROGRESS_PENDING/STALE_IN_PROGRESS]` | STATUS_CONFLICT | `[T:IN_PROGRESS_PENDING/STALE_IN_PROGRESS]` |
| `ABSENT` | INSUFFICIENT_DATA | INSUFFICIENT_DATA | MATCHED | MATCHED | STATUS_CONFLICT | ORDER_PAID_WITHOUT_PAYMENT | ORDER_PAID_WITHOUT_PAYMENT | MATCHED | ORDER_PAID_WITHOUT_PAYMENT | MATCHED |
| `UNKNOWN` | INSUFFICIENT_DATA | INSUFFICIENT_DATA | INSUFFICIENT_DATA | INSUFFICIENT_DATA | INSUFFICIENT_DATA | INSUFFICIENT_DATA | INSUFFICIENT_DATA | INSUFFICIENT_DATA | INSUFFICIENT_DATA | INSUFFICIENT_DATA |

### 4.3 Every notation and non-obvious cell, explained

**`[A]` — the two sides agree.** Two cells only: `CAPTURED` with `PAID_UNFULFILLED` or `FULFILLED`. The state
pair raises no finding, so evaluation continues to rule 7 (amount comparison) and then `MATCHED`. `[A]` is not
"no finding"; it is "no *status* finding, now check the money".

**`[T:x/y]` — conditional on a stale threshold.** Twelve cells. Below the threshold the gateway is in a
legitimate intermediate state and the finding is informational; at or above it, something is stuck. The two
thresholds are different values for different reasons: `staleAuthorizationThreshold` is about funds reserved on
a customer's card, `staleInProgressThreshold` about PayTR never having received an `OK`.

**The `UNKNOWN` row and column (18 cells)** are unreachable through the normal path, because rule 2
short-circuits them. They are specified anyway so the table is total over the enums and `TotalityTest` can
assert it without a carve-out. A cell reachable only by a bug should still have a defined answer.

**Cells a reviewer should challenge:**

| Cell | Verdict | Reasoning |
|---|---|---|
| `CAPTURED` + `REFUNDED` | REFUND_DIVERGENCE (`shop_only`) | The shop refunded; the gateway shows no refund. The customer is owed money. This was A-2 |
| `CAPTURED` + `FAILED` | STATUS_CONFLICT | Money was taken and the shop recorded failure. Materially similar harm to `ORDER_UNPAID_DESPITE_PAYMENT`, but operationally distinct: a failed order is likely to have been re-attempted, so a duplicate charge may follow. The reason records it |
| `CAPTURED` + `DRAFT` | PAYMENT_WITHOUT_ORDER | A block-checkout draft is not an order the merchant can fulfil, so this is nearer "no order" than "unpaid order" |
| `REFUNDED` + `ABSENT`/`DRAFT` | PAYMENT_WITHOUT_ORDER | A payment existed with no order. `amountAtRisk` is **present and zero** — `collected.absoluteDifference(refunded)` — because the money was returned. **Severity remains HIGH**, since severity is a function of `FindingType` alone (INV-08): the money is back, but the two systems' records diverged, which the merchant should see |
| `REFUNDED` + `CANCELLED` | MATCHED | Cancelled and refunded is a consistent, correctly-handled outcome. Was `—` |
| `PARTIALLY_REFUNDED` + `REFUNDED` | REFUND_DIVERGENCE (`partial_vs_full`) | The shop refunded in full, the gateway only in part: the customer is owed the remainder. Was `—` |
| `PARTIALLY_REFUNDED` + `CANCELLED` | REFUND_DIVERGENCE (`partial_vs_full`) | Order cancelled, only part of the money returned. Was `—` |
| `FAILED` + `REFUNDED` | REFUND_DIVERGENCE (`shop_only`) | The shop refunded a payment the gateway never captured, so it may have paid out real money against nothing |
| `ABSENT` + `REFUNDED` | ORDER_PAID_WITHOUT_PAYMENT | The shop believes a payment arrived and was returned; the gateway has no record of either. The root problem is the believed payment |
| `ABSENT` + `PAYMENT_UNCONFIRMED` | STATUS_CONFLICT | Stock was reduced pending confirmation, yet the gateway has no record at all |
| `ABSENT` + `DRAFT` | MATCHED | **The most common row in any real store's data** — an abandoned checkout. It must never produce a finding |
| `AUTHORIZED` + `FULFILLED` | STATUS_CONFLICT | Goods shipped against an uncaptured authorisation |
| `IN_PROGRESS` + `PAID_UNFULFILLED` | STATUS_CONFLICT | Plausible and common: the shop processed the payment but PayTR never received the `OK`, so it still shows "Devam Ediyor". The reason says so, because the remedy is to re-acknowledge rather than to chase money |

### 4.4 Severity, action and financial exposure

**What was wrong.** Revision 0.2 made severity a function of `(FindingType, amountAtRisk)` and let a zero
amount reduce a HIGH type to LOW. But zero was overloaded: it meant both *confirmed no exposure* and *exposure
not computable*. The unsafe case the review named is exact — `CAPTURED` against a `REFUNDED` order is
`REFUND_DIVERGENCE(shop_only)`, where the customer may still be owed money, yet the gateway's `refundedAmount`
is zero, so the finding would have been demoted to LOW precisely when it mattered. BR-08 also contradicted
§4.3 about `PAYMENT_WITHOUT_ORDER`.

**The correction**, taking the review's option B because it is the simpler of the two offered and this is a
scope-controlled revision:

1. **Severity is a fixed function of `FindingType` alone.** No amount influences it. INV-08 restated.
2. **`amountAtRisk` is optional**, `Optional<Money>`. **Present** means a computed exposure, which may
   legitimately be zero. **Absent** means PayGuard cannot compute one. The two are now distinguishable.
3. **An absent amount never lowers severity.** A serious finding with unknown exposure stays serious.

| Finding type | Severity | Action | `amountAtRisk` |
|---|---|---|---|
| `MATCHED` | INFO | NONE | absent — nothing is at risk |
| `WITHIN_GRACE_PERIOD` | INFO | OBSERVE | absent — too early to say |
| `AUTHORIZATION_PENDING` | INFO | OBSERVE | **present:** `requestedAmount` — funds reserved on the customer's card |
| `IN_PROGRESS_PENDING` | INFO | OBSERVE | absent — the transaction's outcome is not yet known |
| `INSUFFICIENT_DATA` | **MEDIUM** | MANUAL_INVESTIGATION | **absent, by definition** — if we could compute exposure we would not be here |
| `STALE_AUTHORIZATION` | MEDIUM | ALERT_MERCHANT | **present:** `requestedAmount` |
| `STALE_IN_PROGRESS` | MEDIUM | ALERT_MERCHANT | absent — whether money moved is unknown |
| `AMOUNT_MISMATCH` | MEDIUM | ALERT_MERCHANT | **present:** the largest absolute difference among the comparisons that fired |
| `STATUS_CONFLICT` | MEDIUM | MANUAL_INVESTIGATION | absent — which side is correct is unknown, so any figure would be a guess |
| `PAYMENT_WITHOUT_ORDER` | **HIGH** | MANUAL_INVESTIGATION | **present when both known:** `collectedAmount.absoluteDifference(refundedAmount)`. This is **legitimately zero** for a fully refunded orphan payment, and severity stays HIGH regardless. Absent if either amount is unknown |
| `ORDER_UNPAID_DESPITE_PAYMENT` | **HIGH** | ALERT_MERCHANT | **present:** `collectedAmount` |
| `PAYMENT_AGAINST_CANCELLED_ORDER` | **HIGH** | MANUAL_INVESTIGATION | **present:** `collectedAmount` |
| `ORDER_PAID_WITHOUT_PAYMENT` | **HIGH** | MANUAL_INVESTIGATION | **present:** `orderTotal` |
| `REFUND_DIVERGENCE` | **HIGH** | MANUAL_INVESTIGATION | **by direction:** `gateway_only` → present, the gateway's `refundedAmount`. `shop_only` → **absent**; the gateway refunded nothing, and what the customer is owed is a shop-side figure PayGuard does not have. `partial_vs_full` → **absent** in Scope A, because the comparison needs the refund amount arithmetic deferred under FR-07 |
| `CURRENCY_MISMATCH` | **HIGH** | MANUAL_INVESTIGATION | **absent** — cross-currency arithmetic is forbidden (INV-04), so no exposure figure is computable without a rate PayGuard has no business inventing |
| `DUPLICATE_PAYMENT` | **HIGH** | MANUAL_INVESTIGATION | **present when computable:** the sum of the capture records beyond the first. Absent if history amounts are unavailable |

**Cells this changes, audited as the review required:**

| Case | Revision 0.2 | Revision 0.3 |
|---|---|---|
| `CAPTURED` + `REFUNDED` order | `REFUND_DIVERGENCE`, amount 0 → demoted to **LOW** | `REFUND_DIVERGENCE(shop_only)`, amount **absent**, severity **HIGH**. The unsafe demotion is gone |
| `REFUNDED` + `ABSENT` order | `PAYMENT_WITHOUT_ORDER`, "zero amount at risk" → **LOW** | `PAYMENT_WITHOUT_ORDER`, amount **present and zero**, severity **HIGH**. The contradiction with BR-08 is resolved: the amount *is* collected minus refunded, which is genuinely zero, and that no longer changes severity |
| `REFUNDED`/`PARTIALLY_REFUNDED` + `DRAFT` order | as above | same as above |
| `PARTIALLY_REFUNDED` + `CANCELLED`/`REFUNDED` order | `REFUND_DIVERGENCE`, amount from refund arithmetic | `REFUND_DIVERGENCE(partial_vs_full)`, amount **absent** in Scope A — FR-07 defers the arithmetic, and absent is the honest value |
| Any `CURRENCY_MISMATCH` | amount unspecified | **absent**, explicitly, with the reason |
| Any `INSUFFICIENT_DATA` | LOW | **MEDIUM**, amount absent. Raised because every route to it is now a real integrity problem — an unreadable status, two records contradicting each other, or two provider endpoints contradicting each other — not merely a missing optional field |
| Any `STATUS_CONFLICT` | amount unspecified | **absent**, explicitly |

**One consequence worth stating.** A fully refunded orphan payment is now HIGH severity with zero exposure.
That is deliberate: the money is back, but the shop's and the gateway's records diverged, which is an integrity
problem a merchant should see. Severity answers "how much should I care"; `amountAtRisk` answers "how much
money". Separating them is what makes both honest, and it is simpler than the coupling it replaces.

### 4.5 Other rules

| ID | Rule |
|---|---|
| BR-01 | Amounts are integers in minor units; the tolerance is also in minor units, default 0 |
| BR-02 | Two **distinct-identity** capture *records* for one `merchantOrderRef` are `DUPLICATE_PAYMENT` regardless of amounts (FR-05.4). With no history supplied, duplicates are undetectable and `decisionPath` says so |
| BR-03 | Repeats of one `recordIdentity` are never `DUPLICATE_PAYMENT` (FR-05.2) |
| BR-04 | Grace is measured from `max(transactionOccurredAt, businessStateChangedAt)` — **never from a retrieval time** (FR-04.1, INV-11) |
| BR-04b | The decision table reads **only** `PaymentObservation.status`. Transaction history never determines or adjusts gateway state (FR-06) |
| BR-05 | Defaults, all ASSUMPTION, all tuned from pilot data: `gracePeriod` 15 min; `staleInProgressThreshold` 24 h; `staleAuthorizationThreshold` 72 h; `clockSkewTolerance` 5 min. There is no `graceCeiling` in Scope A (FR-04.3) |
| BR-06 | `collectedAmount > requestedAmount` is expected when `installmentCount > 0`; a finding when it is 0. `collectedAmount < requestedAmount − tolerance` is **always** a finding (FR-02) |
| BR-07 | A stale threshold is measured from the same reference instant as grace, so it too cannot be restarted by polling |
| BR-07b | `businessStateChangedAt` in Scope A **is** the order's creation time. Any other value is an MVP decision that triggers FR-33 |
| BR-08 | `amountAtRisk` is `Optional<Money>` and is defined per `FindingType` in §4.4 — **that table is the single authoritative source**. Absent means not computable; present-and-zero means a computed zero exposure. Neither affects severity (BR-08b) |
| BR-08b | Severity is a function of `FindingType` alone (INV-08). No amount, present, absent or zero, changes it |
| BR-09 | A `test_mode` transaction never produces a merchant-facing finding; it is recorded and tagged. PayTR sends `test_mode=1` both in test mode and when a test is run in live mode, so live data contains test transactions |
| BR-10 | Policy is versioned; a finding records the policy version that produced it (NFR-16) |
| BR-11 | A discovered transaction whose `siparis_no` matches no order yields an `OrderObservation` with status `ABSENT` **only when the shop sweep for that window completed**; otherwise the pair is not evaluated (FM-10) |
