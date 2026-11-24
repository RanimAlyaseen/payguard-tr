# ADR-016 — One authoritative source for gateway state

**Status:** Accepted · **Date:** 22 Nov 2026 (revision 0.3) · **Supersedes** [ADR-008](ADR-008-out-of-order-events.md)

## Context

Revision 0.2 contained a contradiction between an ADR and a requirement.

**ADR-004** established that the architecture is query-first and that a webhook payload is **never** the source
of truth. Revision 0.2's **FR-06** said that "the last event in canonical order decides the gateway state." Both could not
hold. A reader implementing from the requirements would have built an event-replay model; a reader implementing
from the ADRs would have built a query model; and the decision table would have received a different status
depending on which they read.

Underneath the contradiction sat an unresolved question the review named directly: what *is* the thing that
revision 0.2's core called `PaymentEvent`? Three different things were in scope and the name did not
distinguish them.

The provider facts settle it:

**[V]** PayTR's **Status Inquiry** API (`/odeme/durum-sorgu`) returns a current state for one `merchant_oid`,
with `payment_date` to the second and a `returns` block for refunds. It is a snapshot.
Source: <https://dev.paytr.com/en/durum-sorgu>

**[V]** PayTR's **Transaction Detail** service (`/rapor/islem-dokumu`) returns rows over a date range of at
most three days, each with `siparis_no`, `islem_tipi` (`S` sales / `I` returns), amounts and `islem_tarihi`.
**It returns no per-transaction status field.**
Source: <https://dev.paytr.com/en/islem-dokumu>

**[V]** PayTR's callback goes to the **merchant's own** Notification URL, so PayGuard does not receive it.
iyzico's webhook is available but its signature verification requires per-account activation by email.
Sources: <https://dev.paytr.com/en/iframe-api/iframe-api-2-adim>, <https://docs.iyzico.com/en/advanced/webhook>

## Decision

**Three sources, three distinct roles, named so they cannot be confused.**

| Source | Type in the core | Role |
|---|---|---|
| Query API snapshot | `PaymentObservation.status` | **Authoritative.** The decision table reads this and nothing else |
| Gateway transaction history | `List<TransactionRecord>` | **Evidence only** — three permitted uses, below |
| Webhook notification | *(no type — never enters the core)* | A run trigger, per ADR-004 |

Revision 0.2's `PaymentEvent` is renamed `TransactionRecord` and defined as **a row of the gateway's own
transaction history**. The rename is the point: the old name implied a notification stream and invited exactly the
confusion that produced the contradiction.

**History may be used for exactly three things**, and INV-13 forbids any other use:

1. Detecting two distinct capture records for one order → `DUPLICATE_PAYMENT` (FR-05.4).
2. Populating `decisionPath` and operator diagnostics.
3. Deterministic presentation order.

**History never determines, adjusts or overrides `PaymentObservation.status`.**

**When they contradict each other**, neither wins. The clearest case is a status of `FAILED` or `ABSENT`
alongside one or more capture records. The result is `INSUFFICIENT_DATA` with reason
`gateway_history_contradicts_queried_state`, recording both sides. PayGuard reports that two endpoints of one
provider disagree, which is what it actually knows.

**Where history is absent**, the table verdict stands on the authoritative status alone, and `decisionPath`
records that history was unavailable — so a `DUPLICATE_PAYMENT` that could not be detected is visible as a
limitation rather than as a clean bill of health.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **History determines state** (revision 0.2's FR-06) | Unimplementable against the only endpoint that supplies history, because that endpoint returns no status field. It would also require PayGuard to assume its history is *complete*, which a 3-day windowed walk cannot guarantee (FM-14) |
| Status is authoritative, but history silently overrides it when "more recent" | The silent-override behaviour the review forbade. It also needs a recency comparison between a second-precision `payment_date` and a date-granular `islem_tarihi`, which is not a sound comparison |
| Merge both into one derived state with a precedence table | Invents a precedence neither provider documents, and buries the disagreement instead of reporting it. A merchant told "captured" by a system that also holds a `FAILED` record has been given a confident answer from contradictory evidence |
| Treat a webhook payload as a `TransactionRecord` | Mixes a notification with a history row — the conflation being fixed. It is also impossible for PayTR, whose callback PayGuard never receives |
| Event-source the gateway state properly, with a complete event log | The right design for a system that owns the events. PayGuard owns none of them, receives no complete stream, and would be reconstructing state from a partial log it cannot verify |
| Drop history entirely and rely only on the query API | Tempting for simplicity, and it would cost `DUPLICATE_PAYMENT` — a HIGH-severity finding that is among the most valuable the product produces, because the Status Inquiry snapshot cannot show two captures against one order |

## Reasons

Each source should answer the question it is capable of answering. The query API answers "what is the state
now", which is the question the decision table asks. A history row answers "what happened once", which is a
different question and the only one that can reveal a second capture.

The deciding argument against any precedence scheme is that PayGuard cannot know which source is right. Two
endpoints of one provider disagreeing is a provider-side integrity problem. Reporting it is honest and
actionable; resolving it by rule would manufacture certainty from contradiction — the same failure the
architecture already refuses in FM-06 (a failed call is not an absence) and INV-07 (an unknown status is not a
guess).

**This also makes Scope A commercially reusable**, which the review asked to verify. The MVP will obtain status
from one documented endpoint and history from another, with different shapes and different granularities. The
core's model is now a direct image of that reality rather than a model the adapters would have had to fake.

## Consequences

**Good:** one source of truth, so two readers of the documents build the same thing; revision 0.2's "last
event wins" logic disappears entirely, which is a net simplification of Scope A; `DUPLICATE_PAYMENT` is preserved;
contradictions surface instead of being resolved arbitrarily; the model matches the endpoints that exist.

**Bad:** duplicate-capture detection depends on history being supplied, so it is unavailable where history is
absent — a real capability gap, now documented rather than hidden; two provider calls per confirmed candidate
rather than one, which raises call volume against unknown rate limits (OI-06); and the contradiction case adds
a gate to the rule chain, though it costs one comparison.

## Risks

**A future contributor "improving" the core by deriving state from history**, which would look like a
reasonable refactor. Mitigations: INV-13 states the prohibition; the engine receives history in a parameter
that no rule but the duplicate rule and the contradiction gate may read; and the code-review checklist asks
directly whether a change lets history influence cell selection.

**Under-detection of duplicates being mistaken for absence of duplicates.** Mitigation: `decisionPath` records
when history was unavailable, so a `MATCHED` finding produced without history is distinguishable from one
produced with it.

## Reconsider when

A provider offers a complete, verifiable event stream that PayGuard can subscribe to independently of the
merchant's endpoint, with ordering guarantees — then event-sourced state becomes implementable and the
trade-off changes. Nothing available today is close: PayTR's callback belongs to the merchant, and its history
service is a windowed report with no status field.
