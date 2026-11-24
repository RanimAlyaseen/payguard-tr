package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.error.InvalidReconciliationInputException;
import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-04.2 boundaries. The window is half-open: an age strictly below the grace period is suppressed,
 * an age equal to it is reported.
 */
class GracePeriodTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();
    private final Duration grace = SpecFixtures.policy().gracePeriod();

    @Test
    @DisplayName("boundary: below, at, and above the grace period")
    void boundaryValues() throws Exception {
        assertEquals(
                FindingType.WITHIN_GRACE_PERIOD,
                atAge(Duration.ZERO).type(),
                "a zero-age discrepancy is the newest possible and must be suppressed");
        assertEquals(FindingType.WITHIN_GRACE_PERIOD, atAge(grace.minusMillis(1)).type());
        assertEquals(
                FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                atAge(grace).type(),
                "exactly at the grace period the window is over");
        assertEquals(FindingType.ORDER_UNPAID_DESPITE_PAYMENT, atAge(grace.plusMillis(1)).type());
    }

    @Test
    @DisplayName("the suppressed candidate and the age are both recorded")
    void suppressionIsTransparent() throws Exception {
        ReconciliationFinding finding = atAge(Duration.ofSeconds(5));
        assertTrue(finding.reasons().contains("within_grace_period"));
        assertTrue(
                finding.reasons().contains("suppressed_candidate=ORDER_UNPAID_DESPITE_PAYMENT"),
                "an operator must be able to see what was suppressed, or grace becomes a silent hole");
    }

    @Test
    @DisplayName("with no domain time on either side, grace cannot apply")
    void withoutDomainTimeGraceCannotApply() throws Exception {
        PaymentObservation noTime =
                new PaymentObservation(
                        "GW",
                        SpecFixtures.ORDER_REF,
                        PaymentStatus.CAPTURED,
                        SpecFixtures.try_(125_000),
                        SpecFixtures.try_(125_000),
                        null,
                        0,
                        null,
                        SpecFixtures.NOW,
                        false);
        OrderObservation noCreatedAt =
                new OrderObservation(
                        SpecFixtures.ORDER_REF,
                        OrderStatus.AWAITING_PAYMENT,
                        SpecFixtures.try_(125_000),
                        null,
                        null,
                        null,
                        SpecFixtures.NOW);
        assertEquals(
                FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                engine.reconcile(SpecFixtures.input(noTime, noCreatedAt)).type(),
                "falling back to a retrieval time here would reopen the defect grace was fixed for");
    }

    @Test
    @DisplayName("FR-08: domain time beyond the clock-skew tolerance is rejected, not clamped")
    void clockSkewBoundary() throws Exception {
        Duration skew = SpecFixtures.policy().clockSkewTolerance();
        assertDoesNotThrow(() -> engine.reconcile(aheadBy(skew.minusMillis(1))));
        assertDoesNotThrow(() -> engine.reconcile(aheadBy(skew)));
        assertThrows(
                InvalidReconciliationInputException.class,
                () -> engine.reconcile(aheadBy(skew.plusMillis(1))),
                "a timestamp from the future past the tolerance is bad data, and silently clamping it"
                        + " would make the discrepancy age meaningless");
    }

    private ReconciliationFinding atAge(Duration age) throws Exception {
        Instant reference = SpecFixtures.NOW.minus(age);
        return engine.reconcile(
                SpecFixtures.input(
                        SpecFixtures.payment(PaymentStatus.CAPTURED, reference),
                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, reference)));
    }

    private com.payguard.core.engine.ReconciliationInput aheadBy(Duration ahead) throws Exception {
        return SpecFixtures.input(
                SpecFixtures.payment(PaymentStatus.CAPTURED, SpecFixtures.NOW.plus(ahead)),
                SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, SpecFixtures.ONE_HOUR_AGO));
    }
}
