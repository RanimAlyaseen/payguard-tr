# Architecture Review Response — Revision 0.2

**Reviewed:** `SAD.md`, `requirements.md`, `testing-strategy.md` (revision 0.1)
**Status requested:** REVISION REQUIRED, no implementation
**This document:** issue by issue — what was identified, why it was wrong, what changed, which documents moved,
and what remains assumed.

Three of the six findings were defects that would have produced incorrect behaviour in production. Two of those
sat in requirements I had labelled CONFIRMED.

---

## 1. Payment discovery

**Identified.** The sequence discovered WooCommerce orders first and queried the gateway per order reference,
which cannot detect a successful payment with no store order.

**Root cause.** Not a modelling slip — an incomplete investigation. Revision 0.1 found PayTR's Status Inquiry
service (which needs a `merchant_oid`) and the dated `odeme-detayi` report, concluded that independent
enumeration was doubtful, and recorded it as *blocking unknown* OI-01 instead of reading the one remaining
service. I then designed around the limitation I had assumed. Recording an unknown is not a substitute for
resolving one, particularly when it blocks the product's headline capability.

**Correction.** Verified against PayTR's documentation: the **Transaction Detail** service (İşlem Dökümü),
`POST /rapor/islem-dokumu`, retrieves sales and refund transactions over a date range of **at most 3 days**,
takes `merchant_id`, `start_date`, `end_date`, `dummy` and `paytr_token`, and **`merchant_oid` is not a field
of it**. Each transaction returns `siparis_no` — the join key that makes orphan discovery possible.

Reconciliation is now **bidirectional**:

- **Sweep A, gateway-first:** walk 3-day windows, take each `siparis_no`, ask the shop whether that order
  exists. No order → `PAYMENT_WITHOUT_ORDER`.
- **Sweep B, shop-first:** orders changed since the watermark, as before, which catches the opposite direction.

Because the discovery service carries **no per-transaction status field** and its transaction date is
documented with a date-only example, discovery **finds candidates** and a second Status Inquiry call
**confirms** them with a canonical status and a precise `payment_date`. Discovery never assigns a
`PaymentStatus`.

**Documented versus needing integration testing** is tabulated step by step in ADR-014 §Decision. Documented:
date-range retrieval without an order reference, the 3-day cap, `siparis_no`, `islem_tipi` as the only refund
indicator, the absence of a status field. Needs testing: whether `islem_tarihi` ever carries a time (UNK-09),
whether non-completed transactions appear at all (UNK-10), how far back history goes (UNK-08), the structure of
`returns` (UNK-11). The `dummy=1` flag allows a first pass on all four without real transactions.

**Documents affected.** `provider-capabilities.md` §1.4 (new, full), §4, §5 (UNK-04 struck through, UNK-08 to
UNK-11 added); `SAD.md` §1, §5.1 (sequence rewritten), §13; new `adr/ADR-014-bidirectional-discovery.md`;
`adr/ADR-004` revised to rev 2; `requirements.md` FR-21b, FR-21c, FR-22, BR-11;
`failure-mode-analysis.md` FM-14, FM-15, FM-13 revised.

**Remaining assumptions.** That a 3-day walk is affordable against rate limits nobody has published (OI-06),
and that discovery's precision is good enough to keep confirmation calls to candidates rather than every
transaction (OI-10).

---

## 2. Grace-period semantics

**Identified.** The calculation used the latest observation timestamp; does repeated polling restart the window
indefinitely?

**Answer: yes, it did.** Age was `evaluatedAt − max(observedAt, lastModifiedAt)`, and `observedAt` is the
moment PayGuard *retrieved* the record. Every poll produced a fresh `observedAt`, so the age collapsed to near
zero on each run and a real discrepancy would have sat inside the grace window permanently. **More frequent
monitoring would have meant less detection** — the defect inverted the product.

**Root cause.** Two different ideas were carried in one field name. `observedAt` was introduced for
provenance — when did we fetch this — and then reused as a proxy for "when did something happen", which it
never was. The failure was not arithmetic; it was an unexamined conflation in the data model, and no test
could have caught it because no test asked the question.

**Correction.** Five time concepts are now distinct in the model, and only three may influence a rule:

| Concept | Field | May a rule read it? |
|---|---|---|
| Transaction occurrence | `PaymentObservation.transactionOccurredAt` (PayTR `payment_date`) | **Yes** |
| Business-state change | `OrderObservation.businessStateChangedAt`, defaulting to creation time | **Yes** |
| Source observation / retrieval | `*.observedAt` | **No — provenance only** |
| First detected discrepancy | `ReconciliationInput.firstDetectedAt` | **Yes, for the ceiling only** |
| Evaluation | `evaluatedAt` | **Yes** |

The rule: `referenceInstant = max(transactionOccurredAt, businessStateChangedAt)`;
`discrepancyAge = evaluatedAt − referenceInstant`. `WITHIN_GRACE_PERIOD` requires **both**
`discrepancyAge < gracePeriod` **and** (`firstDetectedAt` absent or
`evaluatedAt − firstDetectedAt < graceCeiling`).

**Why a ceiling as well.** The reference-time fix alone stops *polling* from restarting the window, because
neither domain time moves when PayGuard merely looks again. But a *legitimate* business-state change — a real
order edit, a re-attempted payment — does advance `businessStateChangedAt`, correctly, and repeated legitimate
changes could chain. The ceiling makes suppression impossible by construction rather than by argument, which is
what "deterministic policy" requires.

`OrderObservation.lastModifiedAt` is kept for watermarking and diagnostics and is explicitly **not** a
business-state time, because WooCommerce's `date_modified` advances on any edit — a staff note would otherwise
restart the window by a second route.

Two invariants added: **INV-11** no rule reads a retrieval time or an arrival order; **INV-12**
`discrepancyAge` is non-decreasing in `evaluatedAt` for fixed domain times, and the ceiling is absolute.

**Documents affected.** `requirements.md` FR-04 (rewritten as FR-04.1 and FR-04.2), BR-04, BR-05, BR-07;
`domain-model.md` §3b (new), §3 class diagram, INV-11, INV-12; `testing-strategy.md` traceability matrix,
§4 grace and ceiling boundaries, stale thresholds; `SAD.md` §1, §5.1, §5.4; `engineering-standards.md`
checklist and CI gates 9 and 10.

**Remaining assumptions.** `gracePeriod` 15 minutes and `graceCeiling` 6 hours are guesses (OI-05), which is
why both are versioned policy rather than constants. And `businessStateChangedAt` defaults to the order's
creation time because WooCommerce does not say *what* changed; a shop that exposed payment-specific state
transitions would allow something better.

---

## 3. Deduplication and order-independence

**Identified.** FR-05 and FR-06 were mutually inconsistent, and conflicting records must not be selected by
position in the input list.

**Root cause.** FR-05 said "first arrival wins" for a same-identity conflict; FR-06 and INV-09 asserted
permutation invariance. Those cannot both hold. Worse, the tie-break for equal `occurredAt` values was
`receivedSequence` — arrival order — so the **ordering rule itself** depended on the thing the invariant said
must not matter. I wrote the invariant and then, two requirements later, wrote the rule that broke it, because
I was thinking about duplicate *collapse* in one place and about *ordering* in another and never put them side
by side.

**Correction**, as the seven points the review asked for:

| Point | Resolution |
|---|---|
| **Provider-specific event identity** | iyzico supplies `iyziPaymentId`; identity is that plus canonical status |
| **Behaviour when provider event IDs are unavailable** | **PayTR supplies none** — its callback has no notification identifier. The adapter computes a **content digest** over significant fields only: `merchantOrderRef`, canonical status, collected amount, currency, payment type. Transport fields (`hash`, receipt time, headers) are excluded, so a retry digests identically. One shared digest function across adapters |
| **Identical duplicate handling** | Same-identity, content-identical events collapse to one; arrival count kept for audit (FR-05.2) |
| **Conflicting records with the same identity** | **`INSUFFICIENT_DATA`**, reason `conflicting_events_same_identity`, every value recorded. Not a choice between them. PayGuard genuinely does not know which is true, and reporting that is deterministic, permutation-invariant and honest (FR-05.3) |
| **Distinct successful captures** | Two or more *different-identity* captures for one order → `DUPLICATE_PAYMENT`, counted over the deduplicated set so it is a set property (FR-05.4) |
| **Stable ordering and tie-breaking** | Sort by `occurredAt`, **tie-break on `eventIdentity`** lexicographically. `receivedSequence` is audit-only (INV-11) |
| **Permutation invariance condition** | Stated precisely: for any two input lists that are permutations of one another and contain the same multiset of events, `reconcile` returns an identical finding including `decisionPath`. It holds because the sort key is derived entirely from set-intrinsic data |

