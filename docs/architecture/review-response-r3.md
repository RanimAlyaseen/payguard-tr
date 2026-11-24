# Architecture Review Response — Revision 0.3

**Reviewed:** revision 0.2 of the full package
**Status requested:** final consistency pass; no feature expansion, no implementation
**This document:** issue, root cause, exact correction, tests affected.

Eight findings. Two were contradictions between documents that would have produced two different
implementations depending on which file a developer read. **Three corrections let me remove things**, which on
a two-day deadline is the best kind of finding: Scope A is smaller after this revision than before it.

---

## 1. Authoritative payment-state model — BLOCKING

**Issue.** ADR-004 said the architecture is query-first and a webhook payload is never the source of truth.
FR-06 said the last canonically ordered `PaymentEvent` determines the gateway state. A developer implementing
from the ADRs would have built a query model; one implementing from the requirements would have built an
event-replay model.

**Root cause.** A type named `PaymentEvent` that was never pinned to one meaning. It could have been a webhook
notification, a gateway history row, or both, and because the name said "event" the ordering logic drifted
toward event-sourcing semantics while the ADRs went the other way. Nobody had to make a wrong decision; the
ambiguity did it.

**Exact correction.**

`PaymentEvent` is renamed **`TransactionRecord`** and defined as *a row of the gateway's own transaction
history* — in PayTR's case a row from `/rapor/islem-dokumu`. Three sources, three roles, named so they cannot
be conflated:

| Source | Type | Role |
|---|---|---|
| Query API snapshot (`/odeme/durum-sorgu`) | `PaymentObservation.status` | **Authoritative.** The decision table reads this and nothing else |
| Transaction history (`/rapor/islem-dokumu`) | `List<TransactionRecord>` | **Evidence only**, three permitted uses |
| Webhook (iyzico, optional) | *no type* | A run trigger; never enters the core |

History may do exactly three things, and **INV-13** forbids any other: detect two distinct capture records
(`DUPLICATE_PAYMENT`), populate `decisionPath`, and give a deterministic presentation order. It can never
determine, adjust or override the status.

**On contradiction, neither source wins.** A `FAILED` or `ABSENT` status alongside a capture record yields
`INSUFFICIENT_DATA`, reason `gateway_history_contradicts_queried_state`, recording both. PayGuard reports that
two endpoints of one provider disagree, which is what it actually knows.

**Where history is absent**, the table verdict stands on the status alone and `decisionPath` records that
duplicate detection was unavailable — so an undetected duplicate is visible as a limitation rather than as a
clean result.

**Why the query API must be authoritative, and why this is commercially reusable.** PayTR's history service
returns **no status field at all**. A model in which history determined state would be unimplementable against
the only endpoint that supplies history. The core's model is now a direct image of the two endpoints that
actually exist — one snapshot with a status and a second-precision `payment_date`, one windowed report with
rows and no status.

**Documents affected.** `requirements.md` FR-05 (rewritten), FR-06 (rewritten), §4.1 gate 2, BR-02, BR-03,
BR-04b; `domain-model.md` §1b (new), §3 class diagram, §3b, FindingType table, INV-06, INV-09, INV-11, INV-13
(new); `SAD.md` §1 principle five, §5.1, §5.2 (diagram rewritten), §5.4, §7 ERD; new
`adr/ADR-016-authoritative-state.md`; `adr/ADR-004` consistent as written; `adr/ADR-008` **superseded**;
`adr/ADR-006` identity corrected; `adr/ADR-015` amended for terminology; `failure-mode-analysis.md` must-never
table; `engineering-standards.md` CI gate 9b and checklist.

**Tests affected.** New `AuthoritativeStateTest` (FR-06, INV-13: status selects the cell; contradiction
detected; empty history still yields the table verdict). `EventIdentityTest` → `RecordIdentityTest`;
`EventNormalizerTest` → `RecordNormalizerTest`; `IdempotencyPropertyTest` →
`RepetitionInvariancePropertyTest`. `DuplicatePaymentTest` gains an empty-history case. New equivalence
partition of four classes for authoritative-status-versus-history. **Removed:** the "last record decides state"
logic and every test of it.

---

## 2. `amountAtRisk` and severity semantics — BLOCKING

