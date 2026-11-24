# Provider Capabilities — Verified Facts

**Why this file exists (justifying a change to the suggested structure):** the architecture depends on what
PayTR, iyzico and WooCommerce actually do. Mixing those facts into design prose makes it impossible to tell
later which statements are provider behaviour and which are our choices. This file holds only externally
verifiable provider behaviour with a citation per claim. When a provider changes its API, only this file and
the decisions that cite it need revisiting.

Every claim below is labelled:

- **[V]** verified in the provider's own documentation (link given).
- **[U]** unknown — not found in the documentation; must be confirmed with the provider or by sandbox testing.

Nothing in this file is invented. Where a capability is unknown, it is listed in §5 rather than assumed.

---

## 1. PayTR

### 1.1 Payment notification (callback)

PayTR posts the payment result to a **Notification URL configured in the merchant panel**, which is the
merchant's own endpoint.

**[V]** Fields posted to the callback URL: `merchant_oid`, `status`, `total_amount`, `hash`, `payment_type`
(`card` or `eft`), plus `failed_reason_code` and `failed_reason_msg` on failure only, and `currency` and
`payment_amount` on success only. `test_mode` is `1` in test mode.
Source: <https://dev.paytr.com/en/iframe-api/iframe-api-2-adim>

**[V]** `status` is `success` or `failed`.

**[V]** Hash verification: concatenate `merchant_oid` + `merchant_salt` + `status` + `total_amount`, compute
HMAC-SHA256 keyed with `merchant_key`, Base64-encode, compare with the posted `hash`. The documentation warns
that skipping this check risks financial loss.

**[V]** The endpoint must return the plain text `OK` with nothing before or after it. Session control must not
restrict the callback.

**[V]** Retry behaviour: if PayTR cannot connect or does not receive `OK`, it tries again after a minute. Until
`OK` is received the payment shows as "In Progress" (Devam Ediyor) in the merchant panel.

**[V]** Duplicates: because of retries the same notification can arrive more than once. Only the first
notification for an order should approve or cancel it; later ones should be acknowledged with `OK` only.
Duplicates are identified by `merchant_oid`.

### 1.2 Amounts and the instalment trap

**[V]** Amounts are sent multiplied by 100 — 34.56 is sent as `3456`.

**[V]** `merchant_oid` is the merchant's own unique order ID, set when the payment is started.

**[V]** `payment_amount` is the amount requested when the payment was started. `total_amount` is the amount
actually collected from the customer, **and it can be larger than the requested amount**, for example with
instalments or alternative payment methods.

> **This single fact changes a core business rule.** A reconciler that compares the order total against the
> amount the customer paid will raise a false AMOUNT_MISMATCH on every instalment payment. The rule must
> compare the order total against the **requested** amount, and treat an excess in the collected amount as
> expected when instalments are present. See BR-06 in `requirements.md`.

**[V]** `currency` (success only) can be `TL`, `USD`, `EUR`, `GBP` or `RUB`.

### 1.3 Status Inquiry API — the basis for read-only operation

**[V]** `POST https://www.paytr.com/odeme/durum-sorgu` queries the status of a transaction.
Source: <https://dev.paytr.com/en/durum-sorgu>

**[V]** Request: `merchant_id`, `merchant_oid`, `paytr_token`. The token is Base64 of
HMAC-SHA256(`merchant_id` + `merchant_oid` + `merchant_salt`) keyed with `merchant_key`. The samples do not
transmit `merchant_key` or `merchant_salt`, so the secrets stay out of the request body.

**[V]** Response on success includes `status`, `payment_amount` (amount for the order), `payment_total`
(amount the customer paid), `payment_date`, `currency`, `net_tutar` (amount after deductions),
`kesinti_tutari` (amount deducted), `taksit` (instalment count, 0 to 12), `kart_marka`, `masked_pan`,
`odeme_tipi` (`CART` or `EFT`), `test_mode`, and `returns` — refund information, present when the order has
refunds.

**[V]** Refunds are therefore observable through this API via `returns`. **[U]** The documentation does not
define the structure of each item in `returns`; the .NET sample prints Amount, Date, Type, DateCompleted,
AuthCode and RefNum, so those are likely but unconfirmed.

### 1.4 Transaction Detail service — the basis for **independent** payment discovery

This service is what makes it possible to find a payment that has no order at all. It was under-documented in
revision 0.1 of this architecture, which is why that revision's reconciliation sequence could not detect the
product's headline scenario. Corrected here.

