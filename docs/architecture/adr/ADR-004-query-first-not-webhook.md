# ADR-004 — Bidirectional query-first reconciliation; webhooks optional

> **Revised 22 Nov 2026 (rev 2).** Rev 1 was correct that reconciliation must be query-driven, but specified
> only a shop-first sweep. That cannot detect a payment with no order, which is the product's headline
> scenario. Superseded in part by ADR-014.

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

The product vision describes "missing or delayed payment callbacks" as a target failure, which suggests
PayGuard should receive callbacks. The provider research says it cannot, or not reliably.

PayTR posts its notification to a **Notification URL set in the merchant's own panel**, which is a single
value and is the merchant's shop endpoint
([docs](https://dev.paytr.com/en/iframe-api/iframe-api-2-adim)). For PayGuard to receive it, the merchant
would have to repoint that URL away from their shop, which would break their order flow.

iyzico does support webhooks, with the first notification 10 to 15 seconds after the payment attempt, but the
`X-IYZ-SIGNATURE-V3` header is only sent if the webhook signature feature is enabled on the account, which
requires emailing their integration team
([docs](https://docs.iyzico.com/en/advanced/webhook)).

Both providers expose read-only query APIs: PayTR's Status Inquiry
([docs](https://dev.paytr.com/en/durum-sorgu)) and iyzico's Retrieve Payment and Transaction Detail Reporting
([docs](https://docs.iyzico.com/en/advanced/retrieve-payment),
[docs](https://docs.iyzico.com/en/advanced/reporting-service)).

## Decision

Reconciliation is driven by **scheduled queries** against provider read APIs and the shop's orders API, in
**both directions** — gateway-first and shop-first (ADR-014). A webhook, where a merchant has one available and
signature-verified, is accepted only as a **trigger** to run reconciliation sooner. Its payload is never the
source of truth; PayGuard still queries before deciding.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Event-driven on provider webhooks | Impossible for PayTR without breaking the merchant's shop. Conditional for iyzico on a per-account manual activation, so it cannot gate onboarding |
| Ask the merchant's shop to forward callbacks to PayGuard | Requires installing code in the merchant's shop, which turns a monitoring product into a plugin, adds a failure point inside the merchant's payment path, and contradicts NFR-08 |
| Webhook payload as truth when available, query otherwise | Two code paths for the same decision, and the payload-trusting path would be the less verified one. The product's credibility rests on its verdicts; two sources of truth is how you get contradictory ones |

## Reasons

A reconciler's job is to distrust both sides and compare them against the provider's authoritative record.
Querying *is* that. Treating a webhook as a hint gives lower latency where it is available without creating a
second decision path or a second trust assumption.

There is an honest limitation to state: PayGuard **cannot observe a missed callback directly**. It observes
the consequence — a captured payment with no corresponding paid order. That is what the merchant cares about,
but findings must be worded as discrepancies with possible causes, not as "your callback failed".

## Consequences

**Good:** onboarding needs no change to the merchant's shop and no favour from the provider; one decision
path; PayGuard works identically for both gateways; being down briefly delays detection rather than losing
events, because the next query still sees the truth.

**Bad:** detection latency equals the polling interval, not seconds; polling costs provider calls per run,
bounded by rate limits that are currently unknown (UNK-01, UNK-02). The question rev 1 raised as blocking
OI-01 — whether PayTR can enumerate transactions without a known `merchant_oid` — **is now resolved
favourably**: the Transaction Detail service takes a date range and no order reference
([docs](https://dev.paytr.com/en/islem-dokumu)). See ADR-014 for the discovery design that follows from it.

## Risks

The residual risk moved rather than disappearing. Discovery is documented, but its *precision* is unverified:
the discovery service has no status field and its transaction date is documented with a date-only example
(UNK-09, UNK-10). So every discovered transaction may need a second confirming call, which multiplies call
volume against rate limits that are still unknown.

## Reconsider when

A provider offers a webhook that PayGuard can subscribe to independently of the merchant's shop endpoint, with
signature verification on by default; or rate limits make the polling interval unacceptably long for the
detection speed customers need.
