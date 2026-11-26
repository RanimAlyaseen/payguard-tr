# PayGuard TR

[![CI](https://github.com/RanimAlyaseen/payguard-tr/actions/workflows/ci.yml/badge.svg)](https://github.com/RanimAlyaseen/payguard-tr/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![JUnit 5](https://img.shields.io/badge/tests-JUnit%205-green)
![Coverage](https://img.shields.io/badge/line%20coverage-79.5%25-yellowgreen)

**A payment reconciliation core for online shops, built as a Software Verification & Validation
project.** It compares what the payment gateway says about a payment with what the store says about
the matching order, and reports any disagreement as a typed, explainable finding.

> Example: the customer's card was charged, but the order is still marked *unpaid* — so the goods
> never ship. Neither system can see this on its own. PayGuard TR reports it as
> `ORDER_UNPAID_DESPITE_PAYMENT`, severity `HIGH`, with the amount at risk and the reasons.

This repository contains **Scope A**: the reconciliation engine as a pure Java library, its JUnit 5
test suite, and the verification evidence. There is no user interface, database or live gateway
integration, and the engine never moves money.

---

## Results

| Measurement | Result |
|---|---|
| JUnit 5 executions (Maven Surefire) | **304 run, 304 passed**, 0 failures, 0 errors |
| Line / branch coverage (JaCoCo, whole module) | **79.5%** / **78.8%** |
| Line / branch coverage (excluding the demo `main`) | 93.8% / 82.5% |
| Standalone specification harness | 728 / 728 checks passed |
| Decision-table expected values vs. requirements | 80 / 80 cells, 0 mismatches |
| Implementation defects found and fixed | 5, each with a regression test |

These are separate measurements and are never added together. Only the first row is a JUnit result.
The coverage figure is reported for the whole module on purpose; the demo is not excluded in the
JaCoCo configuration.

## Quick start

Requires **JDK 21+** and **Maven 3.9+**.

```bash
cd core
mvn clean test              # run the JUnit 5 suite (also writes the JaCoCo report)
mvn -q compile exec:java    # run the eight-scenario demonstration
```

The coverage report is written to `core/target/site/jacoco/index.html`.

## How it works

`ReconciliationEngine.reconcile(input)` takes one payment observation, one order observation, the
transaction history, a policy and an explicit evaluation time, and returns exactly one finding.

1. **Validate** the input. Malformed input throws a typed `InvalidReconciliationInputException`
   that names the wrong field; it never becomes a finding.
2. **Normalise** the history, so repeated or reordered records cannot change the result.
3. **Gates**, in priority order: insufficient data → currency mismatch → duplicate payment.
4. **Decision table**: 8 payment states × 10 order states = 80 cells, then an amount comparison.
5. **Grace period**: a disagreement that is too new to judge is reported as `WITHIN_GRACE_PERIOD`.

The engine is pure: it never reads the clock, uses no network or database, and keeps no state, so
the same input always gives the same output.

## Testing approach

| Technique | Where it is used |
|---|---|
| Equivalence Partitioning | Amounts split by *meaning* (known, known zero, unknown) — this found DEF-001 |
| Boundary Value Analysis | Every time limit (15 min, 24 h, 72 h) and the amount tolerance, at −1 ms / exact / +1 ms |
| Decision Table Testing | All 80 cells, with expected values taken from the requirements, not from program output |
| Negative testing | Malformed input must throw a typed exception; unknown statuses must return `INSUFFICIENT_DATA` |
| Invariant testing | Repetition invariance and order independence (all 24 orderings of four records) |
| Regression testing | One explicit test per defect in the `regression` package |

A real failing run is also kept as evidence: the DEF-001 fix was removed in a separate copy and the
suite reported `Tests run: 304, Failures: 2` and `BUILD FAILURE`
([log](docs/verification/evidence/def001-fault-injection-run.txt)).

## Defects found

| ID | Summary |
|---|---|
| DEF-001 | An unknown refund was treated as zero, so an amount that could not be calculated was reported as a real one |
| DEF-002 | A limitation note was written to the finding's reasons, and an old test endorsed it |
| DEF-003 | A raw `NullPointerException` could leave the module instead of a typed validation error |
| DEF-004 | A business rule (BR-07b) was enforced by the factory method but not by validation |
| DEF-005 | The currency check looked at one amount per side while four amounts could differ |

Root causes, fixes and the reason each one was missed are in the
[defect log](docs/verification/defect-log.md).

## Repository structure

```
payguard-tr/
├── core/                         Maven module: the reconciliation engine
│   ├── pom.xml
│   ├── src/main/java/…           engine, rules, model, policy, result, error, demo
│   ├── src/test/java/…           28 JUnit 5 classes, 126 test methods (304 executions)
│   └── verification/             dependency-free harness and SpecTableChecker
├── docs/
│   ├── architecture/             requirements, architecture document, ADRs, test strategy
│   └── verification/             defect log, implementation summary, run evidence
└── .github/workflows/ci.yml      builds and tests every push
```

## Documentation

- [Requirements](docs/architecture/requirements.md): functional requirements FR-01 to FR-08 and the
  80-cell decision table
- [Software architecture document](docs/architecture/SAD.md) and
  [architecture decision records](docs/architecture/adr/README.md)
- [Testing strategy](docs/architecture/testing-strategy.md)
- [Module README](core/README.md): package layout, the specification oracle and the harness
- [Verification documents](docs/verification/README.md): defect log, measured results and evidence

## Course context

Developed for **SWE411 Software Verification and Validation** (2026). The scope, results and
limitations are described in the [implementation summary](docs/verification/implementation-summary.md).
