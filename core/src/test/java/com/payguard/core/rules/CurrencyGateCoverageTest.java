package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.Money;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.spec.SpecFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-03 across every monetary field (DEF-005).
 *
 * <p>{@code CurrencyRuleTest} covers the rule's ordinary behaviour. This class exists because that
 * one did not: it compared a single currency per side, which is what the implementation did, so a
 * payment whose requested and collected amounts disagreed slipped through the gate and failed later
 * inside the amount rule. The gate now examines all four amounts, and these are the combinations
 * that distinguish the two designs.
 */
class CurrencyGateCoverageTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("requested and collected in different currencies is caught by the gate")
    void requestedVersusCollected() throws Exception {
        assertEquals(
                FindingType.CURRENCY_MISMATCH,
                evaluate(SpecFixtures.try_(125_000), SpecFixtures.eur(125_000), null));
    }

    @Test
    @DisplayName("a refund in a third currency is caught")
    void refundedInAnotherCurrency() throws Exception {
        assertEquals(
                FindingType.CURRENCY_MISMATCH,
                evaluate(SpecFixtures.try_(125_000), SpecFixtures.try_(125_000), SpecFixtures.eur(1_000)));
    }

    @Test
    @DisplayName("no arithmetic exception escapes a cross-currency input")
    void noArithmeticExceptionEscapes() {
        assertDoesNotThrow(
                () -> evaluate(SpecFixtures.try_(125_000), SpecFixtures.eur(125_000), null),
                "FR-08 forbids an untyped failure leaving the module in place of a finding");
    }

    @Test
    @DisplayName("control: four amounts in one currency is not a mismatch")
    void sameCurrencyIsNotAMismatch() throws Exception {
        assertEquals(
                FindingType.MATCHED,
                evaluate(
                        SpecFixtures.try_(SpecFixtures.AMOUNT),
                        SpecFixtures.try_(SpecFixtures.AMOUNT),
                        null));
    }

    private FindingType evaluate(Money requested, Money collected, Money refunded) throws Exception {
        return engine.reconcile(
                        SpecFixtures.input(
                                new PaymentObservation(
                                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED, requested,
                                        collected, refunded, 0, SpecFixtures.ONE_HOUR_AGO,
                                        SpecFixtures.NOW, false),
                                SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                .type();
    }
}
