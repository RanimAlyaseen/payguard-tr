package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.RecommendedAction;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.result.Severity;
import com.payguard.core.spec.SpecFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * FR-01 and the reason {@code UNKNOWN} exists in both vocabularies.
 *
 * <p>WooCommerce order statuses are extensible and plugins add their own, so an unrecognised status
 * is a routine event rather than a bug. Guessing what it means would be the dangerous choice: a
 * plugin status that happens to mean "paid" and one that means "fraud hold" are indistinguishable
 * from the outside, so PayGuard reports that it cannot tell and asks for a human.
 */
class UnknownStatusTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("an unrecognised payment status yields INSUFFICIENT_DATA against every order status")
    void unknownPaymentStatus(OrderStatus order) throws Exception {
        assertEquals(
                FindingType.INSUFFICIENT_DATA,
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.UNKNOWN, order)).type());
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    @DisplayName("an unrecognised order status yields INSUFFICIENT_DATA against every payment status")
    void unknownOrderStatus(PaymentStatus payment) throws Exception {
        assertEquals(
                FindingType.INSUFFICIENT_DATA,
                engine.reconcile(SpecFixtures.tableCase(payment, OrderStatus.UNKNOWN)).type());
    }

    @Test
    @DisplayName("the finding routes to a human and says why")
    void theFindingRoutesToAHuman() throws Exception {
        ReconciliationFinding finding =
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.UNKNOWN, OrderStatus.FULFILLED));
        assertTrue(finding.reasons().contains("unrecognised_status"));
        assertEquals(Severity.MEDIUM, finding.severity());
        assertEquals(RecommendedAction.MANUAL_INVESTIGATION, finding.recommendedAction());
        assertTrue(
                finding.amountAtRisk().isEmpty(),
                "without knowing what the status means, no exposure can be claimed");
    }
}
