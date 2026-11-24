package com.payguard.core.result;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.spec.SpecFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * INV-08: severity is a function of the finding type alone.
 *
 * <p>This was a revision 0.2 defect, and a subtle one. The text had severity depending on the amount
 * at risk, so a refund divergence with no amount available "therefore returns LOW" — the most
 * dangerous class of discrepancy demoted precisely because PayGuard could not measure it. Missing
 * information made the alert quieter. Severity now comes from the type, and the amount is reported
 * separately as present, absent, or legitimately zero.
 */
class SeverityTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @ParameterizedTest
    @EnumSource(FindingType.class)
    @DisplayName("every finding type has a severity and an action")
    void everyTypeIsMapped(FindingType type) {
        assertDoesNotThrow(() -> FindingCatalog.severityOf(type));
        assertDoesNotThrow(() -> FindingCatalog.actionOf(type));
        assertNotNull(FindingCatalog.severityOf(type));
        assertNotNull(FindingCatalog.actionOf(type));
    }

    @Test
    @DisplayName("the mapping is stable across repeated lookups")
    void mappingIsStable() {
        for (FindingType type : FindingType.values()) {
            assertEquals(FindingCatalog.severityOf(type), FindingCatalog.severityOf(type));
        }
    }

    @Test
    @DisplayName("representative severities")
    void representativeSeverities() {
        assertEquals(Severity.INFO, FindingCatalog.severityOf(FindingType.MATCHED));
        assertEquals(Severity.INFO, FindingCatalog.severityOf(FindingType.WITHIN_GRACE_PERIOD));
        assertEquals(Severity.MEDIUM, FindingCatalog.severityOf(FindingType.INSUFFICIENT_DATA));
        assertEquals(Severity.HIGH, FindingCatalog.severityOf(FindingType.REFUND_DIVERGENCE));
        assertEquals(Severity.HIGH, FindingCatalog.severityOf(FindingType.PAYMENT_WITHOUT_ORDER));
        assertEquals(Severity.HIGH, FindingCatalog.severityOf(FindingType.DUPLICATE_PAYMENT));
    }

    @Test
    @DisplayName("the same type keeps its severity whether the amount is present, absent or zero")
    void severityIsIndependentOfTheAmount() throws Exception {
        ReconciliationFinding withAmount =
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.PAID_UNFULFILLED));
        ReconciliationFinding withoutAmount =
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.REFUNDED));

        assertEquals(FindingType.REFUND_DIVERGENCE, withAmount.type());
        assertEquals(FindingType.REFUND_DIVERGENCE, withoutAmount.type());
        assertTrue(withAmount.amountAtRisk().isPresent());
        assertTrue(withoutAmount.amountAtRisk().isEmpty());
        assertEquals(
                withAmount.severity(),
                withoutAmount.severity(),
                "an unmeasurable discrepancy must not be quieter than a measurable one");
    }

    @Test
    @DisplayName("a legitimately zero exposure does not lower severity either")
    void zeroExposureKeepsSeverity() throws Exception {
        ReconciliationFinding refundedOrphan =
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.ABSENT));
        assertEquals(FindingType.PAYMENT_WITHOUT_ORDER, refundedOrphan.type());
        assertTrue(refundedOrphan.amountAtRisk().isPresent());
        assertTrue(refundedOrphan.amountAtRisk().orElseThrow().isZero());
        assertEquals(
                Severity.HIGH,
                refundedOrphan.severity(),
                "a payment with no order is a broken integration whether or not money is still held");
    }

    @Test
    @DisplayName("no finding type recommends an automatic financial action")
    void noAutomaticFinancialAction() {
        for (RecommendedAction action : RecommendedAction.values()) {
            assertTrue(
                    action == RecommendedAction.NONE
                            || action == RecommendedAction.OBSERVE
                            || action == RecommendedAction.ALERT_MERCHANT
                            || action == RecommendedAction.MANUAL_INVESTIGATION,
                    () -> action + " is outside the four actions the architecture permits;"
                            + " PayGuard never moves money (ADR-010)");
        }
    }
}
