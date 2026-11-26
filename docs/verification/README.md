# Verification documents

| Document | Contents |
|---|---|
| [`implementation-summary.md`](implementation-summary.md) | What was built, the measured results, and the known limitations |
| [`defect-log.md`](defect-log.md) | The five implementation defects (DEF-001 to DEF-005): failing behaviour, expected behaviour, root cause, why it was missed, fix |
| [`evidence/`](evidence/) | Captured output of the runs quoted in the documents and the report |
| [`report-notes/`](report-notes/) | Working notes used while preparing the course report |

## Evidence files

| File | What it shows |
|---|---|
| `maven-surefire-final.txt` | Final JUnit run: 304 tests, 0 failures, `BUILD SUCCESS` |
| `jacoco-final.csv` | JaCoCo coverage counters for every class, from the final run |
| `def001-fault-injection-run.txt` | A real failing run: the DEF-001 fix was removed in a separate copy, giving `Tests run: 304, Failures: 2` and `BUILD FAILURE` |
| `def001-fault-run/` | The Surefire report files from that failing run (see its `README.txt`) |
| `specification-verification-run.txt` | The standalone harness: 728 / 728 checks |
| `spec-table-check.txt` | `SpecTableChecker`: 80 expected cells checked against requirements §4.2, 0 mismatches |
| `finding-type-reachability.txt` | All 16 finding types can be produced |
| `demo-run.txt` | Output of the eight demonstration scenarios |
| `environment.txt`, `maven-offline-attempt.txt` | The development environment, and why JUnit could not run there |

The harness, specification-table and JUnit results are separate measurements and are never added
together. Only the Surefire run is a JUnit result.
