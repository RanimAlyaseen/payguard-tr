# ADR-001 — Modular monolith, not microservices

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

PayGuard's MVP has one scheduled workflow: fetch, compare, store, notify. It has one developer. It has no
users yet. The brief says to prefer a modular monolith unless there is a compelling reason otherwise, and asks
that technology not be chosen for fashion.

## Decision

One deployable application with enforced internal module boundaries: `core` (pure domain), `application`
(use cases), `adapters` (providers), `web` (HTTP), with dependencies pointing inward only. One relational
database. The scheduler runs in-process with a database lock.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Microservices (ingestion, reconciliation, notification as separate services) | Introduces network partitions, partial failures and distributed-transaction problems — the exact class of failure PayGuard exists to detect in other people's systems. One developer would spend their time on operations, not on reconciliation rules |
| Serverless functions per step | Cold starts and a 15-minute-plus run for a large store do not fit the model well; local development and testing get harder; state passing between functions needs infrastructure the monolith gets for free |
| Modular monolith with a separate worker process | Reasonable, and the likely next step, but two processes to deploy before there is a load problem is premature |

## Reasons

Module boundaries give the benefit people attribute to microservices — independent reasoning about parts —
without the distribution cost. Boundaries enforced by package structure and dependency direction can be split
later; a distributed system cannot be recombined cheaply.

## Consequences

**Good:** one thing to deploy, debug and monitor; in-process calls so no serialisation between layers; a
single transaction around "persist observations and findings"; trivial local development.

**Bad:** one scaling unit, so a heavy store competes with a light one for the same process; a crash takes
everything down; the boundary discipline is a convention that code review must police rather than something
the network enforces.

## Risks

A team that does not respect the dependency direction turns a modular monolith into a tangle. Mitigation: the
`core` module has no dependencies declared in its build file, which makes a violation a compile error rather
than a matter of taste.

## Reconsider when

Reconciliation runs for one store start starving others; or run duration exceeds the scheduling interval and
horizontal scaling of the worker becomes necessary; or more than about four engineers are editing the same
modules. Splitting the worker out comes first, not a full decomposition.
