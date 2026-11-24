package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.engine.ReconciliationInput;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * BR-09, as it applies to Scope A.
 *
 * <p>BR-09 states that a test-mode transaction never produces a <em>merchant-facing</em> finding and
 * that it is recorded and tagged. The qualifier matters. Scope A is a classification core with no
 * publication layer: INV-01 requires it to return exactly one finding for every valid input, so it
 * has no way to produce nothing, and suppressing a verdict here would mean returning a wrong
 * classification rather than withholding a notification.
 *
 * <p>The division is therefore: the core classifies identically whether or not the transaction is in
 * test mode, {@code PaymentObservation.testMode} carries the tag BR-09 requires, and the decision
 * about what reaches a merchant belongs to the application layer that consumes findings — which is
 * Scope B. This test pins the first half of that, so a future change cannot quietly make
 * classification depend on the flag.
 *
 * <p>This matters in live data, not just in a sandbox: PayTR sets {@code test_mode=1} both in test
 * mode and when a test transaction is run against a live account, so production data contains
 * test-mode rows and a core that treated them differently would mis-reconcile real merchants.
 */
class TestModeNeutralityTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @ParameterizedTest(name = "{0} + {1} classifies identically in test mode")
    @CsvSource({
        "CAPTURED, AWAITING_PAYMENT",
        "CAPTURED, PAID_UNFULFILLED",
        "CAPTURED, ABSENT",
        "CAPTURED, CANCELLED",
        "AUTHORIZED, AWAITING_PAYMENT",
        "REFUNDED, PAID_UNFULFILLED",
        "FAILED, AWAITING_PAYMENT",
        "ABSENT, FULFILLED"
    })
    @DisplayName("toggling testMode alone changes nothing about the verdict")
    void testModeDoesNotAffectClassification(PaymentStatus payment, OrderStatus order)
            throws Exception {
        ReconciliationFinding live = engine.reconcile(scenario(payment, order, false));
        ReconciliationFinding test = engine.reconcile(scenario(payment, order, true));

        assertEquals(live.type(), test.type(), "finding type");
        assertEquals(live.severity(), test.severity(), "severity");
        assertEquals(live.recommendedAction(), test.recommendedAction(), "recommended action");
        assertEquals(live.amountAtRisk(), test.amountAtRisk(), "amount at risk");
        assertEquals(live.reasons(), test.reasons(), "reasons");
    }

    private ReconciliationInput scenario(PaymentStatus status, OrderStatus order, boolean testMode)
            throws Exception {
        PaymentObservation payment =
                new PaymentObservation(
                        "GW",
                        SpecFixtures.ORDER_REF,
                        status,
                        SpecFixtures.try_(SpecFixtures.AMOUNT),
                        SpecFixtures.try_(SpecFixtures.AMOUNT),
                        status == PaymentStatus.REFUNDED ? SpecFixtures.try_(SpecFixtures.AMOUNT) : null,
                        0,
                        SpecFixtures.ONE_HOUR_AGO,
                        SpecFixtures.NOW,
                        testMode);
        return SpecFixtures.input(payment, SpecFixtures.order(order));
    }
}
