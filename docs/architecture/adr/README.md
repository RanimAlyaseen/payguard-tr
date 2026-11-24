# Architectural Decision Records

Each record states the context, the decision, the alternatives actually considered, the reasons, the
consequences (good and bad), the risks, and — most importantly — **what would make us reconsider**. A decision
without a reconsideration trigger is a prejudice.

| ADR | Decision | Status |
|---|---|---|
| [001](ADR-001-modular-monolith.md) | Modular monolith, not microservices | Accepted |
| [002](ADR-002-core-as-pure-library.md) | SVV core as a pure library, not a service | Accepted |
| [003](ADR-003-money-integer-minor-units.md) | Money as integer minor units, not BigDecimal; non-negative, no `minus()` | Accepted, amended rev 0.3 |
| [004](ADR-004-query-first-not-webhook.md) | Bidirectional query-first reconciliation; webhooks optional | Accepted, rev 2 |
| [005](ADR-005-immutable-observations.md) | Immutable observations, derived findings | Accepted |
| [006](ADR-006-idempotency-natural-keys.md) | Idempotency by natural key | Accepted, amended rev 0.3 |
| [007](ADR-007-rules-over-state-machine.md) | Prioritised rule chain, not a state machine | Accepted |
| [008](ADR-008-out-of-order-events.md) | ~~Order events by occurrence time, not arrival~~ | **Superseded** by 015 and 016 |
| [009](ADR-009-failure-recovery-watermark.md) | Watermark advances only on success | Accepted |
| [010](ADR-010-adapter-ports.md) | Ports owned by the application, adapters implement them | Accepted |
| [011](ADR-011-multi-tenancy.md) | Shared schema with enforced scoping | Accepted, with a known risk |
| [012](ADR-012-technology-stack.md) | Java 21 + Maven core; boring stack around it | Accepted |
| [013](ADR-013-no-financial-writes.md) | No mutating provider calls in the codebase | Accepted |
| [014](ADR-014-bidirectional-discovery.md) | Gateway-first discovery as a first-class sweep | Accepted — supersedes part of ADR-004 rev 1 |
| [015](ADR-015-event-identity.md) | Record identity; conflicts are a data-integrity fault | Accepted, amended rev 0.3 |
| [016](ADR-016-authoritative-state.md) | **One authoritative source for gateway state** | Accepted — supersedes 008 |
