# Engineering Quality Standards

Standards for the future implementation. Each is marked **essential** (a release gate) or **aspirational**
(worth doing, not worth blocking on). Marking everything essential is how standards get ignored.

## Module boundaries and dependency direction — essential

- Four modules: `core`, `application`, `adapters`, `web`. Dependencies point inward only.
- `core` declares **no runtime dependencies** in its `pom.xml`. This is the enforcement mechanism for ADR-002:
  a violation is a compile failure, not a review discussion.
- No provider name, provider string or HTTP type appears above the adapter layer.
- **Gate:** the build fails if `core` gains a runtime dependency.

## Naming — essential for the domain, aspirational elsewhere

- One vocabulary, from `domain-model.md`. The canonical enums are the only status words in the codebase; a
  provider's `success` or `on-hold` exists only inside the adapter that translates it.
- A class name says what it is: `AmountComparisonRule`, not `AmountHelper`. No `Manager`, `Util` or `Service`
  suffix without a reason that survives being asked.
- Test names read as specifications: `flagsMismatchWhenDifferenceExceedsToleranceByOneMinorUnit`.

## Domain invariants — essential

- The ten invariants in `domain-model.md` §4 are each covered by at least one test (traceability matrix in
  `testing-strategy.md` §2).
- Invariants are enforced in constructors and factories, not checked by callers. `Money.of` rejects a negative
  amount; no caller is trusted to check first.
- **Gate:** an invariant without a test is not an invariant.

## Input validation — essential

- Validate at the boundary: the HTTP layer validates requests, adapters validate provider responses, the core
  validates its input (FR-08).
- A typed exception naming the offending field. No `IllegalArgumentException` with a prose message, because an
  operator needs the field name to act.
- No `NullPointerException` escapes any module.

## Error handling — essential

- Typed exceptions per failure class. The distinction that matters most: a provider transport failure is never
  a `PaymentStatus` (FM-06), so adapters throw rather than returning `ABSENT`.
- No empty `catch`. No `catch (Exception e)` that swallows. Catching to add context and rethrowing is fine;
  catching to continue is a decision that needs a comment explaining why continuing is safe.
- Unrecognised provider values map to `UNKNOWN` rather than to a guess (INV-07).

## Configuration — essential

- All configuration from the environment; no environment-specific values in the repository.
- Secrets only from the secret store, referenced by `credential_ref` (NFR-10).
- Policy values (grace period, tolerance, thresholds) are versioned configuration, not constants, because they
  are guesses that pilot data will change (BR-05, BR-07, OI-05).
- The application fails to start on missing required configuration, rather than defaulting. A monitor that
  silently starts with the wrong polling interval is worse than one that refuses to start.

## Reproducible builds — essential

- Pinned dependency versions; no version ranges.
- A committed lock of the resolved tree, and a build that works from a clean checkout with no local state.
- The same build command locally and in CI.

## Automated tests — essential

- `core` tests run offline with no database, network or filesystem (NFR-03).
- Adapter tests run against recorded provider fixtures, not live credentials.
- The full suite runs on every change. With a pure core this costs seconds.

## CI quality gates

**Essential:**

0. The `UNKNOWN` enum values exist and the decision table is total over both enums (`TotalityTest`).
1. Compile with warnings as errors for the `core` module.
2. All tests pass.
3. `core` has no runtime dependencies.
4. No `double`, `float` or `BigDecimal` in the money path (NFR-02).
5. Secret scanning finds nothing.
6. Cross-tenant authorisation tests pass (T-05).
7. Log-redaction test finds no known secret in captured output (T-07).
8. The adapter endpoint allowlist contains no mutating endpoint (NFR-12, ADR-013).
9. **No rule class references `observedAt` or `retrievalSequence`** — a static check, because INV-11 is the invariant two separate defects violated.
9b. **No rule but the duplicate rule and the contradiction gate references the history list** — a static check enforcing INV-13, because deriving state from history is the refactor that would look most reasonable and be most wrong.
10. `GraceSuppressionTest` and `OrderIndependencePropertyTest` pass — the two property tests that encode the corrections.

**Aspirational:** a coverage floor; a mutation-score floor; static analysis beyond compiler warnings;
dependency-vulnerability scanning; performance regression detection.

