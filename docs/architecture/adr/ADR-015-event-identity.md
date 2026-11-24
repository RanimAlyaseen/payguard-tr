# ADR-015 — Record identity, and conflicts as a data-integrity fault

**Status:** Accepted · **Date:** 22 Nov 2026 · **Amended revision 0.3**

> **Revision 0.3 amendment.** This record was written about a type called `PaymentEvent`, which conflated
> webhook notifications with gateway transaction-history rows. ADR-016 settles that: the type is
> `TransactionRecord`, it means **a row of the gateway's transaction history**, and a webhook is a run trigger
> that never enters the core. Read `eventIdentity` as `recordIdentity` and "event" as "history record"
> throughout. The reasoning is unaffected — the identity problem is identical for history rows, and for PayTR
> it is sharper, because its history service supplies no row identifier either.

## Context

Revision 0.1 contained a contradiction the review caught. FR-05 resolved two events claiming the same identity
by "first arrival wins". FR-06 and INV-09 asserted that the verdict is invariant under permutation of the input
list. First-arrival-wins is a function of list position, so the two could not both hold, and the invariant was
unenforceable.

Worse, revision 0.2's tie-break for equal `occurredAt` values was `receivedSequence` — arrival order — so the
ordering rule *itself* depended on the thing the invariant said must not matter.

There is also a provider fact that revision 0.1 did not confront. **PayTR's callback carries no notification
identifier.** Its fields are `merchant_oid`, `status`, `total_amount`, `hash`, `payment_type`, and
conditionally `currency`, `payment_amount`, `failed_reason_code`, `failed_reason_msg`, `test_mode`
([docs](https://dev.paytr.com/en/iframe-api/iframe-api-2-adim)). PayTR's own guidance is to identify duplicates
by `merchant_oid` and act only on the first notification. iyzico, by contrast, supplies `iyziPaymentId`
([docs](https://docs.iyzico.com/en/advanced/webhook)). So identity cannot be assumed to exist.

## Decision

**1. Identity is provider-specific and computed by the adapter.** The core receives an opaque `recordIdentity`
and never derives one.

- Where the provider supplies an identifier, use it (iyzico: `iyziPaymentId` plus canonical status).
- Where it does not, the adapter computes a **content digest** over the semantically significant fields only:
  `merchantOrderRef`, canonical status, collected amount, currency, payment type. Transport-level fields —
  `hash`, receipt time, headers — are excluded, so a retry of the same notification digests identically.
- One shared digest function, so two adapters cannot disagree about identity.

**2. Identical duplicates collapse.** A group of same-identity, content-identical records contributes one
record. Retrieval count is kept for audit.

**3. Same-identity conflicts are `INSUFFICIENT_DATA`, not a choice.** If a group shares an identity but its
members differ in a significant field, no member contributes to the gateway state and the finding is
`INSUFFICIENT_DATA` with reason `conflicting_records_same_identity`, recording every value.

**4. Ordering uses only set-intrinsic data.** Sort by `occurredAt`, tie-break on `recordIdentity`
lexicographically. `retrievalSequence` is retained for audit and read by no rule (INV-11).

**5. INV-09 is restated precisely:** for any two input lists that are permutations of one another and contain
the same multiset of records, `reconcile` returns an identical finding, `decisionPath` included.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| First arrival wins (revision 0.1) | Position-dependent, so it contradicts INV-09. The review was right to forbid it |
| Last arrival wins | Same defect in the other direction |
| Precedence by status — treat `CAPTURED` as authoritative over `FAILED` | Deterministic and permutation-invariant, so it passes the mechanical test. Rejected because it invents a precedence no provider states, and it would silently mask a genuine reversal: a capture later voided would read as captured forever |
| Precedence by amount — prefer the larger | Arbitrary dressed as a rule, and biased toward over-reporting money at risk |
| Core computes the digest itself | Would require the core to know provider field semantics, breaking ADR-002's dependency-free domain and ADR-010's translation boundary |
| Use `merchant_oid` alone as identity for PayTR | PayTR's duplicate guidance keys on it, but it is the *order* reference: two genuinely distinct captures for one order would collapse into one event and `DUPLICATE_PAYMENT` would become undetectable — destroying a HIGH-severity finding |

## Reasons

The decisive argument against every precedence scheme is that PayGuard genuinely does not know which record is
true. Two sources claiming one identity with different content is a data-integrity problem, and reporting it as
one is both deterministic and honest. Manufacturing a winner would produce a confident verdict from
contradictory evidence, which is the failure mode this architecture refuses in FM-06 and INV-07 as well.

The derived digest has a neat consequence worth noting: because identity is a function of significant content,
"same identity, different content" **cannot arise for a digest-based provider at all**. Rule 3 is therefore
unreachable for PayTR and reachable only where a provider supplies its own identifier and reuses it. That is a
small surface, which is the right size for a rule whose answer is "we cannot tell".

Tie-breaking on `recordIdentity` rather than retrieval order is what turns INV-09 from an assertion into a
property: the sort key is a function of the set, so permuting the input cannot change the sorted sequence.

## Consequences

**Good:** INV-09 is provable rather than asserted; duplicate retries and distinct double charges are cleanly
separated (FR-05.2 versus FR-05.4); no provider's missing identifier blocks the design; the conflict case has a
defined, testable answer.

**Bad:** a digest must be specified exactly and kept stable — a change to the significant-field set silently
changes identity for every future event, so it is a versioned decision; a provider that reformats a significant
field without changing meaning produces a new identity and therefore a spurious second event; and two distinct
records sharing an `occurredAt` are ordered by an essentially arbitrary string comparison, which is consistent
but not meaningful.

## Risks

The digest's field set is the risk. Including too much (a timestamp, a header) breaks duplicate collapse;
including too little makes two genuinely different records collide, which for two captures would hide a
`DUPLICATE_PAYMENT`. Mitigation: the field set is written in FR-05.1, tested for stability under a
transport-field change, and tested for distinctness across two real captures.

## Reconsider when

A provider begins supplying a stable, globally unique notification identifier — it should then replace the
digest for that provider, and the digest stays as the fallback. Or if sandbox testing shows PayTR reusing a
significant-field combination across genuinely distinct notifications, which would mean the digest's field set
is too narrow.
