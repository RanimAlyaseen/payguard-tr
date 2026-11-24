package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * INV-01: <strong>for every valid input</strong>, {@code reconcile} returns exactly one
 * {@code FindingType}.
 *
 * <p>Scoped to the valid domain deliberately. Malformed input lies outside it and raises a typed
 * exception, which is {@code InputValidationTest}'s job (FR-08). Revision 0.2's wording claimed
 * totality over "every input", which contradicted FR-08's exceptions.
 */
class TotalityTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("all 80 valid status combinations return a finding, UNKNOWN row and column included")
    void everyValidCombinationReturnsAFinding() throws Exception {
        int evaluated = 0;
        for (PaymentStatus payment : PaymentStatus.values()) {
            for (OrderStatus order : OrderStatus.values()) {
                ReconciliationFinding finding =
                        engine.reconcile(SpecFixtures.tableCase(payment, order));
                assertNotNull(finding, () -> "null finding for " + payment + "/" + order);
                assertNotNull(finding.type(), () -> "null type for " + payment + "/" + order);
                assertNotNull(finding.severity(), () -> "null severity for " + payment + "/" + order);
                assertNotNull(
                        finding.recommendedAction(), () -> "null action for " + payment + "/" + order);
                assertNotNull(
                        finding.amountAtRisk(),
                        () -> "null Optional for " + payment + "/" + order + " (absent, never null)");
                evaluated++;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(80, evaluated, "combinations evaluated");
    }
}
