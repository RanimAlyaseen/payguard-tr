# Strongest Evidence for the Seven-Page SVV Report

Ranked by what it demonstrates about verification and validation, not by how much work it took.
Seven pages is roughly 3 500 words: four or five of these, developed properly, will fill it. Taking
all twelve would produce a list instead of an argument.

---

## Which defects to explain deeply — read this first

The project now has **five** implementation defects. Seven pages cannot carry five case studies, and
trying would turn the report into a list. Explain **two** deeply and compress the rest.

**My recommendation: DEF-003 deep, DEF-001 deep, the other three in a compact table.**

This differs from the obvious pick of DEF-002, and the reason is worth stating. DEF-002 and DEF-003
teach the *same* lesson — an oracle written from the implementation rather than the specification —
and spending two of seven pages on one idea is waste. DEF-003 is strictly the stronger instance:

| | DEF-002 | DEF-003 |
|---|---|---|
| What the test asserted | that a note appeared in `reasons` | that a raw `NullPointerException` was thrown |
| What the specification said | the note belongs in `decisionPath` | FR-08 forbids any untyped exception escaping |
| Where the bad assertion lived | the duplicate-payment suite | **`InputValidationTest` — the class whose entire purpose is FR-08** |
| Severity of the underlying defect | cosmetic noise on a clean verdict | a contract violation at the module boundary |
| The `@DisplayName` | neutral | **"a null payment or order is rejected at construction" — documenting the wrong contract in prose** |

DEF-003 is the same failure with the stakes raised: a test class named after a requirement,
certifying behaviour that requirement prohibits, in a human-readable sentence. Mention DEF-002 in a
single sentence inside the DEF-003 discussion as the first appearance of the pattern — it
corroborates without consuming space.

**DEF-001 is the second deep dive** because it teaches a *different* lesson and anchors §6, a
separately graded section: a method every decision-table case exercised, with the semantic partition
still untested. Oracle independence and coverage limits are two distinct ideas; two deep dives
should not both be the first one.

Compress the remaining three into a table like this:

| ID | Defect | Requirement | Lesson in one line |
|---|---|---|---|
| DEF-002 | Limitation note written to `reasons` as well as `decisionPath` | FR-05.4 | The first appearance of the oracle problem, in milder form |
| DEF-004 | BR-07b enforced by the static factory but not by validation, so a caller could move the grace anchor | BR-07b, INV-12 | An invariant guarded on the convenient path and left open on the reachable one |
| DEF-005 | Currency gate compared one amount per side while four could disagree | FR-03, FR-08 | A convenience accessor answering a question the caller should not have asked |

**The one-sentence finding that ties all five together**, and the strongest single claim in the
report: four of the five are the same conflation — *an absent value treated as a benign one*. An
unknown refund read as zero (DEF-001), a null history read as an empty one (DEF-003), one currency
read as *the* currency when three fields could disagree (DEF-005), and in the architecture review, a
zero exposure meaning both "nothing at risk" and "cannot tell". DEF-004 is the lone exception.

**And the finding about process:** a 683-check suite, derived from the specification and passing
completely, missed three defects that an independent reader with only the specification found in one
pass. The suite was not weak — all 80 cells, every boundary, four invariants as properties. It was
written by the same person who wrote the implementation, and it inherited that person's assumptions.
That is a conclusion about verification method, not about effort, and it is the kind of thing the
"critical discussion" criterion is asking for.

---

## Tier 1 — the three that should anchor the report

### 1. A test that endorsed the defect it was meant to catch (DEF-003, with DEF-002 as its milder first appearance)

The single most valuable item. The assertion read
`noHistory.reasons().contains("duplicate_detection_unavailable_no_history")` — written by looking at
what the code did, not at what FR-05.4 specifies. It passed, and would have kept passing for exactly
as long as the defect existed, because it was a restatement of the defect in assertion form. A
681-check suite otherwise derived from the specification did not catch it (that count is
historical — the figure at the moment DEF-002 was found; the final harness count is 728). Reading the program's
output as a user did.

**Why it earns the space:** it is a concrete, personally-encountered instance of the central problem
in software verification — that tests derived from the implementation verify nothing. Most student
reports assert that oracles must be independent. This one can show the moment the principle was
violated, what it cost, and what was changed in response (the test now asserts the specification's
placement *and* that a clean match carries no reasons at all, which is the stronger claim FR-05.4
implies).

**Supporting artefacts:** `defect-log.md` §2; the before-and-after assertion; the demo output line
showing a clean `MATCHED` carrying a spurious reason.

### 2. High coverage of the specified cases, zero coverage of the unspecified one (DEF-001)

