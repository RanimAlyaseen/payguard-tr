# ADR-006 — Idempotency by natural key, not post-hoc de-duplication

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

Duplicates are guaranteed, not hypothetical. PayTR retries a notification after a minute when it does not
receive `OK`, and documents that the same notification can therefore arrive more than once, to be identified
by `merchant_oid` ([docs](https://dev.paytr.com/en/iframe-api/iframe-api-2-adim)). A reconciliation run that
crashes halfway will be retried over the same window. A merchant may click "run now" twice.

## Decision

Idempotency is enforced by **unique natural keys at the point of write**, not by cleaning up afterwards:

- `PAYMENT_OBSERVATION` unique on (`integration_id`, `merchant_order_ref`, `observed_at`, content hash)
- `ORDER_OBSERVATION` likewise
- `TRANSACTION_RECORD` identity is (`integration_id`, `record_identity`), where `record_identity` is the **provider-specific opaque string** the adapter computes — a provider's own row identifier where one exists, otherwise a content digest over the row's significant fields (ADR-015, FR-05.1). It is deliberately *not* (`merchant_order_ref`, `event_id`): PayTR's history service returns no row identifier, and `merchant_order_ref` alone would collapse two distinct captures and destroy `DUPLICATE_PAYMENT`
- `RECONCILIATION_FINDING` unique on (`finding_key`, open state), where `finding_key` is store + order + type
- `RECONCILIATION_RUN.id` is an idempotency key supplied by the scheduler
- Every PayGuard POST requires an `Idempotency-Key` header

Writes use insert-or-ignore semantics. In the core, `RecordNormalizer` groups by `recordIdentity` before
classification, and INV-06 requires that adding or removing content-identical repetitions never changes the
verdict. Overlapping discovery windows (ADR-014) re-retrieve the same history rows by design, so inertness
under repetition is a working requirement rather than a defensive nicety.

No exactly-once delivery is claimed anywhere. The goal is idempotent **effects**.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Detect and merge duplicates in a cleanup job | There is a window where duplicates are visible, which means a merchant can be notified twice about one problem. Notifications cannot be recalled |
| Rely on a distributed lock to prevent concurrent runs | Locks reduce the chance of concurrency, they do not make a repeated write safe. A lock that expires early reintroduces the problem. Needed anyway for scheduling, but not as the correctness mechanism |
| Trust provider identifiers to be unique and use them as primary keys | Couples the schema to a provider's identifier scheme, and PayTR's history service supplies no row identifier at all. `merchant_oid` is the *merchant's* order reference, unique within one merchant and shared by every transaction against that order |

## Reasons

A constraint in the database is checked by the database, which is the one component that sees every write
regardless of which code path made it. Application-level checks are bypassed by the next code path someone
writes. Putting idempotency in the key also makes the retry strategy simple: retry freely, because a repeat is
a no-op.

Distinguishing *the same event twice* from *two genuinely different payments* is a domain distinction, not an
infrastructure one — hence BR-02 and BR-03, and hence `DUPLICATE_PAYMENT` is a finding while a repeated event
identity is not.

## Consequences

**Good:** retries are safe by construction; a crashed run can be re-run without reasoning about where it
stopped; two concurrent triggers produce one run; the property test for INV-06 is a direct check of the
guarantee.

**Bad:** natural keys are wider than surrogate ones, so indexes cost more; a content hash must be computed on
every ingest; a provider that changes its response formatting without changing its meaning produces a
different hash and therefore a new observation row, which is noisy but harmless.

## Risks

Choosing the wrong natural key is expensive to change later. The riskiest is `finding_key`: if it is too
coarse, two genuinely different problems on one order collapse into one finding; too fine, and a merchant gets
a new notification each run. Mitigation: `finding_key` includes the finding type, and resolution is a state
change rather than a delete, so a mis-sized key is visible in the data before it is baked in.

## Reconsider when

A provider gives a stable, globally unique **row** identifier for its transaction history (UNK-12 asks this of
iyzico's reporting service). It should then replace the digest for that provider inside `record_identity`,
strengthening the key without changing the approach — the column stays opaque to the schema either way.
