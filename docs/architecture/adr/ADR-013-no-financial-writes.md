# ADR-013 — No mutating provider calls anywhere in the codebase

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

PayGuard finds orders that were paid but not fulfilled. The obvious next step — mark the order paid, or refund
the duplicate charge — is the step that turns a monitoring tool into something that moves other people's money.

The capability is close at hand. PayTR's Status Inquiry response already carries a `returns` block for
refunds that have been made ([docs](https://dev.paytr.com/en/durum-sorgu)), and third-party client libraries
list a refund endpoint at `/odeme/iade` — unverified against PayTR's own documentation, so recorded as such in
`provider-capabilities.md` §1.4. Either way, the credentials PayGuard holds to *read* status
(`merchant_key`, `merchant_salt`) are the same credentials used to authenticate every other PayTR API call,
and no read-only credential scope is documented (UNK-05).

So "read-only" is not something the provider enforces on PayGuard. It is something PayGuard must enforce on
itself.

## Decision

No mutating provider endpoint appears in the codebase. Not called, not wrapped, not behind a feature flag, not
in a disabled branch. Specifically excluded: PayTR `/odeme/iade`, any iyzico cancel or refund endpoint, and
every WooCommerce write.

Adapters carry an explicit allowlist of permitted endpoints. The build fails if an adapter can reach a URL
outside it (NFR-12). The MVP's `RecommendedAction` vocabulary has no `AUTO_REPAIR` value, so a repair cannot be
represented, let alone executed.

PayGuard's output is a statement to a human about what happened.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Approval-gated repair now: PayGuard proposes, a merchant clicks, PayGuard acts | The design is sound and this is the likely future. Rejected for the MVP because it needs its own threat model, its own audit guarantees, and confidence in the verdicts that only pilot data can give. Acting on an unvalidated classifier that moves money is the worst possible combination |
| A feature flag, default off | A flag is a code path, and a code path can be enabled by a mistake, a misconfiguration, or an attacker who reaches the configuration. The point of a structural exclusion is that there is nothing to enable |
| Auto-complete orders in WooCommerce only, never touch the gateway | Less dangerous, still wrong. Marking an order paid on a false positive means shipping goods that were never paid for, which is a real loss the merchant did not consent to |
| Generate a script the merchant runs themselves | Transfers the risk without reducing it, and a script written from a wrong verdict does the same damage with the merchant's name on it |

## Reasons

The asymmetry decides it. A missed finding costs a merchant one order's attention. A wrong automated action
costs them money, their customer's trust, and their trust in PayGuard, and it is not reversible by an apology.
With AS-01 unvalidated and the classifier untested against real data, the expected value of automation is
negative.

There is a second reason that matters more for credibility. The most honest thing PayGuard can tell a
merchant is "we only ever read". That claim is worth more than a feature, and it is only true if it is
structural. A flag makes it a promise; an allowlist makes it a property.

The restriction must also be stated honestly rather than oversold. PayGuard holds credentials that *could*
move money, so a compromise of PayGuard is a compromise of the merchant's refund capability regardless of what
PayGuard's own code does. That is OI-03, and merchants should be told it plainly rather than reassured with
the phrase "read-only access".

## Consequences

**Good:** the blast radius of a classification bug is a wrong message, not a wrong transaction; the security
review is far simpler; merchants can grant access with less fear; no approval-workflow machinery is needed in
the MVP.

**Bad:** merchants must act manually on every finding, which limits the value PayGuard can claim and arguably
caps what it can charge; a competitor offering one-click repair will look more capable; and the restriction
must be undone deliberately, with work, if automation is ever wanted.

## Risks

Pressure to add "just this one safe write" once a pilot merchant asks. Mitigation: the allowlist and the
absent enum value make it a visible architectural change requiring a new ADR, not a quiet commit.

## Reconsider when

**All** of these hold: the classifier's false-positive rate is measured on real merchant data across at least
one full pilot; a merchant explicitly asks for repair knowing the risk; a full threat model for the write path
exists; every action is approval-gated, reversible where the provider allows it, and audited; and the provider
offers credential scoping so the write capability can be granted separately from the read capability (UNK-05).

Until all five hold, this stays closed.
