package com.payguard.core.error;

import com.payguard.core.model.Currency;

/**
 * Cross-currency arithmetic was attempted (INV-04).
 *
 * <p>This must never escape {@code reconcile} for a valid input: the currency gate at priority 3
 * runs before any amount comparison, so by the time arithmetic happens the currencies agree. If it
 * ever does escape, the gate has a hole.
 */
public final class CurrencyMismatchException extends PayGuardCoreException {
    private static final long serialVersionUID = 1L;

    public CurrencyMismatchException(Currency left, Currency right) {
        super("cross-currency arithmetic refused: " + left + " vs " + right);
    }
}