A neat consequence: because identity is a function of significant content, "same identity, different content"
**cannot arise for a digest-based provider at all**. FR-05.3 is unreachable for PayTR and reachable only where
a provider supplies its own identifier and reuses it — a small surface, which is the right size for a rule
whose answer is "we cannot tell".

Alternatives rejected, with reasons, in ADR-015: status precedence (invents an order no provider states, and
would mask a genuine reversal), amount precedence (arbitrary, biased toward over-reporting), `merchant_oid` as
identity (would collapse two distinct captures and destroy `DUPLICATE_PAYMENT`).

**Documents affected.** `requirements.md` FR-05.1–5.4 and FR-06 (both rewritten); new
`adr/ADR-015-event-identity.md`; `domain-model.md` INV-09 restated, INV-11; `SAD.md` §5.2 (flowchart
rewritten), §5.4; `testing-strategy.md` traceability, event-list and identity partitions, negative suite,
test structure.

**Remaining assumptions.** The digest's significant-field set is a judgement: too wide breaks duplicate
collapse, too narrow could collide two real captures. It is specified, version-sensitive, and tested for
stability under a transport-field change and for distinctness across two real captures.

---

## 4. Decision table audit

**Identified.** Review all combinations independently; check the named cells; clarify every fallback cell;
do not claim completeness from cell count alone.

**Root cause.** Revision 0.1's table was assembled by filling a grid and then counted for completeness, rather
than derived cell by cell from the functional requirements. Counting found the missing `DRAFT` column in round
one; it could not find a cell whose verdict contradicted its own type definition.

**Correction.** Every cell re-derived from the FRs. Six defects, including the count twice:

| # | Defect | Correction |
|---|---|---|
| A-1 | **`OrderStatus` had no `UNKNOWN`.** WooCommerce statuses are extensible and plugins add custom ones, so an unrecognised shop status is expected, not hypothetical. Without it an adapter would have to guess, violating INV-07 | `UNKNOWN` added. The grid is **8 × 10 = 80**, not 72 |
| A-2 | **`CAPTURED` + `REFUNDED` order was mapped to `REFUND_NOT_REFLECTED`**, a type defined as "gateway shows a refund and the shop does not" — the exact opposite. The real meaning is that the shop refunded and the gateway did not, so **the customer is owed money and has not received it**. The most serious single error | `REFUND_DIVERGENCE`, carrying a direction (`gateway_only`, `shop_only`, `partial_vs_full`) in `reasons` |
| A-3 | **`DUPLICATE_PAYMENT` ranked below `WITHIN_GRACE_PERIOD`**, so two captures inside the grace window were reported as "too early to judge". A double charge does not converge | Priority reordered: insufficient-data, currency and duplicates all precede grace. The line between them is now explicit — above it, facts that will not change by waiting; below it, states that may still converge |
| A-4 | **`STALE_IN_PROGRESS` used unconditionally**, though its name asserts a threshold has passed | Split into `IN_PROGRESS_PENDING` / `STALE_IN_PROGRESS` |
| A-5 | **`AUTHORIZED` + unpaid order returned `WITHIN_GRACE_PERIOD` at any age** (previously OI-04). A status meaning "too early to judge" cannot hold forever, and an uncaptured authorisation holds funds on a customer's card | `AUTHORIZATION_PENDING` / `STALE_AUTHORIZATION`, 72-hour default. OI-04 closed |
| A-6 | **Eleven `—` cells**, meaning "handled by a later rule", which is not a specification | Every cell carries a verdict or one of two defined notations |

**Every notation now has one meaning**, documented in requirements §4.3: `[A]` = the two sides agree on state,
proceed to the amount comparison then `MATCHED` (two cells only); `[T:x/y]` = conditional on the named stale
threshold, `x` below and `y` at or above (fourteen cells). The `UNKNOWN` row and column (18 cells) are
unreachable because rule 2 short-circuits them, and are specified anyway so `TotalityTest` needs no carve-out.

Thirteen specific cells a reviewer should challenge are listed with their reasoning in §4.3, including each one
the review named.

Sixteen finding types, up from thirteen: three splits, one replacement of a mis-defined type.

