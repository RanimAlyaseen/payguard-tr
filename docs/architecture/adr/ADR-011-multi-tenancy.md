# ADR-011 — Shared schema with enforced scoping

**Status:** Accepted, with a known risk · **Date:** 22 Nov 2026

## Context

One agency may manage many stores for many merchants. Tenants are organisations; stores belong to
organisations; findings and observations belong to stores. Cross-tenant data access is the most damaging
single bug this product could have, because the data is other people's payment records.

## Decision

One database, one schema, with `organization_id` carried on every tenant-scoped table and enforced in a
repository layer that **requires** a tenant context to construct a query. No query path exists that can omit
the scope. Every HTTP endpoint has a test asserting that a caller from organisation A receives `403` or `404`
for a resource in organisation B.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| A database per tenant | Strongest isolation, and plainly the safest. Rejected for now because migration, backup, connection pooling and cost multiply per customer, and an agency view across stores becomes a cross-database join. Revisit if a customer requires it contractually |
| A schema per tenant | Middle ground, but migrations across many schemas are an operational burden, and the isolation benefit is weaker than people assume once one application connects to all of them |
| Row-level security in PostgreSQL | Genuinely attractive, because the database enforces it rather than the application. Not chosen yet only because it requires session-variable discipline on every connection and the pooling interaction needs care. **This is the most likely thing to change about this decision** |
| Application-level filtering without a repository constraint | What most leaks look like. One hand-written query without a `where organization_id` clause is enough |

## Reasons

At MVP scale the operational cost of per-tenant databases is real and the number of tenants is small. The
honest reason is pragmatism, not security: a shared schema is weaker isolation, and this record should say so
rather than dress it up.

What makes it defensible is where the enforcement sits. Scoping in a repository layer that cannot build an
unscoped query makes the safe path the only path, which is the same reasoning as ADR-006 putting idempotency in
database constraints rather than application checks.

## Consequences

**Good:** one migration, one backup, one connection pool; the agency cross-store view is an ordinary query; new
tenants cost nothing to provision.

**Bad:** isolation depends on application code being correct, which is a weaker guarantee than the database
refusing; a single database means one tenant's heavy query can affect others; a backup restore restores
everyone, so a single-tenant recovery is awkward.

## Risks

**This is the highest-severity accepted risk in the architecture.** A missing scope clause exposes one
merchant's payment records to another. Mitigations: scoping enforced structurally in the repository layer; a
cross-tenant authorisation test per endpoint as a release gate; `403` attempts written to the audit log and
alerted on; and code review specifically checking any hand-written query.

Logged as OI-07 in the SAD rather than treated as closed.

## Reconsider when

The first enterprise or agency customer asks for data isolation in writing; or the cross-tenant test suite
catches a real leak in development, which would be evidence the application-level approach is too fragile to
keep. Row-level security is the first step to take in either case, before splitting databases.
