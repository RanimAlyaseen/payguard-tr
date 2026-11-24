package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.Currency;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.spec.SpecFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-03. A currency mismatch is reported, never resolved: PayGuard holds no exchange rates and
 * inventing one would turn an alert into a wrong number.
 *
 * <p>The negative cases matter as much as the positive one. A rule that compares currencies must
 * not blow up when one side has no amount at all, which is the normal situation for an absent
 * payment or a failed one.
 */
class CurrencyRuleTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("different currencies on the two sides are reported")
    void differentCurrenciesAreReported() throws Exception {
        PaymentObservation euroPayment =
                new PaymentObservation(
                        "GW",
                        SpecFixtures.ORDER_REF,
                        PaymentStatus.CAPTURED,
                        SpecFixtures.eur(125_000),
                        SpecFixtures.eur(125_000),
                        null,
                        0,
                        SpecFixtures.ONE_HOUR_AGO,
                        SpecFixtures.NOW,
                        false);
        assertEquals(
                FindingType.CURRENCY_MISMATCH,
                engine.reconcile(
                                SpecFixtures.input(
                                        euroPayment, SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                        .type());
    }

    @Test
    @DisplayName("an absent payment is not a currency mismatch")
    void absentPaymentIsNotAMismatch() throws Exception {
        assertEquals(
                FindingType.MATCHED,
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.ABSENT),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT)))
                        .type());
    }

    @Test
    @DisplayName("a present payment carrying no amount is skipped, not flagged and not an error")
    void missingAmountsAreSkipped() throws Exception {
        PaymentObservation withoutAmounts =
                new PaymentObservation(
                        "GW",
                        SpecFixtures.ORDER_REF,
                        PaymentStatus.FAILED,
                        null,
                        null,
                        null,
                        0,
                        SpecFixtures.ONE_HOUR_AGO,
                        SpecFixtures.NOW,
                        false);
        assertDoesNotThrow(
                () -> engine.reconcile(
                        SpecFixtures.input(
                                withoutAmounts, SpecFixtures.order(OrderStatus.AWAITING_PAYMENT))));
        assertEquals(
                FindingType.MATCHED,
                engine.reconcile(
                                SpecFixtures.input(
                                        withoutAmounts, SpecFixtures.order(OrderStatus.AWAITING_PAYMENT)))
                        .type());
    }

    @Test
    @DisplayName("every supported currency uses two minor-unit digits")
    void everyCurrencyHasAKnownExponent() {
        for (Currency currency : Currency.values()) {
            assertEquals(2, currency.minorUnitExponent(), () -> currency + " exponent");
        }
    }
}