Gates 3, 4, 6, 7 and 8 are unusual as CI checks and they are the ones worth having, because each enforces an
architectural decision that would otherwise decay into a preference. **No coverage percentage is a gate.** A
coverage floor measures whether lines ran, and `testing-strategy.md` §9 explains at length why that is weak
evidence. A floor would create pressure to write assertion-free tests to clear it.

## Documentation — essential for decisions, aspirational for code

- **Essential:** an ADR for every decision that would be expensive to reverse, including the alternatives and
  the reconsideration trigger. A decision made without one tends to be re-litigated from memory.
- **Essential:** `provider-capabilities.md` updated whenever provider behaviour is discovered, with a citation
  and a `[V]`/`[U]` label. Unknowns are listed, not assumed.
- **Aspirational:** method-level comments. The code and the tests should carry it. A comment explaining *why*
  a surprising rule exists (the instalment allowance) is worth more than ten explaining what a line does.

## Secret management — essential

- Secrets never in the repository, the application database, logs, exception messages or error-tracker
  payloads.
- Decryption happens in the adapter; the value never enters a domain object or crosses a module boundary.
- Separate credentials per environment, so a staging compromise is not a production one.
- Deleted from the secret store when an integration is removed.

## Logging — essential

- Structured, with an allowlist of fields. Log fields because they were chosen, not by serialising an object —
  that is how credentials and personal data leak.
- Every log line for a run carries the `run_id`, so one run's story can be read end to end.
- Log the decision, not the data: "finding ORDER_UNPAID_DESPITE_PAYMENT for order ref X" rather than the
  payload.
- `raw_payload` is stored in the database and never logged.
- Levels: `ERROR` only for things needing human action; `WARN` for degraded-but-handled; `INFO` for run
  lifecycle.

## Minimal dependencies — essential

- `core`: zero at runtime.
- Elsewhere: a dependency must earn its place by replacing code we would otherwise write and maintain. The
  question is not "is this library good?" but "what would we write instead, and how long would that take?"
- No dependency added for a single function.

## Code review checklist

For a solo founder this is a self-review checklist, which is weaker but not worthless. The items are the ones
where this architecture's specific mistakes would appear:

**Correctness**

- [ ] Does a new rule change the priority order in requirements §4.1? If so, is the decision table test updated?
- [ ] Is every new `FindingType` present in all 80 decision-table cells where it could apply?
- [ ] Does any money comparison use the **requested** amount where it should, not the collected amount? (FR-02)
- [ ] Is a new boundary tested at −1, exactly, and +1?
- [ ] **Does any temporal rule read `observedAt` or `retrievalSequence`?** It must not (INV-11)
- [ ] **Does this let transaction history influence which decision-table cell is selected?** It must not (INV-13)
- [ ] **Does a new `FindingType` or amount rule let an absent `amountAtRisk` change severity?** It must not (INV-08)
- [ ] **Could repeated evaluation of an unchanged discrepancy change its verdict?** It must not (INV-12)
- [ ] Is a new rule's outcome invariant under permutation of the transaction-record list (INV-09)?
- [ ] Does a new `OrderObservation` with status `ABSENT` come from a *completed* sweep window (BR-11)?

**Safety**

- [ ] Does this add a provider endpoint? Is it in the allowlist, and is it non-mutating? (ADR-013)
- [ ] Does a provider call failure produce a finding anywhere? It must not. (FM-06)
- [ ] Does any query lack a tenant scope? (T-05)
- [ ] Can a credential reach a log, an exception message or a domain object? (T-04, T-07)

**Design**

- [ ] Does `core` still have no runtime dependencies?
- [ ] Does any provider string appear above the adapter?
- [ ] Is a new observation written as append-only? (ADR-005)
- [ ] Does a new write path have a natural key making it idempotent? (ADR-006)

**Honesty**

- [ ] Is a new provider capability claim backed by a citation in `provider-capabilities.md`?
- [ ] Is a new unknown added to the unknowns table rather than assumed away?

The last two matter more than they look. This architecture's main defence against inventing provider behaviour
is a habit of writing down what has actually been verified.
