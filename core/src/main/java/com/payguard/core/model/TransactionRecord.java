package com.payguard.core.model;

import com.payguard.core.error.InvalidReconciliationInputException;
import java.time.Instant;

/**
 * One row of the gateway's own transaction history — for PayTR, a row from the Transaction Detail
 * service for a given {@code siparis_no}.
 *
 * <p><strong>This is not a webhook notification.</strong> Under the query-first architecture PayGuard
 * does not receive PayTR's callback at all (it goes to the merchant's own Notification URL), and
 * iyzico's webhook is a run trigger that never enters the core (ADR-004, ADR-016). The old name
 * {@code PaymentEvent} left that open, which is how history came to be treated as a source of
 * truth; the rename is the fix.
 *
 * <p>{@code recordIdentity} is an <strong>opaque, provider-specific</strong> string the adapter
 * computes: a provider row identifier where one exists, otherwise a content digest over the row's
 * significant fields. The core never derives it (FR-05.1, ADR-015).
 *
 * <p>{@code retrievalSequence} is audit-only and read by no rule (INV-11). Overlapping discovery
 * windows re-retrieve the same row by design, so retrieval order carries no domain meaning.
 */
public record TransactionRecord(
        String recordIdentity,
        String merchantOrderRef,
        RecordKind kind,
        Money amount,
        Instant occurredAt,
        long retrievalSequence) {

    public TransactionRecord {
        // DEF-003. Previously Objects.requireNonNull, which raised a raw NPE from a public
        // constructor. FR-08 requires a typed, field-naming error instead; the checks now live
        // in validated(), which the engine calls at the reconciliation boundary.
    }

    public TransactionRecord validated() throws InvalidReconciliationInputException {
        requirePresent(recordIdentity, "history.recordIdentity");
        requirePresent(merchantOrderRef, "history.merchantOrderRef");
        requirePresent(kind, "history.kind");
        requirePresent(occurredAt, "history.occurredAt");
        if (recordIdentity.isBlank()) {
            throw new InvalidReconciliationInputException(
                    "history.recordIdentity", "must not be blank");
        }
        return this;
    }

    public boolean isCapture() {
        return kind == RecordKind.CAPTURE;
    }

    /**
     * The fields that make two rows the same event. Used only to detect a provider reusing one
     * identity across differing content (FR-05.3); it is never used to derive an identity.
     */
    public String significantContent() {
        return merchantOrderRef
                + "|"
                + kind
                + "|"
                + (amount == null ? "-" : amount.minorUnits() + ":" + amount.currency())
                + "|"
                + occurredAt;
    }

    private static void requirePresent(Object value, String field)
            throws InvalidReconciliationInputException {
        if (value == null) {
            throw new InvalidReconciliationInputException(field, "must not be null");
        }
    }
}
