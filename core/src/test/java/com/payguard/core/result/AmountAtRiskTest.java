package com.payguard.core.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.spec.SpecFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §4.4. {@code amountAtRisk} is an {@code Optional<Money>} with three distinguishable states:
 * present and non-zero, present and legitimately zero, and absent because it cannot be computed.
 *
 * <p>Revision 0.2 collapsed the last two into a bare zero, which told a merchant "nothing is at
 * stake" when the truth was "PayGuard cannot tell". That is the one failure mode a reconciliation
 * tool must not have, so each state gets its own test.
 */
class AmountAtRiskTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("present and non-zero: money is measurably exposed")
    void presentAndNonZero() throws Exception {
        assertEquals(
                Optional.of(SpecFixtures.try_(SpecFixtures.AMOUNT)),
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.AWAITING_PAYMENT))
                        .amountAtRisk(),
                "ORDER_UNPAID_DESPITE_PAYMENT exposes what was collected");

        assertEquals(
                Optional.of(SpecFixtures.try_(SpecFixtures.AMOUNT)),
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.ABSENT, OrderStatus.FULFILLED))
                        .amountAtRisk(),
                "ORDER_PAID_WITHOUT_PAYMENT exposes the order total");

        assertEquals(
                Optional.of(SpecFixtures.try_(SpecFixtures.AMOUNT)),
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.CANCELLED))
                        .amountAtRisk(),
                "PAYMENT_AGAINST_CANCELLED_ORDER exposes what was collected");
    }

    @Test
    @DisplayName("present and legitimately zero: a fully refunded orphan payment")
    void presentAndZero() throws Exception {
        ReconciliationFinding finding =
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.ABSENT));
        assertEquals(FindingType.PAYMENT_WITHOUT_ORDER, finding.type());
        assertTrue(finding.amountAtRisk().isPresent(), "both amounts are known, so this is computable");
        assertTrue(finding.amountAtRisk().orElseThrow().isZero());
        assertEquals(Severity.HIGH, finding.severity());
    }

    @Test
    @DisplayName("absent because an input amount is unknown")
    void absentBecauseUncomputable() throws Exception {
        ReconciliationFinding capturedOrphan =
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.ABSENT));
        assertEquals(FindingType.PAYMENT_WITHOUT_ORDER, capturedOrphan.type());
        assertTrue(
                capturedOrphan.amountAtRisk().isEmpty(),
                "§4.4 requires both amounts to be known; no refund figure means no net exposure,"
                        + " and the collected amount alone would be a different quantity reported as"
                        + " if it were this one");

        PaymentObservation withoutCollected =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED, SpecFixtures.try_(125_000),
                        null, null, 0, SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false);
        assertTrue(
                engine.reconcile(
                                SpecFixtures.input(
                                        withoutCollected, SpecFixtures.order(OrderStatus.AWAITING_PAYMENT)))
                        .amountAtRisk()
                        .isEmpty());
    }

    @Test
    @DisplayName("absent because the finding type carries no exposure")
    void absentByType() throws Exception {
        assertTrue(
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.FAILED))
                        .amountAtRisk()
                        .isEmpty(),
                "STATUS_CONFLICT states that the two sides disagree, not how much is at stake");
        assertTrue(
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.UNKNOWN, OrderStatus.FULFILLED))
                        .amountAtRisk()
                        .isEmpty());
        assertTrue(
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.FAILED, OrderStatus.AWAITING_PAYMENT))
                        .amountAtRisk()
                        .isEmpty(),
                "a matched pair has nothing at risk");
    }

    @Test
    @DisplayName("a pending or stale in-progress payment reports no exposure")
    void inProgressReportsNoExposure() throws Exception {
        Instant stale = SpecFixtures.NOW.minus(SpecFixtures.policy().staleInProgressThreshold());
        assertTrue(
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.IN_PROGRESS, stale),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, stale)))
                        .amountAtRisk()
                        .isEmpty(),
                "an unfinished attempt has taken nothing yet");

        Instant pending = SpecFixtures.NOW.minus(Duration.ofHours(1));
        assertTrue(
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.IN_PROGRESS, pending),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, pending)))
                        .amountAtRisk()
                        .isEmpty());
    }

    @Test
    @DisplayName("a stale authorisation exposes the requested amount, which may yet be lost")
    void staleAuthorizationExposesRequestedAmount() throws Exception {
        Instant stale = SpecFixtures.NOW.minus(SpecFixtures.policy().staleAuthorizationThreshold());
        assertEquals(
                Optional.of(SpecFixtures.try_(SpecFixtures.AMOUNT)),
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.AUTHORIZED, stale),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, stale)))
                        .amountAtRisk());
    }
}
