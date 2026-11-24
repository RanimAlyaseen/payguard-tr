# ADR-005 — Immutable observations, derived findings

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

PayGuard tells a merchant that something is wrong with their money. The first question any merchant asks is
"are you sure?", and the second, weeks later, is "why did you say that?". Answering either requires knowing
what the providers said at the time, not what they say now.

## Decision

Everything PayGuard retrieves is stored as an **append-only observation** with the time it was retrieved and
the raw payload as received. Observations are never updated. A `ReconciliationFinding` is a **derived** record
that cites the observations it was computed from and the policy version in force. Findings have mutable
workflow state (open, acknowledged, resolved) but their verdict is never edited in place — a re-run under a
different policy creates a new finding and marks the old one `SUPERSEDED`.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Current-state rows updated on each fetch | Destroys the ability to explain a past finding, because the evidence has been overwritten. Also makes "did this change, or did we just look again?" unanswerable |
| Observations immutable, findings updated in place | Cheaper, but a merchant who acted on yesterday's finding could not see what it said once a re-run changed it. Silent verdict changes are how a monitoring tool loses trust |
| Event sourcing the whole system | The observations already *are* the event log for the part that matters. Event-sourcing the workflow state as well would add projection machinery for no benefit at this size |

## Reasons

This separation makes several requirements fall out for free rather than needing mechanism: a finding is
explainable from the database alone without re-querying a provider (NFR-15); a policy change cannot rewrite
history (NFR-16); and re-ingesting the same provider response is a no-op rather than a conflict, because the
natural key includes the observation time and a content hash (ADR-006).

It also draws a line the domain genuinely has. What PayTR said at 11:02 is a fact. Whether that constitutes a
problem is an opinion, and opinions depend on a policy that changes.

## Consequences

**Good:** auditability without extra work; findings reproducible from stored data; raw payloads available when
a provider's format surprises us; re-running a changed policy over history is a feature rather than a danger.

**Bad:** storage grows monotonically, and raw payloads are the bulkiest and most sensitive part of the schema,
so retention must be configured per store (NFR-14); reading "current state" means selecting the latest
observation rather than a single row, which needs an index to stay fast.

## Risks

Raw payloads contain customer identifiers and masked card numbers, so unbounded retention is both a storage
cost and a privacy liability. Mitigation: `raw_payload` carries the shortest retention in the schema, is
configurable per store, and has a tested retention job.

## Reconsider when

Storage cost becomes material — the answer then is tiering old observations to cheaper storage, or dropping
`raw_payload` while keeping the normalised observation, not making observations mutable.
