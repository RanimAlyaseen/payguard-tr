package com.payguard.core.model;

/**
 * Currencies PayTR documents for its callback and status-inquiry responses.
 *
 * <p>The minor-unit exponent is fixed at 2 for all five. A currency with another exponent would
 * read it from here rather than from an assumption elsewhere (ADR-003).
 */
public enum Currency {
    TRY(2),
    USD(2),
    EUR(2),
    GBP(2),
    RUB(2);

    private final int minorUnitExponent;

    Currency(int minorUnitExponent) {
        this.minorUnitExponent = minorUnitExponent;
    }

    public int minorUnitExponent() {
        return minorUnitExponent;
    }
}
