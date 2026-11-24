package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.result.Severity;
import com.payguard.core.spec.SpecFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-07, as revised in 0.2. One finding type with a recorded direction, rather than three types.
 *
 * <p>The amount behaviour here is the one defect of revision 0.2 that mattered most: the original
 * text said the exposure was the gateway's refunded amount "else zero", which reported a confident
 * zero for the shop-only direction, where PayGuard has no refund figure at all. A zero exposure
 * reads as "nothing at stake"; absent reads as "cannot tell". Severity is unaffected either way,
 * because INV-08 fixes it from the finding type alone.
 */
class RefundDivergenceTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("gateway refunded, shop did not: direction recorded, amount present")
    void gatewayOnly() throws Exception {
        ReconciliationFinding finding =
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.PAID_UNFULFILLED));
        assertEquals(FindingType.REFUND_DIVERGENCE, finding.type());
        assertTrue(finding.reasons().contains("refund_direction=gateway_only"));
        assertTrue(finding.amountAtRisk().isPresent(), "the gateway's refunded amount is known here");
    }

    @Test
    @DisplayName("shop refunded, gateway did not: direction recorded, amount absent")
    void shopOnly() throws Exception {
        ReconciliationFinding finding =
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.REFUNDED));
        assertEquals(FindingType.REFUND_DIVERGENCE, finding.type());
        assertTrue(finding.reasons().contains("refund_direction=shop_only"));
        assertFalse(
                finding.amountAtRisk().isPresent(),
                "there is no refund figure on this side, and zero would be a false reassurance");
        assertEquals(Severity.HIGH, finding.severity(), "INV-08: severity comes from the type alone");
    }

    @Test
    @DisplayName("partial against full: direction recorded, amount absent in Scope A")
    void partialAgainstFull() throws Exception {
        ReconciliationFinding finding =
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED));
        assertEquals(FindingType.REFUND_DIVERGENCE, finding.type());
        assertTrue(finding.reasons().contains("refund_direction=partial_vs_full"));
        assertFalse(
                finding.amountAtRisk().isPresent(),
                "Scope A has no shop-side refund figure to compare against");
        assertEquals(Severity.HIGH, finding.severity());
    }

    @Test
    @DisplayName("both sides refunded is agreement, not divergence")
    void bothRefundedIsAgreement() throws Exception {
        assertEquals(
                FindingType.MATCHED,
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.REFUNDED)).type());
    }
}
