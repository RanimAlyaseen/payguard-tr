package com.payguard.core.model;

/**
 * Canonical shop-side status (domain-model.md §2). Ten values.
 *
 * <p>{@link #UNKNOWN} exists because WooCommerce order statuses are extensible and plugins
 * routinely add their own, so an unrecognised shop status is expected rather than exceptional.
 */
public enum OrderStatus {
    /** The query succeeded and found no order for this reference. */
    ABSENT,
    /** A shop status we do not recognise, including a plugin-defined custom status. */
    UNKNOWN,
    /** Checkout started; not yet a real order. */
    DRAFT,
    /** Created, unpaid. */
    AWAITING_PAYMENT,
    /** Stock reduced, payment not confirmed. */
    PAYMENT_UNCONFIRMED,
    /** Paid, not yet shipped. */
    PAID_UNFULFILLED,
    /** Paid and complete. */
    FULFILLED,
    /** Cancelled by admin, customer, or stock-hold expiry. */
    CANCELLED,
    /** Refunded in the shop. */
    REFUNDED,
    /** Payment attempt failed. */
    FAILED
}
