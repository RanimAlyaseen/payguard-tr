# ADR-010 — Ports owned by the application, adapters implement them

**Status:** Accepted · **Date:** 22 Nov 2026

## Context

Two gateways and one platform are in view now, with more later. Their models differ substantially: PayTR keys
on the merchant's own `merchant_oid` and sends amounts as integers ×100 with statuses `success`/`failed`
([docs](https://dev.paytr.com/en/durum-sorgu)); iyzico keys on its own `paymentId` with a `conversationId`
correlation and statuses like `SUCCESS` ([docs](https://docs.iyzico.com/en/advanced/retrieve-payment));
WooCommerce returns decimal strings and statuses like `pending` and `on-hold`
([docs](https://woocommerce.com/document/managing-orders/order-statuses/)).

## Decision

The application layer declares `GatewayPort` and `ShopPort` interfaces in terms of the **canonical** types
from `payguard-core`. Adapters implement them. Each adapter owns: HTTP, authentication (PayTR's HMAC token,
iyzico's `IYZWSv2` header), pagination, retry, rate limiting, signature verification, and translation from the
provider's vocabulary and amount format into the canonical one. No provider string or provider amount format
exists above the adapter.

Dependencies point inward: adapters depend on the core's types; the core depends on nothing.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Adapters define their own interfaces, the application adapts to each | Inverts the dependency. Adding a gateway would then change application code, which is exactly what NFR-17 forbids |
| A single generic "provider" interface covering gateways and shops | They answer different questions — "was money captured?" versus "does the shop consider this paid?". One interface would be a union of two unrelated shapes, with half its methods unimplemented in each adapter |
| Pass provider payloads through to the rules and translate there | Puts provider knowledge in the rules, so every new gateway edits the decision logic. This is the mistake the whole canonical vocabulary exists to prevent |
| An off-the-shelf integration platform between PayGuard and providers | Another dependency and another failure mode, for logic that is a few hundred lines per provider |

## Reasons

The translation boundary is where provider-specific risk should be concentrated and tested. Two of the three
most dangerous facts in this system live in adapters: PayTR's amounts being ×100, and its collected total
legitimately exceeding the requested amount on instalments. Both are mapping concerns. Keeping them in one
small, heavily tested class per provider means a provider change has one place to break.

It also makes the canonical vocabulary load-bearing rather than decorative. `PaymentStatus.UNKNOWN` exists so
an adapter can honestly say "the provider sent something I do not recognise" instead of guessing, which is
what INV-07 then turns into a visible `INSUFFICIENT_DATA` finding.

## Consequences

**Good:** a new gateway is one class plus its tests, with no change above the adapter (NFR-17); adapters can be
tested against recorded provider responses as contract tests, with no live credentials; the core stays
dependency-free, so ADR-002 holds.

**Bad:** a mapping layer per provider is code to write and maintain; a genuinely provider-specific concept
with no canonical equivalent (iyzico's fraud status, say) either gets forced into the canonical model or is
kept only in `raw_payload`, and neither is satisfying.

## Risks

A provider changes its response shape silently and the adapter maps it wrongly rather than failing. Mitigation:
adapters reject unrecognised status values into `UNKNOWN` rather than defaulting to a success or failure, and
contract tests run against recorded fixtures so a shape change surfaces as a test failure.

## Reconsider when

A provider's model cannot be expressed in the canonical vocabulary without distortion. The answer then is to
extend the canonical model deliberately, with a new enum value and new decision-table rows, rather than to
leak the provider's vocabulary upward.
