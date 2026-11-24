package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.RecordKind;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-06 and INV-13: the queried status is authoritative; history is evidence and can only
 * contradict, never override.
 */
class AuthoritativeStateTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("a FAILED status alongside a capture record is a contradiction, not a capture")
    void failedStatusWithCaptureRecordIsInsufficientData() throws Exception {
        ReconciliationFinding finding =
                engine.reconcile(
                        SpecFixtures.input(
                                SpecFixtures.payment(PaymentStatus.FAILED),
                                SpecFixtures.order(OrderStatus.AWAITING_PAYMENT),
                                List.of(SpecFixtures.capture("r1", 125_000))));
        assertEquals(FindingType.INSUFFICIENT_DATA, finding.type());
        assertTrue(
                finding.reasons().contains("gateway_history_contradicts_queried_state"),
                "the contradiction must be named");
    }

    @Test
    @DisplayName("an ABSENT status alongside a capture record is also a contradiction")
    void absentStatusWithCaptureRecordIsInsufficientData() throws Exception {
        assertEquals(
                FindingType.INSUFFICIENT_DATA,
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.ABSENT),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT),
                                        List.of(SpecFixtures.capture("r1", 125_000))))
                        .type());
    }

    @Test
    @DisplayName("empty history still yields the ordinary table verdict")
    void emptyHistoryStillGivesTheTableVerdict() throws Exception {
        assertEquals(
                FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT)))
                        .type());
    }

    @Test
    @DisplayName("history richer than the status but not contradicting it does not trigger the gate")
    void nonContradictingHistoryIsNotAGateTrigger() throws Exception {
        TransactionRecord refundRow =
                SpecFixtures.record("f1", RecordKind.REFUND, 62_500, SpecFixtures.ONE_HOUR_AGO, 1L);
        assertEquals(
                FindingType.REFUND_DIVERGENCE,
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.PARTIALLY_REFUNDED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        List.of(refundRow)))
                        .type());
    }

    @Test
    @DisplayName("INV-13: for a fixed status, varying non-contradictory history cannot change the cell")
    void historyCannotChangeTheSelectedCell() throws Exception {
        TransactionRecord refundRow =
                SpecFixtures.record("f1", RecordKind.REFUND, 1_000, SpecFixtures.ONE_HOUR_AGO, 1L);
        List<List<TransactionRecord>> histories =
                List.of(
                        List.of(),
                        List.of(SpecFixtures.capture("c1", 125_000)),
                        List.of(refundRow),
                        List.of(SpecFixtures.capture("c1", 125_000), refundRow));

        for (List<TransactionRecord> history : histories) {
            assertEquals(
                    FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                    engine.reconcile(
                                    SpecFixtures.input(
                                            SpecFixtures.payment(PaymentStatus.CAPTURED),
                                            SpecFixtures.order(OrderStatus.AWAITING_PAYMENT),
                                            history))
                            .type(),
                    () -> "history of size " + history.size() + " changed the selected cell");
        }
    }
}
