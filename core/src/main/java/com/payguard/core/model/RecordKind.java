package com.payguard.core.model;

/**
 * What a transaction-history row represents.
 *
 * <p>Maps from PayTR's {@code islem_tipi}, whose documented values are {@code S} for sales and
 * {@code I} for returns — the only refund indicator that service provides.
 */
public enum RecordKind {
    /** A sale. PayTR {@code islem_tipi=S}. */
    CAPTURE,
    /** A return. PayTR {@code islem_tipi=I}. */
    REFUND,
    /** Anything else a provider may report. */
    OTHER
}
