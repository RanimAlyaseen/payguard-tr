package com.payguard.core.error;

/**
 * A money operation exceeded the range of {@code long} minor units (FR-08).
 *
 * <p>Detected with {@link Math#addExact}, so the alternative would have been a silently wrapped
 * value. In a money type that is indefensible, which is why this is an exception and not a clamp.
 */
public final class MoneyOverflowException extends PayGuardCoreException {
    private static final long serialVersionUID = 1L;

    public MoneyOverflowException(long left, long right) {
        super("money overflow: " + left + " + " + right + " exceeds long minor units");
    }
}
