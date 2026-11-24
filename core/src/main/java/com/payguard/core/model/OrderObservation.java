package com.payguard.core.model;

import com.payguard.core.error.InvalidReconciliationInputException;
import java.time.Instant;
import java.util.Optional;

/**
 * One snapshot of the shop's view of an order.
 *
 * <p>{@code businessStateChangedAt} is domain time. <strong>In Scope A it is the order's creation
 * time and nothing else</strong> (BR-07b), which is what makes the grace window monotonic without a
 * ceiling: it never advances, so {@code discrepancyAge} is strictly increasing in
 * {@code evaluatedAt} (INV-12). Use {@link #of} to get that guarantee.
 *
 * <p>{@code lastModifiedAt} is kept for watermarking and diagnostics and is deliberately
 * <strong>not</strong> a business-state time: WooCommerce's {@code date_modified} advances on any
 * edit, including a staff note, so using it would reintroduce a restartable window.
 */
public record OrderObservation(
        String merchantOrderRef,
        OrderStatus status,
        Money orderTotal,
        Instant createdAt,
        Instant businessStateChangedAt,
        Instant lastModifiedAt,
        Instant observedAt) {

    public OrderObservation {
        // DEF-003. Previously Objects.requireNonNull, which raised a raw NPE from a public
        // constructor. FR-08 requires a typed, field-naming error instead; the checks now live
        // in validated(), which the engine calls at the reconciliation boundary.
    }

    /** The Scope A factory: {@code businessStateChangedAt} is forced to {@code createdAt} (BR-07b). */
    public static OrderObservation of(
            String merchantOrderRef,
            OrderStatus status,
            Money orderTotal,
            Instant createdAt,
            Instant lastModifiedAt,
            Instant observedAt) {
        return new OrderObservation(
                merchantOrderRef, status, orderTotal, createdAt, createdAt, lastModifiedAt,
                observedAt);
    }

    /** The query succeeded and found no order for this reference. */
    public static OrderObservation absent(String merchantOrderRef, Instant observedAt) {
        return new OrderObservation(
                merchantOrderRef, OrderStatus.ABSENT, null, null, null, null, observedAt);
    }

    public OrderObservation validated() throws InvalidReconciliationInputException {
        requirePresent(merchantOrderRef, "order.merchantOrderRef");
        requirePresent(status, "order.status");
        requirePresent(observedAt, "order.observedAt");
        if (merchantOrderRef.isBlank()) {
            throw new InvalidReconciliationInputException(
                    "order.merchantOrderRef", "must not be blank");
        }
        // DEF-004. BR-07b fixes businessStateChangedAt to the order creation time in Scope A,
        // because WooCommerce exposes no per-transition timestamp. The static factory enforced it,
        // but the record's canonical constructor is public, so a caller could pass any value and
        // the engine accepted it. That is not cosmetic: INV-12 and the removal of the grace ceiling
        // both rest on the discrepancy age being anchored to a time the caller cannot move. A
        // caller able to advance businessStateChangedAt could re-enter the grace window at will and
        // suppress a real discrepancy indefinitely - precisely the defect the ceiling once existed
        // to prevent. The invariant is now enforced where the engine will see it.
        if (createdAt != null && !createdAt.equals(businessStateChangedAt)) {
            throw new InvalidReconciliationInputException(
                    "order.businessStateChangedAt",
                    "must equal order.createdAt in Scope A (BR-07b) but was "
                            + businessStateChangedAt
                            + " against a creation time of "
                            + createdAt);
        }
        return this;
    }

    public Optional<Money> total() {
        return Optional.ofNullable(orderTotal);
    }

    public Optional<Instant> businessStateChange() {
        return Optional.ofNullable(businessStateChangedAt);
    }

    public Optional<Currency> currency() {
        return orderTotal == null ? Optional.empty() : Optional.of(orderTotal.currency());
    }

    private static void requirePresent(Object value, String field)
            throws InvalidReconciliationInputException {
        if (value == null) {
            throw new InvalidReconciliationInputException(field, "must not be null");
        }
    }
}
