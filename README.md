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
| JUnit executions (Surefire) | _to be inserted from the final `mvn clean test` run_ |
| Line / branch coverage (JaCoCo) | _to be inserted from the final `mvn jacoco:report` run_ |
| Standalone harness checks | 728 / 728, 0 failed |
| Specification-table checks | 80 cells, 0 mismatches |

These are three different measurements and are never merged: only the first is a JUnit result.

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
evidence/      captured output of every run quoted in the reports
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

## Reports

- `implementation-summary.md` — what was built, real execution numbers, limitations
- `defect-log.md` — the five implementation defects (DEF-001 to DEF-005), with root causes
- `report-correction-sheet.md` — what in the report draft is stale, and what replaces it
- `report-evidence.md` — the strongest material for the seven-page SVV report, ranked
