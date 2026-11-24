package com.payguard.core.model;

import com.payguard.core.error.InvalidReconciliationInputException;
import java.time.Instant;
import java.util.Optional;

/**
 * One snapshot of the gateway's view of a payment, as returned by the provider's query API.
 *
 * <p>{@link #status()} is the <strong>authoritative</strong> gateway state: the decision table reads
 * it and nothing else (INV-13, ADR-016). Transaction history never adjusts it.
 *
 * <p>{@code transactionOccurredAt} is domain time — PayTR's {@code payment_date}. {@code observedAt}
 * is when PayGuard fetched the record and is <strong>provenance only</strong>: no rule may read it
 * (INV-11). That distinction is the fix for the defect where every poll reset the grace window.
 *
 * <p>Amounts are optional because an {@link PaymentStatus#ABSENT} payment has none.
 */
public record PaymentObservation(
        String gatewayReference,
        String merchantOrderRef,
        PaymentStatus status,
        Money requestedAmount,
        Money collectedAmount,
        Money refundedAmount,
        int installmentCount,
        Instant transactionOccurredAt,
        Instant observedAt,
        boolean testMode) {

    public PaymentObservation {
        // DEF-003. Previously Objects.requireNonNull, which raised a raw NPE from a public
        // constructor. FR-08 requires a typed, field-naming error instead; the checks now live
        // in validated(), which the engine calls at the reconciliation boundary.
    }

    /** Validates the parts the record's own constructor cannot express as a typed domain error. */
    public PaymentObservation validated() throws InvalidReconciliationInputException {
        requirePresent(merchantOrderRef, "payment.merchantOrderRef");
        requirePresent(status, "payment.status");
        requirePresent(observedAt, "payment.observedAt");
        if (merchantOrderRef.isBlank()) {
            throw new InvalidReconciliationInputException(
                    "payment.merchantOrderRef", "must not be blank");
        }
        if (installmentCount < 0) {
            throw new InvalidReconciliationInputException(
                    "payment.installmentCount", "must not be negative but was " + installmentCount);
        }
        return this;
    }

    public Optional<Money> requested() {
        return Optional.ofNullable(requestedAmount);
    }

    public Optional<Money> collected() {
        return Optional.ofNullable(collectedAmount);
    }

    public Optional<Money> refunded() {
        return Optional.ofNullable(refundedAmount);
    }

    public Optional<Instant> occurredAt() {
        return Optional.ofNullable(transactionOccurredAt);
    }

    /** The currency both amount fields share, where any amount is present. */
    public Optional<Currency> currency() {
        if (requestedAmount != null) {
            return Optional.of(requestedAmount.currency());
        }
        if (collectedAmount != null) {
            return Optional.of(collectedAmount.currency());
        }
        return Optional.empty();
    }

    /** A verified negative: the query succeeded and the gateway held no record. */
    public static PaymentObservation absent(String merchantOrderRef, Instant observedAt) {
        return new PaymentObservation(
                null, merchantOrderRef, PaymentStatus.ABSENT, null, null, null, 0, null,
                observedAt, false);
    }

    private static void requirePresent(Object value, String field)
            throws InvalidReconciliationInputException {
        if (value == null) {
            throw new InvalidReconciliationInputException(field, "must not be null");
        }
    }
}
