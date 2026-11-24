package com.payguard.core.model;

import com.payguard.core.error.CurrencyMismatchException;
import com.payguard.core.error.InvalidReconciliationInputException;
import com.payguard.core.error.MoneyOverflowException;
import java.util.Objects;

/**
 * A <strong>non-negative</strong> monetary amount, held as integer minor units plus a currency
 * (ADR-003).
 *
 * <p>Minor units are the gateway's own representation: PayTR transmits amounts multiplied by 100,
 * so 34.56 arrives as 3456. The risky decimal conversion therefore happens once in an adapter
 * rather than repeatedly in rules, and every comparison here is exact integer arithmetic. No
 * {@code double}, {@code float} or {@code BigDecimal} appears anywhere in this class (NFR-02).
 *
 * <p>There is deliberately <strong>no {@code minus()}</strong>. Subtraction can produce a value this
 * type cannot represent, and every need in the specification is met by
 * {@link #absoluteDifference(Money)} — including the one real subtraction, collected minus refunded
 * for PAYMENT_WITHOUT_ORDER's exposure, where refunded never exceeds collected.
 */
public final class Money {

    private final long minorUnits;
    private final Currency currency;

    private Money(long minorUnits, Currency currency) {
        this.minorUnits = minorUnits;
        this.currency = currency;
    }

    /**
     * @throws InvalidReconciliationInputException if the amount is negative or the currency is null
     */
    public static Money of(long minorUnits, Currency currency)
            throws InvalidReconciliationInputException {
        if (currency == null) {
            throw new InvalidReconciliationInputException("money.currency", "must not be null");
        }
        if (minorUnits < 0) {
            throw new InvalidReconciliationInputException(
                    "money.minorUnits", "must not be negative but was " + minorUnits);
        }
        return new Money(minorUnits, currency);
    }

    /** Convenience for the common case. */
    public static Money ofTry(long minorUnits) throws InvalidReconciliationInputException {
        return of(minorUnits, Currency.TRY);
    }

    public static Money zero(Currency currency) throws InvalidReconciliationInputException {
        return of(0L, currency);
    }

    public long minorUnits() {
        return minorUnits;
    }

    public Currency currency() {
        return currency;
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isSameCurrency(Money other) {
        return other != null && currency == other.currency;
    }

    /**
     * Addition with checked overflow.
     *
     * @throws CurrencyMismatchException if the currencies differ (INV-04)
     * @throws MoneyOverflowException rather than wrapping silently (FR-08)
     */
    public Money plus(Money other) throws CurrencyMismatchException, MoneyOverflowException {
        requireSameCurrency(other);
        try {
            return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
        } catch (ArithmeticException overflow) {
            throw new MoneyOverflowException(minorUnits, other.minorUnits);
        }
    }

    /**
     * The absolute difference, which is always non-negative and so can never leave this type's
     * domain. For two non-negative longs the result is at most {@code Long.MAX_VALUE}, so it cannot
     * overflow either — which is why this method declares no overflow exception.
     */
    public Money absoluteDifference(Money other) throws CurrencyMismatchException {
        requireSameCurrency(other);
        long diff =
                minorUnits >= other.minorUnits
                        ? minorUnits - other.minorUnits
                        : other.minorUnits - minorUnits;
        return new Money(diff, currency);
    }

    public boolean isGreaterThan(Money other) throws CurrencyMismatchException {
        requireSameCurrency(other);
        return minorUnits > other.minorUnits;
    }

    public boolean isLessThan(Money other) throws CurrencyMismatchException {
        requireSameCurrency(other);
        return minorUnits < other.minorUnits;
    }

    private void requireSameCurrency(Money other) throws CurrencyMismatchException {
        if (other == null) {
            throw new CurrencyMismatchException(currency, null);
        }
        if (currency != other.currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return minorUnits == other.minorUnits && currency == other.currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(minorUnits, currency);
    }

    /** Minor units are shown as-is; this type never formats a decimal. */
    @Override
    public String toString() {
        return minorUnits + " " + currency + "(minor)";
    }
}