**Issue.** Zero meant both *confirmed no exposure* and *exposure not computable*, and severity depended on it.
So `CAPTURED` + `REFUNDED` order — `REFUND_DIVERGENCE(shop_only)`, where the customer may still be owed
money — had a gateway `refundedAmount` of zero and was demoted to LOW precisely when it mattered. BR-08 also
contradicted §4.3 over `PAYMENT_WITHOUT_ORDER`.

**Root cause.** A single `Money` field was asked to carry a value *and* the absence of a value, with `0` as the
sentinel. Coupling severity to it then propagated the ambiguity into prioritisation. This is the same mistake
as revision 0.1's `observedAt` — one field, two meanings — which suggests a pattern worth watching for rather
than two unrelated slips.

**Exact correction.** Review's **option B**, being the simpler of the two offered, and this is a
scope-controlled revision:

1. **Severity is a pure function of `FindingType` alone** (INV-08 restated). No amount influences it.
2. **`amountAtRisk` is `Optional<Money>`.** Present means a computed exposure, which may legitimately be zero.
   Absent means not computable. The two are now distinguishable.
3. **An absent amount never lowers severity.**

`amountAtRisk` is defined for **every** `FindingType` in requirements §4.4, which is the single authoritative
table; BR-08 now points at it instead of restating it.

**Cells audited and changed:**

| Case | Revision 0.2 | Revision 0.3 |
|---|---|---|
| `CAPTURED` + `REFUNDED` order | amount 0 → demoted to LOW | amount **absent**, severity **HIGH**. The unsafe demotion is gone |
| `REFUNDED`/`PARTIALLY_REFUNDED` + `ABSENT`/`DRAFT` order | "zero at risk" → LOW | `PAYMENT_WITHOUT_ORDER`, amount **present and zero** (`collected.absoluteDifference(refunded)`), severity **HIGH**. This resolves the BR-08 contradiction: the amount genuinely is collected-minus-refunded, and it no longer changes severity |
| `PARTIALLY_REFUNDED` + `CANCELLED`/`REFUNDED` | amount from refund arithmetic | amount **absent** — FR-07 defers that arithmetic, and absent is the honest value |
| Every `CURRENCY_MISMATCH` | unspecified | **absent**, explicitly: cross-currency arithmetic is forbidden by INV-04, so no figure is computable without a rate PayGuard has no business inventing |
| Every `STATUS_CONFLICT` | unspecified | **absent** — which side is right is unknown, so any figure would be a guess |
| Every `INSUFFICIENT_DATA` | LOW | **MEDIUM**, amount absent. Raised because every route to it is now a real integrity problem — unreadable status, two records contradicting, or two endpoints contradicting |

**One deliberate consequence.** A fully refunded orphan payment is now HIGH with zero exposure. The money is
back, but the two systems' records diverged, which a merchant should see. Severity answers "how much should I
care"; `amountAtRisk` answers "how much money". Separating them makes both honest and is simpler than the
coupling it replaces.

**Documents affected.** `requirements.md` §4.4 (rewritten), BR-08, BR-08b; `domain-model.md` FindingType table,
INV-08; `SAD.md` §5.4 finding signature, §13.2; `failure-mode-analysis.md` must-never table;
`engineering-standards.md` checklist.

**Tests affected.** New `AmountAtRiskTest` — one case per `FindingType`, asserting present / absent /
present-and-zero exactly as §4.4 specifies. `SeverityTest` rewritten: severity must be identical for one type
across a present, an absent and a zero amount. New three-class equivalence partition for amount presence.
`RefundDivergenceTest` asserts absent amounts for `shop_only` and `partial_vs_full`.

---

## 3. Grace-ceiling identity

**Issue.** `firstDetectedAt` was associated with a finding key containing the `FindingType`, so a discrepancy
that changed type would obtain a new `firstDetectedAt` and restart the ceiling.

**Root cause.** The ceiling was bolted onto an existing identifier that happened to be nearby, rather than
given an identity of its own. The finding key exists to deduplicate *findings*; an episode is a different
thing with a different lifetime, and reusing one for the other made the guarantee false.

**Exact correction: the ceiling is removed from Scope A.** The review invited either a correctly-keyed episode
or a justified removal. Removal is right, for three reasons:

1. **The original defect is already fully fixed without it.** Polling cannot restart the window because
   neither domain time moves when PayGuard merely looks again — provable inside the pure core, no caller
   cooperation needed.
