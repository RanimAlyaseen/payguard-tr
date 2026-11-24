# ADR-008 — Order events by occurrence time, not arrival

**Status:** **SUPERSEDED** (22 Nov 2026, revision 0.3) by [ADR-015](ADR-015-event-identity.md) and
[ADR-016](ADR-016-authoritative-state.md) · Originally accepted 22 Nov 2026

> ## Why this record is superseded
>
> **Its premise no longer exists.** ADR-008 answered the question "when several events disagree, which one
> determines the gateway state?" Under ADR-016 no event determines gateway state at all: the authoritative
> state is `PaymentObservation.status` from the provider's query API, and transaction history is evidence
> only (INV-13). There is nothing left for an ordering rule to decide.
>
> **Two of its specifics were also wrong and are corrected elsewhere:**
>
> | ADR-008 said | Now |
> |---|---|
> | Tie-break equal timestamps on `receivedSequence` | Tie-break on `recordIdentity`, which is set-intrinsic. Retrieval order is read by no rule (INV-11). ADR-008's own tie-break broke the permutation invariance it was arguing for — see ADR-015 |
> | Reject any event whose `occurredAt` is after `evaluatedAt` | Reject only beyond `evaluatedAt + clockSkewTolerance`, because clock skew between a provider and PayGuard is an ordinary operational condition (FR-08) |
>
> **What survives, and where it lives now:** the core argument — that a verdict must not depend on delivery or
> retrieval order — is correct and is preserved as INV-09 and INV-11, and the ordering rule itself survives as
> *presentation* order in FR-06.
>
> The text below is retained unaltered as a record of the reasoning at the time. **Do not implement from it.**

---

**Original status:** Accepted · **Date:** 22 Nov 2026

## Context

PayTR retries a notification after a minute when it does not receive `OK`
([docs](https://dev.paytr.com/en/iframe-api/iframe-api-2-adim)). A retry of an older notification can
therefore arrive after a newer one. Network paths reorder. If arrival order decided the outcome, a stale
`failed` arriving after a `success` would flip a correct verdict into a wrong one, in the direction that costs
the merchant money.

## Decision

`EventNormalizer` sorts by `occurredAt`, tie-breaking on `receivedSequence`. The last event by occurrence
decides the gateway state. Arrival order is recorded for audit but never affects the verdict (INV-09). An
event whose `occurredAt` is after `evaluatedAt` is treated as **invalid input** and raises
`InvalidReconciliationInputException` (FR-08), rather than being silently clamped or accepted.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Last arrival wins | Simplest and wrong. A documented retry mechanism guarantees that stale events arrive late |
| Highest-precedence status wins (`CAPTURED` beats `FAILED` always) | Appealing, but it cannot represent a legitimate reversal — a capture followed by a genuine failure or void would be permanently misread as success |
| A version or sequence number from the provider | Would be better than timestamps, but neither provider documents a monotonic per-order version in the callback payload. Using one we cannot verify exists would be inventing a capability |
| Clamp a future-dated event to `evaluatedAt` | Hides a real problem. A future timestamp means clock skew or a corrupted payload, both of which the operator needs to know about |

## Reasons

Occurrence time is the only ordering the domain actually has, and sorting makes the result independent of
delivery accidents — which is exactly INV-09, a property that can be tested by shuffling an event list and
asserting the verdict is unchanged.

Rejecting future-dated events rather than tolerating them follows the same principle as INV-07: fail visibly
rather than optimistically. Clock skew between a provider and PayGuard is a real operational condition and it
should surface as an error with a field name, not as a quietly adjusted number.

## Consequences

**Good:** verdicts are stable under reordering, and that stability is directly testable; the tie-break on
arrival sequence makes the ordering total, so there is no nondeterminism when two events share a timestamp;
legitimate reversals are representable.

**Bad:** PayGuard depends on provider timestamps being roughly accurate; equal timestamps resolve by arrival,
which is the one place arrival order still matters; and a provider that omits a meaningful occurrence time
would force a fallback this design does not have.

## Risks

Clock skew between the provider's clock and PayGuard's could make a legitimate event look future-dated and
raise a validation error on good data. Mitigation: allow a small configured tolerance on the future check, and
alert the operator when it is exceeded rather than rejecting silently. This tolerance is a policy value, not a
constant.

## Reconsider when

A provider exposes a monotonic per-order sequence or version — it should then become the primary ordering,
with `occurredAt` as the tie-break, reversing the current arrangement.