**Documents affected.** `requirements.md` §4 (rewritten: §4.0 defect log, §4.1 priority, §4.2 the 80-cell
table, §4.3 every notation and non-obvious cell, §4.4 severity, §4.5 business rules); `domain-model.md`
FindingType table and `OrderStatus`; `testing-strategy.md` throughout.

**Remaining assumptions.** `CAPTURED` + `FAILED` order is typed `STATUS_CONFLICT` rather than
`ORDER_UNPAID_DESPITE_PAYMENT`; the harm is similar but a failed order is more likely to have been re-attempted,
so a duplicate charge may follow. That is a judgement worth challenging. And `REFUNDED` + `ABSENT` order relies
on the severity function dropping a HIGH type to LOW at zero amount at risk, which is INV-08 behaving as
intended rather than a special case.

---

## 5. Academic implementation boundary

**Identified.** The assignment requires a Java *application*; a pure library is not independently executable.

**Root cause.** I optimised for one constraint ("no GUI, no database, no network") and treated satisfying it as
satisfying the requirement, without re-reading what the deliverable had to *be*.

**Correction.** `com.payguard.core.demo.ReconciliationDemo` with `public static void main`. It holds
hard-coded scenarios — clean match, orphan payment, unpaid order despite capture, an instalment payment that
must not be flagged, a duplicate capture, a same-identity conflict, and one discrepancy evaluated twice to show
the ceiling — builds each input in code, calls `reconcile`, and prints type, severity, amount at risk and
decision path to standard output.

No framework, no dependency, no database, no network, no file I/O, no argument parsing. It reads nothing and
writes only to `System.out`, so the core's `pom.xml` still declares zero runtime dependencies and ADR-002 and
INV-10 hold unchanged.

It sits in the core module rather than a separate one: a second module would add a parent POM, a second
artefact and a reactor ordering to get wrong, for no behavioural gain on a two-day deliverable. Splitting it
out later is a ten-minute change. Secondary benefit: the printed scenarios are better evidence for the
seven-page report than source listings, and cheaper in page budget.

**Documents affected.** `SAD.md` §1, §5.4, §5.5 (new); `implementation-roadmap.md` Milestone 1;
`testing-strategy.md` §12.

**Remaining assumptions.** That the professor accepts a console application as "an application". If a graphical
or interactive front end were expected, that contradicts the assignment's own "no GUI" constraint, so this
reading seems safe — but it is an interpretation, and it is the one question in this response worth confirming
directly.

---

## 6. Testing strategy reconciliation

**Identified.** Check expectations against the corrected rules; fix "8 × 8 = 72"; audit the boundaries; ensure
measurable acceptance criteria; clarify under-collection, absent-currency comparison and overflow; do not
report unexecuted tests as passing.

**Root cause of the arithmetic error.** Round one's count correction was done by search-and-replace over the
phrase "8 payment states × 8 order states = 64 combinations". One sentence in `testing-strategy.md` §5 said
"8 payment states × 8 order states, all 64 cells specified" — same dimensions, different wording — so the
replacement caught the trailing count and left the dimensions. A mechanical edit across a document needs a
verification pass of its own, which is now a direct question in the SAD's review checklist.

**Corrections.**

- **The count:** 8 × 10 = 80 throughout, dimensions and totals both.
- **`collectedAmount` below `requestedAmount`:** FR-02 now specifies three independent comparisons — order-total
  disagreement, over-collection, and **under-collection**. Under-collection is a finding `AMOUNT_MISMATCH`
  with reason `under_collected` **regardless of instalment count**, because PayTR's documented instalment
  allowance covers a collected total *above* the requested amount only. The allowance is one-directional, and
  revision 0.1's wording left that open.
- **Currency comparison with an absent observation:** the check runs only when both observations are present
  and each carries a currency. An `ABSENT` side, or a present observation with no currency (PayTR omits
  `currency` on a failed callback), **skips** the check — not a mismatch, not an error. Six partitions in
  `testing-strategy.md` §4. Revision 0.1 said only "currency check precedes all amount comparison", which three
  different implementations would all have satisfied.
- **Arithmetic overflow:** `Money` uses `Math.addExact`/`Math.subtractExact` and raises
  `MoneyOverflowException`, never a wrapped value. Three boundary cases: addition overflow, subtraction
  underflow, and a difference near the maximum that must succeed.
