# ADR-012 — Java 21 and Maven for the core; a deliberately boring stack around it

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

The assignment mandates Java, JUnit 5 and Maven, with JaCoCo for coverage. The product does not have to use
the same stack outside the core — ADR-002's core is a library the product depends on, and the brief says the
commercial architecture must not be artificially limited by the assignment. The brief also says not to choose
technology for popularity or fashion.

## Decision

**Core:** Java 21, Maven, JUnit 5, JaCoCo, zero runtime dependencies. PIT for mutation testing, optional.

**Around it, for the MVP:** the same JVM, a mainstream web framework, PostgreSQL, server-rendered pages, the
hosting platform's managed secret store, an in-process scheduler with a database lock. No message broker, no
cache, no container orchestration, no separate front-end application, no event-streaming platform.

The specific web framework is deliberately left unfixed in this document: it is a Milestone 2 decision, it is
replaceable, and naming it now would be choosing before the question is live.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| A different language for the product, with the Java core called over a boundary | Would mean serialising across a process boundary for an in-memory function call, plus two toolchains for one developer. The core being a library is the whole benefit of ADR-002 |
| Rewrite the core in the product's language after the assignment | Two implementations of the only logic that must be right, diverging from the first commit |
| Kotlin for the application layer, Java core | Defensible and tempting. Rejected only to keep one language for one developer under time pressure; the core's API is language-neutral on the JVM, so this stays open |
| A single-page front end with a JSON API | The MVP's UI is a list of findings and a detail page. Server-rendered pages need no build pipeline, no state management and no second deployable |
| Message broker between ingestion and reconciliation | Solves a decoupling problem the monolith does not have (ADR-001), and adds a durable system to operate |
| NoSQL document store | The data is relational and the correctness depends on unique constraints (ADR-006). `jsonb` columns cover the one document-shaped need, `raw_payload` |

## Reasons

Java 21 brings records, sealed interfaces and pattern matching, which express the domain model cleanly — a
sealed `ReconciliationRule` hierarchy and `record` value types fit `Money`, the observations and the finding
almost exactly, and records give value equality without boilerplate, which matters because `Money` equality is
a correctness property (ADR-003).

Zero dependencies in the core is the real decision here. It satisfies the assignment, keeps domain tests fast
and offline, and means the module can never be held back by someone else's upgrade.

Everything outside the core is chosen to be unremarkable, because a solo founder's scarce resource is
attention, and every piece of infrastructure spends some.

## Consequences

**Good:** one language, one build tool, one test framework; coverage and mutation tooling are mature;
the core's lack of dependencies eliminates an entire class of supply-chain and upgrade risk; the stack is
easy to hire for and easy to hand over.

**Bad:** JVM startup and memory are heavier than some alternatives for a small periodic workload; Java is more
verbose than Kotlin for the same model; and "mainstream web framework" deferred means a Milestone 2 decision
still to make.

## Risks

Framework choice creeping into the core by convenience — an annotation here, an injected dependency there.
Mitigation: the core's `pom.xml` declares no runtime dependencies, so any such creep is a compile failure, not
a style debate.

## Reconsider when

The core's API stabilises and a second language genuinely suits the application layer better; or the periodic
workload becomes continuous enough that JVM resource use matters. Neither is close.
