package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** INV-03: observations are immutable and the caller's collections are never touched. */
class ImmutabilityTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("the caller's list is not modified by reconciliation")
    void callerListIsUntouched() throws Exception {
        List<TransactionRecord> callerList =
                new ArrayList<>(
                        List.of(
                                SpecFixtures.capture("c1", 125_000),
                                SpecFixtures.capture("c1", 125_000)));
        int sizeBefore = callerList.size();

        ReconciliationInput input =
                SpecFixtures.input(
                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                        callerList);
        engine.reconcile(input);

        assertEquals(sizeBefore, callerList.size(), "normalisation must not collapse the caller's list");
    }

    @Test
    @DisplayName("the input's own history is unmodifiable")
    void inputHistoryIsUnmodifiable() throws Exception {
        ReconciliationInput input =
                SpecFixtures.input(
                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                        List.of(SpecFixtures.capture("c1", 125_000)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> input.history().add(SpecFixtures.capture("c9", 1)));
    }

    @Test
    @DisplayName("a finding's reasons and decision path are unmodifiable")
    void findingCollectionsAreUnmodifiable() throws Exception {
        ReconciliationFinding finding =
                engine.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.AWAITING_PAYMENT));
        assertThrows(UnsupportedOperationException.class, () -> finding.reasons().add("tampered"));
        assertThrows(UnsupportedOperationException.class, () -> finding.decisionPath().add("tampered"));
    }
}