**[V]** `POST https://www.paytr.com/rapor/islem-dokumu` — the **Transaction Detail** service (İşlem Dökümü).
It returns **sales and refund transactions for a date range**, with a **maximum window of 3 days**.
Source: <https://dev.paytr.com/en/islem-dokumu>

**[V]** Request fields, all required: `merchant_id`, `start_date` and `end_date` (both `YYYY-MM-DD hh:mm:ss`),
`dummy` (`0` or `1`; `1` returns simulated data for testing only), and `paytr_token`.

**[V] `merchant_oid` is not a field of this service at all** — neither required nor optional. Transactions are
retrieved by date range alone. This is the capability that revision 0.1 recorded as blocking unknown UNK-04,
and it is now verified.

**[V]** `paytr_token` is Base64 of HMAC-SHA256(`merchant_id` + `start_date` + `end_date` + `merchant_salt`)
keyed with `merchant_key`.

**[V]** Response: top-level `status` is `success`, `failed` (no transactions in the range) or `error` (detail in
`err_msg`). Each transaction carries:

| Field | Meaning | Why it matters here |
|---|---|---|
| `siparis_no` | Order number | **The join key.** Lets PayGuard ask the shop whether this order exists |
| `islem_tipi` | `S` sales, `I` returns | **The only refund indicator in this service** — there are no amount-level refund fields |
| `islem_tutari` | Transaction amount | |
| `odeme_tutari` | Payment amount, **returned only when it is above the transaction amount** | The instalment case again (see §1.2) |
| `net_tutar`, `kesinti_tutari`, `kesinti_orani` | Net after deduction, deduction amount and rate | Settlement, not payment success |
| `islem_tarihi` | Transaction date, documented with the example `13.01.2021` | **Date only in the example — no time component.** See the consequence below |
| `para_birimi` | Currency: TL, USD, EUR, GBP, RUB | |
| `taksit` | Instalment count | |
| `kart_marka`, `kart_no` | Card brand and masked number | Personal data; shortest retention |
| `odeme_tipi` | `Card` or `EFT` | |

**[V] There is no per-transaction status field in this service.** A transaction's presence, with `islem_tipi`,
is all it reports.

> **Two consequences for the architecture, both forced by the documentation rather than chosen.**
>
> **(a) Discovery cannot be the whole reconciliation.** With no status field and a date-granular
> `islem_tarihi`, this service can tell PayGuard *that* a transaction exists and *which order number* it
> claims, but not its canonical status and not a timestamp precise enough for a grace-period comparison. The
> Status Inquiry API (§1.3) returns both — `payment_date` as `YYYY-MM-DD hh:mm:ss`, plus a status and a
> `returns` block. So discovery and confirmation are **two stages against two different endpoints**, not one
> call. See ADR-014.
>
> **(b) The 3-day cap shapes scheduling and backfill.** Any window longer than three days must be chunked, and
> a first-connection backfill is a loop over consecutive windows. This interacts with FM-13.

**[V]** `dummy=1` returns simulated data. This is a genuine integration-testing affordance and the contract
tests should use it (`testing-strategy.md` §11).

**[U] Documentation quality note.** The `merchant_id` row of the token table on that page is labelled "Payment
amount", which is evidently a copy error, and the page's introduction mentions a card BIN number that appears
in neither request table. Neither affects the capability, but both are reasons to confirm this service's exact
behaviour in the sandbox before relying on field-level detail.

### 1.4b Other reporting services

**[V]** `POST https://www.paytr.com/rapor/odeme-detayi` returns payment detail for a single date
(`YYYY-MM-DD`) as JSON; `status` is `failed` when the date has no movements and `error` on a bad request, with
detail in `err_msg`.
Source: <https://dev.paytr.com/odeme-rapor-servisi/odeme-detayi>

**[V]** A payment statement service exists at `https://www.paytr.com/rapor/odeme-dokumu/`.
Source: <https://dev.paytr.com/en/odeme-rapor-servisi/odeme-ozeti>

