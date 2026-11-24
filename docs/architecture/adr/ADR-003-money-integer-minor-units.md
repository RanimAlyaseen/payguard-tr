# ADR-003 — Money as integer minor units, not BigDecimal

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

Money comparison is the arithmetic the product exists to perform. PayTR transmits amounts multiplied by 100 —
34.56 arrives as `3456` ([docs](https://dev.paytr.com/en/iframe-api/iframe-api-2-adim)). WooCommerce returns
totals as decimal strings. Five currencies are possible at PayTR.

## Decision

A `Money` value type holding a `long` of minor units plus a `Currency` enum, representing a **non-negative**
monetary amount. All comparison and arithmetic happens in minor units. Cross-currency arithmetic throws
`CurrencyMismatchException`. No `double`, `float` or `BigDecimal` appears anywhere in the money path (NFR-02).
Conversion from a provider's decimal string to minor units happens in the adapter, once, at the boundary.

**Operations exposed in Scope A** (narrowed in revision 0.3):

| Operation | Status | Reason |
|---|---|---|
| `of(long, Currency)` | Kept | Rejects a negative amount |
| `plus(Money)` | Kept, with `Math.addExact` overflow detection | Needed for summing capture amounts |
| `absoluteDifference(Money)` | Kept | Always non-negative, so it cannot leave the type's domain |
| `isSameCurrency(Money)` | Kept | |
| ~~`minus(Money)`~~ | **Removed** | Incoherent with a non-negative type: it can produce a value `Money` cannot represent. Every current need is met by `absoluteDifference` — including the one real subtraction, `collectedAmount` minus `refundedAmount` for `PAYMENT_WITHOUT_ORDER`'s exposure, where `refunded ≤ collected` holds and the absolute difference is the same number |

Revision 0.2 exposed `minus()` while rejecting negative values, and the test plan contained a
`Long.MIN_VALUE − 1` case that cannot be constructed under the model. Both are gone. **No signed-money
abstraction is introduced**, because no current requirement needs one.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Keep revision 0.2's `minus()` and allow negative `Money` | Would make every amount in the system nullable-in-spirit — any field could be negative, and the non-negativity invariant that makes validation simple would be gone. No requirement asks for it |
| Keep revision 0.2's `minus()` and throw on a negative result | Two ways to express one operation, where the throwing one is only ever safe when the caller has already established the ordering — at which point `absoluteDifference` is the same call without the trap |
| `BigDecimal` with a scale | Correct, and the usual Java answer, but it carries rounding-mode decisions into every operation, compares badly (`equals` versus `compareTo` on differing scale is a classic bug), and is heavier than the problem needs when the gateway's native format is already an integer |
| `double` | Unacceptable. Binary floating point cannot represent 0.01 exactly, and this is a financial comparison |
| A plain `long` with the currency tracked separately | Loses the invariant. Nothing would stop adding lira to euros; the whole point of the value type is that the compiler and the constructor prevent it |

## Reasons

Minor units are the gateway's own representation, so the risky conversion happens once in an adapter rather
than repeatedly in rules. Integer arithmetic is exact, and `long` holds about 92 quadrillion kuruş, far beyond
any plausible order. Wrapping the currency with the amount makes INV-04 structural instead of a code-review
habit.

## Consequences

**Good:** exact comparison; the amount tolerance is also expressible in minor units with no ambiguity;
equality is value equality with no scale surprises; serialising as `{"minor": 125000, "currency": "TRY"}` means
no API client ever parses a decimal.

**Bad:** every display needs formatting; conversion from decimal strings must be written carefully and tested
hard (a `"12.5"` with one decimal place must become 1250, not 125); currencies with a different number of
minor digits would need the exponent to come from the `Currency` enum rather than being assumed to be 2.

## Risks

A sloppy string-to-minor conversion in an adapter undoes the whole benefit. Mitigation: one conversion utility,
tested against one-decimal, two-decimal, no-decimal and trailing-zero inputs, plus negative and malformed
strings.

## Reconsider when

A requirement genuinely needs a signed amount — a net settlement position, say, which can be negative. That
would be a new type (`SignedAmount`), not a loosening of `Money`, so that the non-negativity invariant survives
where it belongs.

Or when a currency with other than two minor digits is supported — the exponent must then come from `Currency`, which
is a small change inside `Money`, not a change of representation. Or if a requirement for proportional splits
(commission apportionment) appears, where rounding policy becomes a domain concern and `BigDecimal` with an
explicit rounding mode may be clearer for that specific calculation.
