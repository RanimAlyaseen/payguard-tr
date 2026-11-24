package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * INV-02: the same valid input produces the same finding, always.
 *
 * <p>Purity is what makes the rest of this suite meaningful. A core that read a clock, cached state
 * between calls, or depended on instance history could pass every other test on a good run and fail
 * in production, and no test for a specific rule would reveal it.
 */
class PurityTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @RepeatedTest(value = 20, name = "call {currentRepetition} of {totalRepetitions}")
    @DisplayName("repeated calls on one engine instance give the identical finding")
    void repeatedCallsAgree() throws Exception {
        ReconciliationInput input =
                SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.AWAITING_PAYMENT);
        ReconciliationFinding finding = engine.reconcile(input);

        assertEquals(
                com.payguard.core.result.FindingType.ORDER_UNPAID_DESPITE_PAYMENT, finding.type());
        assertEquals(1, finding.decisionPath().stream().filter(s -> s.startsWith("gate 1")).count(),
                "a decision path that grows between calls means state is leaking across evaluations");
    }

    @Test
    @DisplayName("a separate engine instance agrees with this one")
    void separateInstancesAgree() throws Exception {
        ReconciliationInput input =
                SpecFixtures.tableCase(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED);
        ReconciliationFinding first = engine.reconcile(input);
        ReconciliationFinding second = new ReconciliationEngine().reconcile(input);

        assertEquals(first.type(), second.type());
        assertEquals(first.reasons(), second.reasons());
        assertEquals(first.decisionPath(), second.decisionPath());
        assertEquals(first.amountAtRisk(), second.amountAtRisk());
    }

    @Test
    @DisplayName("evaluating other inputs in between changes nothing")
    void interleavedEvaluationsDoNotInterfere() throws Exception {
        ReconciliationInput subject =
                SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.CANCELLED);
        ReconciliationFinding before = engine.reconcile(subject);

        for (PaymentStatus payment : PaymentStatus.values()) {
            for (OrderStatus order : OrderStatus.values()) {
                engine.reconcile(SpecFixtures.tableCase(payment, order));
            }
        }

        ReconciliationFinding after = engine.reconcile(subject);
        assertEquals(before.type(), after.type());
        assertEquals(before.reasons(), after.reasons());
        assertEquals(before.decisionPath(), after.decisionPath());
    }
}