`Exposure.collectedLessRefunded` returned the collected amount when the refunded amount was unknown,
where §4.4 requires the exposure to be absent unless both amounts are known. Every one of the 104
decision-table cell checks exercised that method repeatedly, yet the missing semantic partition
remained untested. The defect survived because no test asked what the method should return when an
input was *absent* rather than merely different.

(Phrase this carefully in the report. JaCoCo was run only after the fix, and it reports per class
rather than per line, so "its line coverage was complete at the time" is not something the evidence
supports. "Exercised repeatedly by the decision-table scenarios, with the semantic partition still
untested" is what can actually be shown, and it makes the same point.)

**Why it earns the space:** it is a precise, non-theoretical demonstration of coverage's blind spot,
which the course covers and most reports illustrate with a textbook example. The partition that
mattered was not a range of values but the three distinguishable states of an `Optional` — present
and non-zero, present and legitimately zero, absent because uncomputable. Equivalence partitioning
over *meanings* found it; 104 passing cell checks did not.

**Supporting artefacts:** `defect-log.md` §1; the offending code comment, which documented the
substitution the specification forbids; the three-state test in `AmountAtRiskTest`.

### 3. An expected-value oracle checked against the specification

The expected decision-table fixture was created from the requirements rather than from
implementation output. The final project includes `SpecTableChecker`, which independently re-parses
requirements §4.2 and verifies all 80 committed expected cells against the specification. **The
checker reports 0 mismatches** (`evidence/spec-table-check.txt`), along with the table's
composition: 66 fixed, 12 threshold-conditional, 2 agreement.

`SpecDecisionTable.java` and the implementation's own `DecisionTable.java` are two separate
transcriptions of one source document, compared against each other by 80 parameterised cases, so a
disagreement is a test failure rather than a silent agreement between code and a test written from
that code.

**State the provenance accurately.** The original transcription was produced by an ad-hoc script
that was not retained, so do **not** write that `SpecDecisionTable` is reproducibly generated by the
current project. What is true, and stronger because it is checkable, is that the committed fixture
is independently verified against the specification on every run of the checker.

**Why it earns the space:** it answers the oracle problem concretely for an 80-cell decision table,
and it is the structural fix for the failures in items 1 and 2. The 80-cell table is the one part of
this project that contained no defects at all — every defect in the log is in code whose expectations
were hand-written. Worth a short diagram: requirements §4.2 → committed fixture, with
`SpecTableChecker` closing the loop back to the document, and the implementation's table arriving
from the same document by a different route.

---

## Tier 2 — strong, pick one or two

### 4. The grace-period defect: more monitoring producing less detection

Revision 0.1 measured discrepancy age from `max(observedAt, lastModifiedAt)` — a retrieval time.
Every poll produced a fresh `observedAt`, restarting the grace window, so a discrepancy polled every
five minutes under a fifteen-minute grace period would never be reported at all. The product's core
promise inverted: the more often PayGuard looked, the less it could see.

`GraceSuppressionTest` is the regression test. It re-polls one unchanging discrepancy once a minute
for a hundred minutes with a fresh retrieval time each pass, and asserts that the window opens once
and closes once: 15 suppressed, 85 reported, never re-entered.

**Why it earns the space:** a specification defect with a severe operational consequence, caught by
review before any code existed, and now pinned by a property test rather than an example. Good
material for the design-review-versus-testing comparison.

### 5. Four review rounds, each finding a different class of defect

| Round | Method | What it caught |
|---|---|---|
| 0.1 self-review | Document against itself | A counting error (64 cells claimed, 72 required, `DRAFT` missing) and a citation over-claim |
| 0.2 | Re-deriving behaviour from the requirements | Three logic defects |
| 0.3 | Documents against each other | Two cross-document contradictions |
| 0.3.1 | Searching for *retired* terminology | 18 live sections an earlier audit had wrongly passed |

**Why it earns the space:** the progression is the finding. Checking a document against itself catches
typos; re-deriving behaviour catches logic; checking documents against each other catches the
contradictions that make two developers build two different systems. Four rounds of the same
technique would have found far less than four rounds of four techniques.

### 6. The audit that could not fail

The 0.3.1 patch exists because an earlier consistency audit reported passing while 18 violations sat
in the files. Two mechanical causes, both worth a paragraph:

- It asked *"is the new term present?"* and never *"is the old term still present?"* A check of that
  shape **cannot fail** while old and new terminology coexist. It passed 25 assertions against a
  document set that was 18 violations from consistent.
- `str.replace` has no failure mode. One replacement's target wrapped differently in the file than in
  the replacement string, matched nothing, and reported success.

Every replacement in the project now asserts its target was found before writing, and the audit
searches for retired terms rather than current ones.

**Why it earns the space:** a verification tool that cannot fail is worse than no tool, because it
produces false confidence. Pairs naturally with item 1 — the same error at a different level.

---

## Tier 3 — use as supporting detail, not sections of their own

### 7. One defect shape, four appearances

Three specification defects and one implementation defect share a single shape: **one field carrying
two meanings.** `observedAt` was provenance and domain time. `amountAtRisk`'s zero was a value and an
absence. `PaymentEvent` was a notification and a history row. DEF-001 is the same conflation at the
implementation level, reintroduced below a specification that had already been corrected for it.

A strong closing observation: the specification was fixed, and the defect still reappeared in the
code, because the convenience default feels locally reasonable at the point where it is written.

### 8. Properties where examples would not do

Two invariants are tested as properties rather than examples, each for a stated reason:

- **INV-06, repetition invariance.** PayTR retries its callback after a minute and its transaction
  report can return the same row across overlapping windows, so repeated content is the norm in
  production, not an edge case. Tested at 1, 2, 3, 4, 5 and 10 repetitions.
- **INV-09, order independence.** All 24 permutations of four records, asserting that the finding,
  the exposure, the reasons *and the decision path* are identical — a differing path means a rule
  consulted the retrieval order. Exhaustive rather than random, because the space is small enough to
  exhaust and an exhausted space needs no seed to reproduce.

### 9. Boundary value analysis where the boundary has a consequence

Every threshold is tested at the value, one millisecond below and one millisecond above: the grace
period (15 min), the in-progress threshold (24 h), the authorisation threshold (72 h) and the amount
tolerance in both directions. Each boundary is where a finding changes severity, so an off-by-one is
the difference between a merchant seeing a false alarm daily and seeing nothing at all.

### 10. Negative testing that draws a line most systems blur

FR-08 separates two things that look alike: data PayGuard cannot interpret (a typed exception,
because returning a finding would be a lie) from data that is valid but uninformative (a finding of
`INSUFFICIENT_DATA`). Getting it wrong in either direction is a real failure — throwing on an
unrecognised status would make PayGuard fall over on any WooCommerce plugin, since WooCommerce order
statuses are extensible; returning a finding for a negative amount would launder bad data into a
report a merchant acts on. 13 tests, nine rejections and four acceptances.

### 11. A domain rule that only exists because the provider's docs were read first

PayTR's callback documentation states that the collected total can legitimately exceed the requested
amount on an instalment payment, because the customer pays the bank's surcharge. A natural
"collected ≠ requested means trouble" check would flag every instalment sale in Turkey. The allowance
is one-directional and tested as such: excess permitted with instalments, flagged without them, and
**never** excusing under-collection, at 1, 2, 3, 6, 9 and 12 instalments.

Worth one sentence on validation versus verification: no amount of verification against a wrong
specification would have caught this. It came from reading the provider's documentation before
writing the rule.

### 12. Reachability reported as reachability

All 16 declared finding types are produced by the suite's scenarios, measured by running them. This
is reported as reachability of declared outcomes and explicitly **not** as code coverage, which was
not measured in this environment.

---

## What to say about coverage

No coverage figure exists yet: the JaCoCo agent could not be obtained in the build environment.
Run `mvn clean test` then `mvn jacoco:report` and take the real line and branch numbers from
`target/site/jacoco/index.html`.

Then make the point item 2 sets up: DEF-001 lived in a line that every decision-table test executed.
Whatever percentage comes back, it was already complete for that method, and the defect was there
anyway. Coverage measures which lines ran, not whether the right questions were asked about them —
and a report that states its own coverage figure *and* its limits is stronger than one that reports
either alone.

## What not to claim

- No mutation score. PIT was not run.
- No performance figures. Nothing was benchmarked.
- No JUnit pass/fail count until the post-patch `mvn clean test` has actually run. The earlier
  278-test figure predates DEF-003, DEF-004 and DEF-005 and is void.
- **Three different measurements, never merged.** (a) JUnit executions, from Surefire — the headline
  figure. (b) 728 standalone harness checks, from a dependency-free runner over the same
  specification fixtures, used during development because JUnit could not be fetched in the build
  environment; never call these JUnit tests. (c) 80 specification-table checks from
  `SpecTableChecker`, which compares the committed fixture against requirements §4.2. Cite each as
  what it is.
- No mutation score unless PIT has actually run.
- Do not call `SpecTableChecker` the original generator. The original script was not retained.
- Do not describe the architecture-review defects as implementation defects. Seven defects were found
  in documents before any code existed, which is a stronger claim than finding them in code — but
  only if it is stated accurately.
