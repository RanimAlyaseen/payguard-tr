package com.payguard.core.model;

/**
 * Canonical gateway-side status (domain-model.md §2). Eight values; adapters translate every
 * provider vocabulary into these and nothing downstream of an adapter sees a provider's own string.
 */
public enum PaymentStatus {
    /** Money taken from the cardholder. */
    CAPTURED,
    /** Held but not captured. */
    AUTHORIZED,
    /** Attempted and declined. */
    FAILED,
    /** Fully returned. */
    REFUNDED,
    /** Partly returned. */
    PARTIALLY_REFUNDED,
    /** Gateway has not finalised — PayTR's "Devam Ediyor", reached when no OK was received. */
    IN_PROGRESS,
    /** The query succeeded and returned no record for this reference. A verified negative. */
    ABSENT,
    /**
     * A provider value we do not recognise. Deliberate: an unrecognised status must never be
     * silently read as success or failure, so it yields INSUFFICIENT_DATA (INV-07).
     */
    UNKNOWN
}
