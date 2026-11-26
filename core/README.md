# PayGuard TR — reconciliation core (Scope A)

The academic deliverable: a pure, dependency-free reconciliation engine with a JUnit 5 verification
suite. No GUI, no database, no network, no gateway integration.

## Run it

```bash
cd core
mvn clean test                 # the JUnit 5 suite
mvn jacoco:report              # coverage -> target/site/jacoco/index.html
mvn -q compile exec:java       # the eight-scenario demonstration
```

Requires JDK 21+ and Maven 3.9+.

### Measured results

| Measurement | Result |
|---|---|
| JUnit executions (Surefire) | **304 run, 304 passed**, 0 failures, 0 errors, 0 skipped — `BUILD SUCCESS` |
| Coverage, whole module (JaCoCo) | line **79.5%** (586/737), branch **78.8%** (208/264), method **81.7%** (125/153), instruction **83.0%** (3148/3795) |
| Coverage, excluding `ReconciliationDemo` | line **93.8%** (586/625), branch **82.5%** (208/252), method **91.2%** (125/137), instruction **93.5%** (3148/3367) |
| Standalone harness checks | 728 / 728, 0 failed |
| Specification-table checks | 80 cells, 0 mismatches |

The suite declares 126 test methods across 28 classes; parameterised methods expand at run time to
the 304 executions above.

The Surefire, harness and specification-table rows are three different measurements and are never
merged: only the Surefire row is a JUnit result. `ReconciliationDemo` accounts for 112 of the 151 uncovered lines — it is a `main`
that prints for a human to read. The whole-module figure is the project's coverage; the
demo-excluded view is offered as explanation, not as a replacement, and JaCoCo is deliberately **not**
configured to exclude the demo.

Five implementation defects (DEF-001 to DEF-005) were found and fixed; each has an explicit
regression test, and all of them execute in the Surefire run above.

## Layout

```
src/main/java/com/payguard/core/
  error/     typed exceptions; nothing untyped leaves the module (FR-08)
  model/     Money and the three immutable observations
  policy/    ReconciliationPolicy — every threshold, no hard-coded constants
  result/    FindingType, Severity, RecommendedAction, ReconciliationFinding
  engine/    the 80-cell decision table, history normalisation, the rule chain
  rules/     one class per rule of requirements §4.1, in priority order
  demo/      ReconciliationDemo — standard output only

src/test/java/com/payguard/core/
  spec/      shared specification data (see below)
  engine/ model/ rules/ result/    28 JUnit 5 classes, 126 declared test methods
  regression/                      explicit regression tests for DEF-001 to DEF-005

verification/  a dependency-free harness (see below)
```

## Two things worth knowing before reading the tests

**`spec/SpecDecisionTable.java` holds expected values taken from the specification, not from
program output.** The implementation's table in `engine/DecisionTable.java` is a separate
transcription of the same document, and the two are compared cell by cell, so a disagreement is a
test failure rather than a silent agreement between code and a test written from that code.

The committed fixture was first produced by an ad-hoc script that was **not retained**, so the
project cannot regenerate it. What it can do is check it: `verification/.../SpecTableChecker.java`
re-parses `docs/architecture/requirements.md` §4.2 on every run and verifies all 80 committed cells
against the specification, reporting 66 fixed, 12 threshold-conditional, 2 agreement and 0
mismatches. If §4.2 changes, update the fixture and re-run the checker — never edit an expected
value to match observed output.

```bash
java -cp target/classes:<verification classes> com.payguard.verify.SpecTableChecker \
     ../docs/architecture/requirements.md
```

**`verification/` is not the test suite.** JUnit 5 could not be downloaded in the environment where
this was built (Maven Central unreachable), so the same specification fixtures were run through a
dependency-free harness to produce real pass/fail numbers instead of claimed ones. The JUnit suite
under `src/test/java` is the canonical one. Run it.

## Verification documents

These live in [`docs/verification/`](../docs/verification/):

- [`implementation-summary.md`](../docs/verification/implementation-summary.md) — what was built, real execution numbers, limitations
- [`defect-log.md`](../docs/verification/defect-log.md) — the five implementation defects (DEF-001 to DEF-005), with root causes
- [`evidence/`](../docs/verification/evidence/) — captured output of every run quoted in the reports
