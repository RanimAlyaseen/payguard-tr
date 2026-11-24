package com.payguard.core.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.error.CurrencyMismatchException;
import com.payguard.core.error.InvalidReconciliationInputException;
import com.payguard.core.error.MoneyOverflowException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** NFR-02, INV-04, ADR-003. Integer minor units, currency-safe arithmetic, no subtraction. */
class MoneyTest {

    @Nested
    @DisplayName("construction (equivalence partitioning and boundary values)")
    class Construction {

        @ParameterizedTest
        @ValueSource(longs = {0L, 1L, 99L, 100L, 125_000L, Long.MAX_VALUE})
        @DisplayName("the valid partition is zero to Long.MAX_VALUE inclusive")
        void validAmountsAreAccepted(long minorUnits) throws Exception {
            assertEquals(minorUnits, Money.ofTry(minorUnits).minorUnits());
        }

        @ParameterizedTest
        @ValueSource(longs = {-1L, -100L, Long.MIN_VALUE})
        @DisplayName("the invalid partition is everything below zero")
        void negativeAmountsAreRejected(long minorUnits) {
            InvalidReconciliationInputException thrown =
                    assertThrows(
                            InvalidReconciliationInputException.class, () -> Money.ofTry(minorUnits));
            assertEquals("money.minorUnits", thrown.field(), "the rejected field must be named");
        }

        @Test
        @DisplayName("a null currency is rejected")
        void nullCurrencyIsRejected() {
            assertThrows(InvalidReconciliationInputException.class, () -> Money.of(1L, null));
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("addition raises on overflow rather than wrapping (FM-09)")
        void additionOverflowRaises() throws Exception {
            Money max = Money.ofTry(Long.MAX_VALUE);
            assertThrows(MoneyOverflowException.class, () -> max.plus(Money.ofTry(1L)));
            assertDoesNotThrow(() -> max.plus(Money.ofTry(0L)));
        }

        @Test
        @DisplayName("absoluteDifference is total over the valid domain and cannot overflow")
        void absoluteDifferenceNeverOverflows() throws Exception {
            assertEquals(
                    Long.MAX_VALUE,
                    Money.ofTry(0L).absoluteDifference(Money.ofTry(Long.MAX_VALUE)).minorUnits());
            assertEquals(
                    Long.MAX_VALUE,
                    Money.ofTry(Long.MAX_VALUE).absoluteDifference(Money.ofTry(0L)).minorUnits());
        }

        @Test
        @DisplayName("absoluteDifference is symmetric")
        void absoluteDifferenceIsSymmetric() throws Exception {
            Money a = Money.ofTry(125_000L);
            Money b = Money.ofTry(131_250L);
            assertEquals(a.absoluteDifference(b), b.absoluteDifference(a));
        }

        @Test
        @DisplayName("INV-04: cross-currency arithmetic raises instead of silently converting")
        void crossCurrencyArithmeticRaises() throws Exception {
            Money lira = Money.ofTry(100L);
            Money euro = Money.of(100L, Currency.EUR);
            assertThrows(CurrencyMismatchException.class, () -> lira.plus(euro));
            assertThrows(CurrencyMismatchException.class, () -> lira.absoluteDifference(euro));
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equality covers amount and currency")
        void equalityCoversAmountAndCurrency() throws Exception {
            assertEquals(Money.ofTry(100L), Money.ofTry(100L));
            assertEquals(Money.ofTry(100L).hashCode(), Money.ofTry(100L).hashCode());
            assertNotEquals(Money.ofTry(100L), Money.of(100L, Currency.EUR));
            assertNotEquals(Money.ofTry(100L), Money.ofTry(101L));
        }

        @Test
        @DisplayName("comparison helpers")
        void comparisonHelpers() throws Exception {
            assertTrue(Money.ofTry(101L).isGreaterThan(Money.ofTry(100L)));
            assertFalse(Money.ofTry(100L).isGreaterThan(Money.ofTry(100L)));
            assertTrue(Money.ofTry(99L).isLessThan(Money.ofTry(100L)));
            assertTrue(Money.ofTry(0L).isZero());
            assertFalse(Money.ofTry(1L).isZero());
        }
    }

    @Nested
    @DisplayName("structural guarantees")
    class Structure {

        @Test
        @DisplayName("ADR-003: Money.minus() must not exist")
        void minusDoesNotExist() {
            for (Method method : Money.class.getDeclaredMethods()) {
                assertNotEquals(
                        "minus",
                        method.getName(),
                        "Money.minus() reappeared. Signed money has no meaning in this domain and a"
                                + " negative result would be unrepresentable; use absoluteDifference.");
            }
        }

        @Test
        @DisplayName("NFR-02: no floating point anywhere in the money API")
        void noFloatingPointInTheApi() {
            for (Method method : Money.class.getDeclaredMethods()) {
                assertFalse(
                        method.getReturnType() == double.class || method.getReturnType() == float.class,
                        () -> method.getName() + " returns a floating-point type");
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertFalse(
                            parameter == double.class || parameter == float.class,
                            () -> method.getName() + " takes a floating-point parameter");
                }
            }
        }
    }
}
