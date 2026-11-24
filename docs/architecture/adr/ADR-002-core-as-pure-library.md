# ADR-002 — SVV core as a pure library, not a service

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

Two requirements meet here. The university assignment demands a self-contained Java application, testable with
JUnit 5, with no GUI, no database and no network, due 24 November. The product needs a reconciliation engine
whose correctness can be argued for.

The temptation is to treat these as separate things — a throwaway academic exercise and a real engine. That
would mean writing the hardest logic twice.

## Decision

`payguard-core` is a Maven module with **no runtime dependencies**. It exposes
`ReconciliationEngine.reconcile(ReconciliationInput) : ReconciliationFinding`. It reads no clock
(`evaluatedAt` is injected), performs no I/O, holds no mutable state, and knows nothing about PayTR, iyzico,
HTTP or SQL. The commercial product depends on this module unchanged.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Core as a small HTTP service | Fails the assignment outright (network dependency), and makes every domain test an integration test |
| Core embedded in the application layer, not separated | Nothing would stop a rule from reaching for the database or the clock, and the purity that makes testing cheap would erode within weeks |
| Two codebases — an academic demo and a real engine | Doubles the work on the only part that must be right, and the demo would rot immediately |

## Reasons

The value of a reconciler is a judgement, and judgements are best tested as functions. Purity buys exact
boundary testing (the grace period boundary is a value, not a race), deterministic property tests for the
idempotency invariants, and tests that need no mocking framework. The assignment's constraints are not a
limitation here; they describe good domain design.

Injecting `evaluatedAt` rather than calling `Instant.now()` is the single change that makes time-dependent
rules testable at exact boundaries.

## Consequences

**Good:** the deliverable and the product share one implementation; domain tests run in milliseconds offline;
the module can be published and reused; coverage of the core is meaningful because there is no infrastructure
noise in it.

**Bad:** callers must assemble the input, which pushes fetching and mapping work into the application layer;
the core cannot log through the application's logger, so it returns a `decisionPath` instead of logging
(which is better for audit anyway, but it is a constraint).

## Risks

Pressure to "just add a lookup" inside a rule when some fact is missing from the input. Mitigation: no
dependencies in the core's build file, so such a change cannot compile.

## Reconsider when

Never, for the core's purity. If a rule genuinely needs external data (a per-merchant exception list, say),
that data becomes part of `ReconciliationPolicy` and is passed in — the input grows, the purity stays.
