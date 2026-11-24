# ADR-014 — Gateway-first discovery as a first-class sweep

**Status:** Accepted · **Date:** 22 Nov 2026 · **Supersedes part of ADR-004 rev 1**

## Context

Revision 0.1's reconciliation sequence read orders from the shop, then queried the gateway for each order's
reference. That topology has a structural blind spot: **it can only ever examine payments whose order already
exists.** A captured payment with no order in the shop — the scenario the product is named for — is invisible
to it, no matter how often it runs.

The review identified this, and pointed at the capability that fixes it. Verified:

**[V]** PayTR's **Transaction Detail** service (İşlem Dökümü), `POST https://www.paytr.com/rapor/islem-dokumu`,
returns sales and refund transactions for a date range with a **maximum window of 3 days**. Its request fields
are `merchant_id`, `start_date`, `end_date`, `dummy` and `paytr_token`. **`merchant_oid` is not a field of this
service.** Each returned transaction carries `siparis_no` (the order number), `islem_tipi` (`S` sales / `I`
returns), amounts, `para_birimi`, `taksit`, and `islem_tarihi`.
Source: <https://dev.paytr.com/en/islem-dokumu>

Two limits matter as much as the capability. **[V]** There is **no per-transaction status field**, and
`islem_tarihi` is documented with a date-only example (`13.01.2021`).

## Decision

Reconciliation runs **two sweeps per store**, and neither subsumes the other.

**Sweep A — gateway-first discovery.** Walk the Transaction Detail service over consecutive windows of at most
three days. For each transaction, take `siparis_no` and ask the shop whether that order exists. A transaction
with no matching order becomes a pair whose `OrderObservation.status` is `ABSENT`, which the decision table
classifies as `PAYMENT_WITHOUT_ORDER`.

**Sweep B — shop-first.** Read orders changed since the watermark (WooCommerce `modified_after`) and query the
gateway for each. This catches the opposite direction: an order the shop believes is paid with no gateway
capture.

**Confirmation stage.** Because discovery carries no status and a coarse date, any transaction that discovery
flags as potentially discrepant is **confirmed** by a Status Inquiry call on its order reference
(`/odeme/durum-sorgu`), which returns a canonical status, `payment_date` as `YYYY-MM-DD hh:mm:ss`, and the
`returns` block. Discovery finds candidates; confirmation decides.

**Documented versus needing integration testing**, kept explicitly separate:

| Step | Basis |
|---|---|
| Date-range retrieval without an order reference | **Documented** |
| 3-day maximum window | **Documented** |
| `siparis_no` available as the join key | **Documented** |
| `islem_tipi` as the only refund indicator | **Documented** |
| Absence of a status field | **Documented** |
| Whether `islem_tarihi` ever carries a time | **Needs integration testing** (UNK-09) |
| Whether failed or in-progress transactions appear at all | **Needs integration testing** (UNK-10) |
| How far back history extends | **Needs integration testing** (UNK-08) |
| Per-item structure of `returns` | **Needs integration testing** (UNK-11) |

The `dummy=1` flag returns simulated data, so a first pass at UNK-09 and UNK-10 is possible before any real
transaction exists.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Shop-first only (revision 0.1) | The defect being fixed. Structurally cannot see an orphan payment |
| Gateway-first only | Misses the opposite direction — an order marked paid with no gateway record — which `ORDER_PAID_WITHOUT_PAYMENT` depends on. The two sweeps answer different questions |
| Treat discovery as authoritative and skip confirmation | Tempting for call volume, but the service has no status field, so "a transaction exists" would have to stand in for "a transaction succeeded". That is exactly the kind of inference the architecture refuses elsewhere (INV-07, FM-06) |
| Use the single-date `/rapor/odeme-detayi` service for discovery | It exists and is documented, but a one-day granularity makes windowing three times as chatty for the same coverage |
| Ask merchants to export statements manually | Defeats the product |

## Reasons

The two sweeps are not redundancy; they are the two halves of a bidirectional reconciliation. A reconciler that
walks only one side of a join finds only one class of mismatch.

Separating discovery from confirmation is forced by the documentation rather than chosen for elegance: a service
with no status field and a date-granular timestamp cannot drive a grace-period comparison, and pretending
otherwise would put an unverified inference underneath every time-based rule.

## Consequences

**Good:** `PAYMENT_WITHOUT_ORDER` becomes detectable, which is the product's headline claim; refunds are
visible from two independent sources (`islem_tipi=I` in discovery, `returns` in confirmation), which is a useful
cross-check; `dummy=1` makes contract tests possible without real transactions.

**Bad:** call volume roughly doubles for discovered candidates, against rate limits still unknown (UNK-01);
the 3-day cap makes any backfill a loop, so a first connection over a year of history is 122 windows; two
sweeps mean two watermarks and two completeness conditions to track; and a date-only `islem_tarihi` would make
confirmation mandatory for every candidate rather than only suspicious ones.

## Risks

**Partial discovery implying absence.** If a window's sweep is incomplete, a transaction PayGuard never saw
looks identical to one that does not exist, and the shop-side lookup would then be asked about an order
reference that was never retrieved. This is FM-10's failure mode arriving by a new route. Mitigation: BR-11 —
an `ABSENT` order observation is only produced when the sweep for that window completed, and per-window
completion is recorded.

**Window-boundary gaps.** Consecutive windows that abut exactly could drop a transaction that lands on the
boundary, given timestamp granularity. Mitigation: windows overlap by a configured margin, and ingestion is
idempotent by natural key (ADR-006), so overlap costs nothing.

## Reconsider when

A provider offers a webhook PayGuard can subscribe to independently of the merchant's shop endpoint, with
signature verification on by default — discovery would then become a backstop rather than the primary path. Or
if UNK-10 resolves badly and the service omits non-completed transactions, in which case stuck-payment
detection stays shop-first and the asymmetry should be documented for customers rather than hidden.