**[U, third-party]** Third-party client libraries and API directories list a refund endpoint at
`/odeme/iade` (for example the `paytr-python` package's endpoint table at
<https://pypi.org/project/paytr-python/>). I did **not** verify this against PayTR's own documentation, so it
is labelled unverified. What *is* verified is that refunds already made are visible in the Status Inquiry
response's `returns` block (§1.3) and as `islem_tipi=I` rows in the Transaction Detail service (§1.4). Either
way PayGuard will not call a refund endpoint — see ADR-013.

---

## 2. iyzico

### 2.1 Retrieve payment

**[V]** `POST https://api.iyzipay.com/payment/detail` retrieves the result of a payment recorded in iyzico;
the response carries `paymentStatus` (for example `SUCCESS`).
Source: <https://docs.iyzico.com/en/advanced/retrieve-payment>

**[V]** Authentication uses an API key with a Base64-encoded authorization value in an
`Authorization: IYZWSv2 <value>` header.
Source: <https://docs.iyzico.com/llms-full.txt>

**[V]** `conversationId` is a correlation ID matching request and response; `paymentId` is returned on payment
creation and merchants are told to keep it in their own system because cancel requests use it.
Source: <https://docs.iyzico.com/en/payment-methods/direct-charge/non-3ds/non-3ds-implementation/create-payment>

### 2.2 Reporting

**[V]** `GET /v2/reporting/payment/details` is a Transaction Detail Reporting endpoint that returns the
current status of payments, refund results and fraud statuses.
Source: <https://docs.iyzico.com/en/advanced/reporting-service>

### 2.3 Webhooks — available but conditionally

**[V]** iyzico can notify an application by HTTP POST when a transaction completes, on both success and
failure. The first notification is sent 10 to 15 seconds after the initial payment attempt.
Source: <https://docs.iyzico.com/en/advanced/webhook>

**[V]** To receive the `X-IYZ-SIGNATURE-V3` header the webhook signature feature must be enabled on the
account, which requires contacting iyzico's integration team by email.

> **Second constraint that shapes the design.** Signature verification is not available by default on a
> merchant's account; it must be switched on per account. An architecture that depends on verified webhooks
> cannot onboard a merchant without that request being granted first. See ADR-004.

---

## 3. WooCommerce

**[V]** The REST API v3 lists orders at `GET /wp-json/wc/v3/orders`, with query parameters including `page`,
`per_page`, `status`, `after` and `before` (creation date, ISO 8601), and `modified_after` and
`modified_before`.
Sources: <https://developer.woocommerce.com/docs/apis/rest-api/v3/orders/>,
<https://developer.woocommerce.com/docs/apis/rest-api/>

**[V, same API shape]** A page size default of 10 and maximum of 100 is documented for a `wc/v3/orders`
endpoint at <https://woocommerce.com/document/customer-orders-api/>. That page documents WooCommerce.com's own
account order API rather than a merchant's store, so treat the exact cap as very likely but confirm it against
the target store's version before fixing the pagination strategy.

**[V]** Authentication is by consumer key and secret; keys can be issued with read-only permission.
Source: <https://developer.woocommerce.com/docs/apis/rest-api/>

**[V]** Default order statuses: pending payment (created, unpaid), processing (payment received, awaiting
fulfilment), on hold (stock reduced but payment needs confirming), completed, cancelled, refunded, failed, and
a draft state during block checkout.
Sources: <https://woocommerce.com/document/managing-orders/order-statuses/>,
<https://www.godaddy.com/en/help/understanding-woocommerce-order-statuses-40211>

**[V]** With delayed-notification payment methods an order can stay On hold while payment is confirmed and
then move to Failed if payment is rejected.
Source: <https://woocommerce.com/document/managing-orders/order-statuses/>

**[V]** When the Hold Stock setting is enabled, WooCommerce automatically cancels eligible pending-payment
orders after the configured time limit and releases their stock.
Source: <https://woocommerce.com/document/managing-orders/order-statuses/>

> **Third constraint.** A late callback can arrive for an order the shop has already auto-cancelled. The
> reconciler therefore needs a distinct finding for "payment succeeded against a cancelled order", which is
> operationally different from "order still unpaid". See FindingType PAYMENT_AGAINST_CANCELLED_ORDER.

**[V]** An order's `modified_after` filter exists in v3; an older API version lacked it, which historically
forced full re-scans.
Source: <https://wordpress.org/support/?p=8252909>

---

## 4. What this means for read-only operation

The question "can PayGuard monitor without write access?" resolves differently per provider:

| Provider | Read-only monitoring | Mechanism | Limitation |
|---|---|---|---|
| PayTR | **Yes, in both directions** | **Discovery:** Transaction Detail service by date range, no order reference needed (§1.4). **Confirmation:** Status Inquiry by `merchant_oid` (§1.3) | The callback goes to the *merchant's* Notification URL, a single value in the merchant panel, so PayGuard cannot receive PayTR callbacks without breaking the merchant's shop. PayGuard must **query**, not listen. Discovery is capped at 3-day windows and returns no status field, so confirmation is a second call. |
| iyzico | **Yes** | Retrieve Payment by `paymentId`, plus Transaction Detail Reporting | Webhooks are possible but signature verification needs per-account activation. |
| WooCommerce | **Yes** | Orders list with `modified_after`, read-only API keys | Pagination capped at 100 per page. |

**Architectural consequence:** PayGuard's MVP is a **bidirectional query-first reconciler**, not a webhook
receiver. Webhooks are an optional latency optimisation where a merchant has them available and verified,
never the source of truth.

"Bidirectional" is the correction this revision makes. Reconciliation must sweep from **both** ends:

- **Gateway-first**, over the Transaction Detail service's date windows, to find a captured payment whose
  `siparis_no` matches no order in the shop. Without this sweep, `PAYMENT_WITHOUT_ORDER` — the scenario the
  whole product is named for — is structurally undetectable, because a shop-first sweep can only ever examine
  orders that exist.
- **Shop-first**, over orders changed since the watermark, to find an order the shop believes is paid with no
  corresponding gateway capture.

Neither direction subsumes the other. Revision 0.1 specified only the second, which was the defect.

It remains true that PayGuard **cannot directly observe a missed callback**. It observes the *consequence* — a
gateway transaction that succeeded with no matching paid order — which is what the merchant actually cares
about. Findings are therefore worded as discrepancies with possible causes, and a missed callback is one of
several.

---

## 5. Important unknowns

| ID | Unknown | Why it matters | How to resolve |
|---|---|---|---|
| UNK-01 | Rate limits on the PayTR Status Inquiry and report APIs **[U]** | Decides polling frequency and whether per-order querying scales | Ask PayTR; measure in sandbox |
| UNK-02 | Rate limits on iyzico Retrieve Payment and reporting **[U]** | Same | Ask iyzico |
| UNK-03 | Structure of PayTR `returns` items **[U]** | Needed for refund reconciliation | Sandbox transaction with a refund |
| ~~UNK-04~~ | ~~Whether the PayTR report services can enumerate transactions without a known `merchant_oid`~~ | — | **RESOLVED, verified.** The Transaction Detail service takes a date range and no order reference, and returns `siparis_no` per transaction (§1.4). Orphan-payment discovery is documented capability |
| UNK-08 | How far back the Transaction Detail service's history extends **[U]** | Decides whether a first-connection backfill can cover a shop's existing orders, and bounds FM-13's coverage window | Sandbox: walk windows backwards until `status=failed` |
| UNK-09 | Whether `islem_tarihi` ever carries a time component, or is always date-only **[U]** | If date-only, discovery timestamps are too coarse for grace-period arithmetic and confirmation via Status Inquiry is mandatory, not an optimisation | Sandbox: inspect a real response |
| UNK-10 | Whether failed or authorised-not-captured transactions appear in the Transaction Detail service at all **[U]** | The service has no status field, so if it lists only completed sales and returns, discovery cannot see a stuck `IN_PROGRESS` transaction | Sandbox: create a failing payment and a stuck one, then query |
| UNK-11 | The exact structure of each `returns` item in the Status Inquiry response **[U]** (was UNK-03, still open) | Needed for refund **amount** reconciliation — the reason FR-07 is reduced in Scope A | Sandbox transaction with a partial refund |
| UNK-05 | Whether a merchant can issue PayTR credentials scoped to read-only **[U]** | `merchant_key` and `merchant_salt` also authorise the refund endpoint, so the same credentials that let PayGuard read also let it move money | Ask PayTR; meanwhile mitigate in software (ADR-013) |
| UNK-06 | Whether iyzico sub-merchant or marketplace accounts change the reporting shape **[U]** | Affects multi-store merchants | Ask iyzico |
| UNK-07 | Typical real frequency of paid-but-unfulfilled orders **[U]** | This is the commercial assumption, not a technical one | The data audit in the validation plan |

**UNK-04 is resolved and no longer blocking.** Independent discovery is documented capability, and the
architecture now uses it (ADR-014).

**UNK-09 and UNK-10 are the new material unknowns**, and both are about the *precision* of discovery rather
than its existence. If `islem_tarihi` is date-only (UNK-09), every discovered transaction needs a Status
Inquiry call before any time-based rule can run, which changes the call-volume arithmetic against still-unknown
rate limits. If the service omits non-completed transactions (UNK-10), discovery cannot surface a stuck
`IN_PROGRESS` payment and that detection stays shop-first only. Both are answerable in one sandbox session, and
the `dummy=1` flag makes a first pass possible without real transactions.

**Nothing in the architecture treats UNK-08 to UNK-11 as proven.** Where a design step depends on one, the step
is marked as requiring integration testing in ADR-014 and in the roadmap's Milestone 1.5.