2. **The guarantee cannot live in Scope A at all.** The core is pure and holds no state between calls, so
   episode identity would have to be maintained by the caller. An invariant whose truth depends on code outside
   the module is not an invariant of the module. Keeping it would have been exactly the unprovable guarantee
   this review objected to.
3. **In Scope A the residual risk does not exist.** `businessStateChangedAt` **is** the order's creation time
   (BR-07b), which never advances, so `referenceInstant` is fixed per pair and `discrepancyAge` is strictly
   increasing. There is nothing for a ceiling to protect against.

**The risk is real only in the MVP**, where an adapter might supply a moving `businessStateChangedAt`. That is
recorded as **FR-33** with the identity defined so it cannot be reinvented wrongly: episode key
`(storeId, merchantOrderRef)`, **independent of `FindingType`**, opening when the pair first becomes
inconsistent and closing only when it becomes genuinely consistent. FR-33 is conditional — it binds *if and
only if* the MVP supplies a `businessStateChangedAt` that can advance.

**INV-12 is now stronger, not weaker.** It was "non-decreasing, plus a ceiling clause"; it is now "**strictly
increasing**, therefore a discrepancy leaves grace exactly once and never returns". Unconditional, and provable
in the module.

**Documents affected.** `requirements.md` FR-04 (rewritten as FR-04.1–4.3), FR-08, FR-33 (new), BR-05, BR-07b;
`domain-model.md` §3b (five concepts → four), INV-12; `SAD.md` §5.1 sequence, §5.4, §13.2;
`implementation-roadmap.md` Milestone 2.

**Tests affected.** **Removed:** three `graceCeiling` boundary cases and the `firstDetectedAt`-after-`evaluatedAt`
validation case. **Strengthened:** `GraceSuppressionTest` now asserts the unconditional property — grace is left
exactly once across 100 evaluations with fresh retrieval times — which is a sharper assertion than the ceiling
version it replaces.

---

## 4. Stale ADRs

**Issue.** ADR-008 still specified `receivedSequence` tie-breaking and rejection of any event after
`evaluatedAt`; ADR-006 still gave `PAYMENT_EVENT` identity as `(merchant_order_ref, event_id)`.

**Root cause.** Revisions 0.2's corrections were applied to the requirements and the SAD but the ADR set was
treated as an archive rather than as part of the specification. An ADR that contradicts the requirements is
worse than no ADR, because it reads as authoritative.

**Exact correction.**

**ADR-008 is marked SUPERSEDED** by ADR-015 and ADR-016, with a prominent note explaining why: its premise —
"when several events disagree, which determines the state?" — no longer exists, because under ADR-016 no event
determines state. Its two wrong specifics are tabulated against their replacements. The original text is
retained verbatim as a record of the reasoning, under an explicit "do not implement from it".

**ADR-006** now states `TRANSACTION_RECORD` identity as `(integration_id, record_identity)` with
`record_identity` the provider-specific opaque string, and explains why it is *not* `(merchant_order_ref,
event_id)`: PayTR's history service supplies no row identifier, and the order reference alone would collapse
two distinct captures and destroy `DUPLICATE_PAYMENT`. `EventNormalizer` → `RecordNormalizer`.

**ADR-003** amended for item 7 below. **ADR-015** amended for terminology, with its reasoning unaffected —
the identity problem is identical for history rows and sharper for PayTR.

A full cross-document terminology and invariant sweep followed; results in the audit below.

**Tests affected.** None directly — ADRs carry no tests. Indirectly, the identity tests follow ADR-015's
amended terminology.

---

## 5. Total-function terminology

**Issue.** INV-01 said "every input yields exactly one `FindingType`", which contradicted FR-08's typed
validation exceptions.

**Root cause.** Loose use of "total". A function made total by validation is total *over its valid domain*;
saying "every input" claims something the design deliberately does not do.

**Exact correction.** INV-01 now reads: *for every **valid** `ReconciliationInput`, `reconcile` returns exactly
one `FindingType`.* Malformed input lies outside the valid domain and raises a documented typed exception.
FR-08 gained a closing note that it *defines* the valid domain, so the two requirements now reference each
other instead of competing.

