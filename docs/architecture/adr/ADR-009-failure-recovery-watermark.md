# ADR-009 — The watermark advances only on success

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

Each store has an `order_watermark` — the point up to which orders have been reconciled. WooCommerce supports
`modified_after` on its orders endpoint
([docs](https://developer.woocommerce.com/docs/apis/rest-api/v3/orders/)), so a run can read only what changed.

A run can fail anywhere: the shop times out, the gateway returns an error, the database is briefly
unavailable, the process is killed mid-run. The question is what the next run then reads.

## Decision

A run records `STARTED`, does its work, and advances the watermark **only** when it records `SUCCEEDED`. A
failed or crashed run leaves the watermark untouched, so the next run re-reads the same window. Combined with
idempotent ingestion (ADR-006), re-reading is harmless. The window also overlaps the watermark by a small
configured margin to absorb clock differences between the shop and PayGuard (OI-09).

A run that was scheduled but never recorded is detected and recorded as `MISSED` (FR-29).

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Advance the watermark as each page is processed | Finer-grained recovery, but a crash between "page processed" and "findings persisted" would skip orders permanently. Skipping is the one failure a monitoring product cannot have |
| Checkpoint per order | Correct but chatty, and it buys little when the whole window is cheap to re-read and re-ingestion is a no-op |
| A durable queue of per-order jobs | The right answer at much larger scale. At MVP scale it adds infrastructure to solve a problem the watermark already solves |
| No watermark; always scan a fixed trailing window | Simple and self-healing, but either the window is long and wasteful or short and capable of missing a late-modified order |

## Reasons

Two failure directions are not symmetrical. Re-processing is free because ingestion is idempotent. Skipping is
unrecoverable, because nothing will ever look at that window again. So the design should bias hard toward
re-processing, and "advance only on success" is the simplest rule that guarantees it.

Detecting a missed run matters as much as recovering a failed one. A monitoring product whose own silence is
indistinguishable from "nothing is wrong" has the defect it is selling a cure for — which is precisely the
silent-failure pattern the Round 2 research found in automation tooling.

## Consequences

**Good:** crash recovery needs no reasoning about partial progress — just run again; no order is ever skipped;
the overlap margin makes clock skew a non-issue; `MISSED` runs are visible in the data rather than inferred
from absence.

**Bad:** a repeatedly failing run re-reads the same window each time, wasting provider calls against unknown
rate limits (UNK-01, UNK-02) — hence a circuit breaker per integration; and a very large window after a long
outage could exceed a reasonable run duration.

## Risks

A persistently failing store silently consuming the provider call budget for every other store. Mitigation:
per-integration circuit breaker, a cap on consecutive failed runs before the integration is marked unhealthy
and the merchant is told, and per-run recording of `provider_calls` so the cost is visible.

## Reconsider when

A single window grows too large to process in one run, which is the point to move to a durable per-order job
queue — keeping the same "never skip" property, enforced by the queue's acknowledgement instead of a watermark.