- **Measurable acceptance criteria:** every FR has them; FR-04.2's includes the suppression test, FR-05.3's and
  FR-06's require the behaviour to hold under *every* permutation.
- **Expectations from specification, not implementation:** stated as a procedure, not a slogan. The 80-cell
  table data is transcribed from requirements §4.2 before any rule class exists, so the suite fails
  comprehensively on day one. If a test fails and the fix is to change the expected value, that change is made
  in requirements §4.2 first and the test re-transcribed. A test whose expectation was copied from observed
  output verifies only that the code is deterministic.
- **No results reported.** The document's opening paragraph says plainly that nothing has been run and that
  coverage, defect counts and mutation scores must be pasted from real tool output.

**New boundary coverage added:** grace ceiling (3 cases), two stale thresholds (6), clock-skew tolerance (3),
under-collection (3), overflow (3), absent-currency (6), event-identity digest stability (3).

**Documents affected.** `testing-strategy.md` (preamble, traceability matrix, §3, §4, §6, §8, §13 new);
`requirements.md` FR-02, FR-03, FR-08.

---

## Recommended scope reduction

**Reduce FR-07 to divergence detection without amount arithmetic.** Keep the three directional cases and the
decision-table cells that produce them; defer partial-refund *amount* comparison to the MVP.

The justification is evidential, not temporal. The deferred part depends on data whose structure is
unverified: the Status Inquiry response's `returns` block exists but its per-item layout is undocumented
(UNK-11), and the Transaction Detail service's only refund indicator is `islem_tipi=S|I` with no amount-level
fields. Specifying six boundary cases over a guessed layout would mean treating unverified provider capability
as proven behaviour — the thing the review's final self-review check forbids. Cutting it removes a dependency
on a guess, not merely a few hours of work.

**Preserved:** three directional cases, a rule with real branching, the refund rows of the table. **Lost:**
partial-amount boundary cases only — and the boundaries added this revision outnumber them.

**Explicitly not cut, despite the deadline:** the 80-cell table, the non-suppression property test, the
permutation-invariance property test, and the negative suite. Three of those four exist because review found
defects they would have caught, and the property tests are the cheapest in the suite — a loop and an assertion.

---

## Readiness assessment

### Scope A — academic SVV core: **READY FOR IMPLEMENTATION**

Upgraded from revision 0.1's conditional status. The three defects that would have produced incorrect
behaviour are corrected and specified; requirements, the 80-cell table, twelve invariants, the package layout
and the test design are complete; there are no external dependencies; and the deliverable is now independently
executable. **No blocking issues.**

The remaining risk is schedule, not design. The report carries most of the marks, so at least a third of the
remaining time belongs to it, and the instructor's approval of the proposal is the one external gate.

### Scope B — commercial MVP: **NOT READY**

| Criterion | State |
|---|---|
| Payment discovery possible | **Yes** — resolved this revision |
| Discovery precision known | **No** — OI-10, OI-11 |
| Call volume within rate limits | **No** — OI-06, raised by the two-sweep design |
| Backfill bounded | **No** — OI-12 |
| Refund reconciliation specifiable | **No** — OI-13 |
| Demand evidence | **No** — OI-02 |

The character of Scope B's blockers improved. Previously one was existential: whether the headline capability
was possible at all. Now all four technical blockers concern **precision and cost**, and all four are answerable
in a single sandbox session using `dummy=1` before any real transaction exists.

The commercial blocker is unchanged and remains decisive. Nobody has shown the problem is frequent or that
anyone will pay, which is why the roadmap gates the MVP build behind a past-data audit rather than behind
these four.

---

## What this round says about the previous one

Round one's self-review caught a counting error and an over-confident citation. Both were real; both were
shallow. It missed three defects in the logic.

The reason is visible in hindsight: I checked the document against itself. R-6 through R-9 were all found by
re-deriving behaviour from the functional requirements rather than reading the table that claimed to implement
them. That is the difference between proofreading and verification — a fitting lesson for this particular
assignment, and one worth a sentence in the report.

Two of the corrected requirements were labelled CONFIRMED, which means the label tracked "I believe this"
rather than "this has been checked". FR-04, FR-05 and FR-06 now carry an explicit note of what was wrong, so a
future reader can see that CONFIRMED has survived a challenge rather than merely never having had one.