**Tests affected.** `TotalityTest` is scoped to valid inputs — all 80 combinations, `UNKNOWN` row and column
included — and the malformed cases live in `InputValidationTest`. The traceability matrix and §6 both say so,
so the split is visible rather than implied.

---

## 6. Duplicate invariance

**Issue.** "Deduplicating the event list never changes the finding" was too broad: collapsing a *conflicting*
same-identity group would change the verdict, and is meant to.

**Root cause.** The invariant was written before FR-05.3 existed and was not revisited when the conflict rule
was added. A new rule created an exception to an old invariant, and the invariant kept its original wording.

**Exact correction.** INV-06 now reads: *adding or removing content-identical repetitions of a `recordIdentity`
does not change the finding.* Conflicting same-identity records are explicitly **not** duplicates for this
purpose; they deliberately produce `INSUFFICIENT_DATA`.

The new wording also matches why the property matters operationally: overlapping discovery windows (ADR-014)
re-retrieve the same rows by design, so inertness under repetition is a working requirement.

**Tests affected.** `IdempotencyPropertyTest` → `RepetitionInvariancePropertyTest`, with the generator
restricted to content-identical repetitions and conflicting groups explicitly excluded and covered by
`ConflictingIdentityTest` instead.

---

## 7. Money contract

**Issue.** `Money.minus()` was exposed while negative values were rejected, and the test plan contained a
`Long.MIN_VALUE − 1` case that cannot be constructed under the model.

**Root cause.** The operation set was written from habit — a value type "should" have plus and minus — rather
than from the requirements. No requirement needed signed money.

**Exact correction.** `Money` represents a **non-negative** amount. `minus()` is **removed** from Scope A.
`plus()` is kept with `Math.addExact` overflow detection; `absoluteDifference()` is kept and is always
non-negative, so it cannot leave the type's domain. The one real subtraction in the system —
`collectedAmount` minus `refundedAmount` for `PAYMENT_WITHOUT_ORDER`'s exposure — is `absoluteDifference`,
which gives the same number because `refunded ≤ collected` holds. **No signed-money abstraction is
introduced.** ADR-003 records both rejected alternatives (allow negatives; keep `minus` and throw) and a
reconsideration trigger: a genuine signed requirement would get a *new* type, not a loosened `Money`.

**Tests affected.** **Removed:** the `Long.MIN_VALUE − 1` subtraction-underflow case and the
subtraction-overflow case — both untestable under the model. **Retained:** `plus` overflow, and
`absoluteDifference(0, Long.MAX_VALUE)` which must succeed. The removals are documented in
`testing-strategy.md` §4 rather than silently dropped, so a reader does not wonder where they went.

---

## 8. Mechanical consistency

| Fix | Where |
|---|---|
| OrderStatus has **10** equivalence classes, not 9 | `testing-strategy.md` §3 |
| Duplicated `version, version` field | `SAD.md` §5.4 |
| `receivedSequence` → `retrievalSequence` | `domain-model.md` INV-11, `failure-mode-analysis.md`, `engineering-standards.md` (CI gate 9 and checklist) |
| `eventIdentity` → `recordIdentity` | `domain-model.md` INV-09 |
| `PaymentEvent` → `TransactionRecord`; `PAYMENT_EVENT` → `TRANSACTION_RECORD` | `domain-model.md`, `SAD.md` §5.4 and ERD, `adr/ADR-006` |
| `firstDetectedAt` removed from the sequence diagram and the MVP deliverables | `SAD.md` §5.1, `implementation-roadmap.md` Milestone 2 |
| `graceCeiling` removed from the policy field list and the review checklist answer | `SAD.md` §5.4, §13.2 |
| `72` → `80` in the milestone acceptance criteria, the review checklist, `engineering-standards.md` and ADR-007 | four files |

The sweep covered all 28 files, not the main three. Remaining mentions of the retired terms are confined to
historical explanations — the revision-0.2 review log, ADR-008's superseded note, and the "what was wrong"
paragraphs — where naming the old term is the point.

---

## Scope control

No new `FindingType`, infrastructure, API or product capability was added. Count stays at **16**.

The contradiction case in item 1 needed a finding, and `INSUFFICIENT_DATA` already meant exactly "two sources
disagree and we cannot resolve it" — so it took a new *reason string*, not a new type. Its severity rose from
LOW to MEDIUM because every route to it is now a genuine integrity problem.

