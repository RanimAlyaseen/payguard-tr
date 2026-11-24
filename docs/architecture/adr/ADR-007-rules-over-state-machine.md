# ADR-007 — Prioritised rule chain, not a state machine

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

The brief asks for an explicit comparison of a state machine against rule-based classification. Both are
plausible: payments and orders each have lifecycles, and a state machine is the usual tool for a lifecycle.

## Decision

Classification is a **prioritised chain of rules**, one class per `FindingType`, evaluated in a fixed order
with the first match winning (requirements §4.1). A separate, small state machine governs the **finding's**
workflow (open → acknowledged → resolved), which is a genuine single-entity lifecycle.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| A state machine over the payment lifecycle | PayGuard does not own the payment's lifecycle; the gateway does. Modelling transitions we neither cause nor control would mean inventing a machine whose states the provider can change without telling us, and whose "illegal transitions" are just observations we did not expect |
| A state machine over the *pair* of states | The pair has 8 × 10 = 80 combinations with no transitions between them that PayGuard observes — we see snapshots, not a path. A machine with 80 states and no edges is a lookup table with extra ceremony |
| One method with nested conditionals | Equivalent in behaviour, but every new finding type edits the same method, the priority order becomes implicit in the nesting, and the decision table stops being readable in the code |
| A rules engine (Drools or similar) | A dependency and a second language for thirteen rules. It would also break ADR-002's no-dependency constraint and the assignment's constraints with it |

## Reasons

The problem is **classifying a pair of observed states**, not governing an entity's transitions. That is
pattern matching over a finite grid, and a prioritised rule chain expresses it directly: the grid is the
decision table, the priority list is the tie-break policy, and each rule class is independently testable.

Priority order carries real domain meaning that a flat table would hide. Currency mismatch outranks amount
mismatch because comparing across currencies is meaningless rather than merely wrong. Grace period outranks
every money finding because a recent disagreement is not yet evidence. Those are judgements worth writing down
as an ordered list someone can argue with.

One class per rule also maps the 80-cell table one-to-one onto test classes, which matters because test design
is what the assignment is graded on.

## Consequences

**Good:** adding a finding type adds a class and a registration, touching no existing rule; the priority order
is a single readable list; each rule is unit-testable in isolation; the `decisionPath` emerges naturally as the
record of which rules were evaluated and which fired.

**Bad:** more files than a single method; the order is load-bearing, so a careless reordering changes
behaviour — mitigated by the decision-table tests, which fail loudly if priority changes; and a reader must
consult the priority list to understand which of two applicable rules wins.

## Risks

Rule interaction. If two rules can both apply and the priority is wrong, the merchant sees the less useful of
two true statements. Mitigation: the decision table in requirements §4.2 enumerates all 80 cells with the
expected outcome, so the resolution of every overlap is specified rather than emergent.

## Reconsider when

PayGuard starts receiving an ordered stream of provider events it can trust as a complete history, rather than
snapshots. Then the payment genuinely has an observable lifecycle and a state machine over it becomes the
better model.