**Net effect on Scope A: smaller.**

| Removed | Added |
|---|---|
| `graceCeiling` policy value | One contradiction gate (one comparison) |
| `firstDetectedAt` input field | `Optional` on one field |
| `Money.minus()` | Four authoritative-status partitions |
| The "last record decides state" logic path | Three amount-presence cases |
| Three ceiling boundary cases | One contradiction case |
| Two impossible `Money` cases | One renamed type |
| One unprovable invariant | Two provable invariants (INV-12 strengthened, INV-13 added) |

Preserved as required: Java, Maven, JUnit 5, JaCoCo, a pure independently executable core, the eight academic
functional requirements, and all five test-design techniques.

---

## Cross-document consistency audit

Run mechanically across all 28 files; every check passed.

| Dimension | Result |
|---|---|
| Status counts | 8 `PaymentStatus`, 10 `OrderStatus`, agreeing in `domain-model.md`, `requirements.md` and `testing-strategy.md` |
| FindingType counts | 16, and requirements §4.4 covers every one with no extras — set difference empty |
| Decision table | 8 × 10 = 80 cells, every row the same width, no blank cell |
| Rule precedence | `DUPLICATE_PAYMENT` above `WITHIN_GRACE_PERIOD`; contradiction in gate 2 |
| Source-of-truth semantics | Stated in `domain-model.md` §1b, FR-06, INV-13 and ADR-016; referenced in the testing strategy |
| Event identity | `recordIdentity` throughout; `eventIdentity` only in historical notes |
| Event ordering | Tie-break on `recordIdentity` in every location; `retrievalSequence` read by no rule |
| Amount-at-risk semantics | `Optional` in the model, requirements and tests; severity from type alone |
| Grace timing | No live `graceCeiling`; INV-12 strictly increasing |
| Invalid-input behaviour | INV-01 scoped to the valid domain; `TotalityTest` scoped to match |
| Money invariants | No `minus()` in any live specification; non-negativity stated in ADR-003 |
| ADR hygiene | 16 records; ADR-008 superseded; ADR-006 identity corrected |
| Links and fences | No broken internal links; all code fences balanced; 11 Mermaid diagrams |

---

## Final Scope A readiness

### **READY FOR IMPLEMENTATION**

| Criterion | State |
|---|---|
| One authoritative design, no document contradicting another | **Yes** — the two cross-document contradictions are resolved and the audit above is mechanical, not a reading |
| Requirements complete with measurable acceptance criteria | Eight FRs, each with acceptance criteria |
| Business rules agree with the decision table | All 80 cells derived from the FRs; every cell has a verdict; every notation has one meaning |
| Invariants provable **within the module** | Thirteen. The one that was not provable was removed rather than kept |
| Exposure and severity unambiguous | Severity from type; `amountAtRisk` optional; no overloaded zero |
| Independently executable | `ReconciliationDemo.main`, zero runtime dependencies |
| External dependencies | None |
| Achievable by 24 November | Yes, and more so than before: Scope A is smaller after this revision |
| Blocking issues | **None** |

The remaining risk is schedule, not design. The report carries most of the marks, so at least a third of the
remaining time belongs to it, and the professor's approval of the proposal is the one external gate.

**Scope B remains NOT READY**, unchanged by this revision: discovery precision (OI-10, OI-11), rate limits
(OI-06), backfill bounds (OI-12), refund structure (OI-13), and — still decisive — no demand evidence (OI-02).

---

## What the three rounds say about the method

Round one's self-review caught a counting error and an over-confident citation. Round two found three logic
defects, all by re-deriving behaviour from the requirements rather than reading the table that claimed to
implement them. Round three found two cross-document contradictions, which neither of the earlier passes could
have caught, because both were *internally* consistent within the file being read.

The progression is the useful part. Checking a document against itself catches typos. Checking it against its
requirements catches logic. Checking documents against *each other* catches the contradictions that make two
developers build two different systems — and that is the only check that can be run mechanically, which is why
the audit above is a script and not a reading.

Three defects across the rounds shared one shape: **one field carrying two meanings.** `observedAt` was
provenance and domain time; `amountAtRisk`'s zero was a value and an absence; `PaymentEvent` was a notification
and a history row. That is a pattern worth naming in the report, because it is cheaper to look for than to
discover.
