package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.policy.ReconciliationPolicy;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * FR-02: the three amount comparisons, at their boundaries.
 *
 * <p>The tolerance boundary is tested at tolerance-1, tolerance and tolerance+1 in both directions,
 * because "within tolerance" is inclusive and an off-by-one here is the difference between a
 * merchant seeing a false alarm every day and seeing nothing at all.
 */
class AmountComparisonTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @ParameterizedTest(name = "requested {0} vs order total {1} with tolerance {2} -> {3}")
    @CsvSource({
        "125000, 125000, 100, MATCHED",
        "125099, 125000, 100, MATCHED",
        "125100, 125000, 100, MATCHED",
        "125101, 125000, 100, AMOUNT_MISMATCH",
        "125000, 125000, 0, MATCHED",
        "125001, 125000, 0, AMOUNT_MISMATCH",
        "124999, 125000, 0, AMOUNT_MISMATCH"
    })
    @DisplayName("boundary values of the order-total comparison")
    void orderTotalComparisonBoundaries(
            long paidAmount, long orderTotal, long tolerance, FindingType expected) throws Exception {
        assertEquals(
                expected,
                evaluate(paidAmount, paidAmount, orderTotal, 0, SpecFixtures.policy().withAmountTolerance(tolerance))
                        .type());
    }

    @Test
    @DisplayName("under-collection is never excused, with or without instalments")
    void underCollectionIsNeverExcused() throws Exception {
        ReconciliationPolicy policy = SpecFixtures.policy().withAmountTolerance(100);

        ReconciliationFinding withoutInstalments = evaluate(125_000, 124_899, 125_000, 0, policy);
        assertEquals(FindingType.AMOUNT_MISMATCH, withoutInstalments.type());
        assertTrue(withoutInstalments.reasons().contains("under_collected"));

        ReconciliationFinding withInstalments = evaluate(125_000, 124_899, 125_000, 12, policy);
        assertEquals(
                FindingType.AMOUNT_MISMATCH,
                withInstalments.type(),
                "the instalment allowance is one-directional: it excuses collecting MORE, never LESS");
        assertTrue(withInstalments.reasons().contains("under_collected"));

        assertEquals(
                FindingType.MATCHED,
                evaluate(125_000, 124_900, 125_000, 0, policy).type(),
                "a shortfall exactly at the tolerance is still within it");
    }

    @Test
    @DisplayName("amountAtRisk is the largest absolute difference found")
    void amountAtRiskIsTheLargestDifference() throws Exception {
        assertEquals(
                Optional.of(SpecFixtures.try_(101)),
                evaluate(125_101, 125_101, 125_000, 0, SpecFixtures.policy().withAmountTolerance(100))
                        .amountAtRisk());
    }

    private ReconciliationFinding evaluate(
            long requested, long collected, long orderTotal, int installments, ReconciliationPolicy policy)
            throws Exception {
        PaymentObservation payment =
                new PaymentObservation(
                        "GW",
                        SpecFixtures.ORDER_REF,
                        PaymentStatus.CAPTURED,
                        SpecFixtures.try_(requested),
                        SpecFixtures.try_(collected),
                        null,
                        installments,
                        SpecFixtures.ONE_HOUR_AGO,
                        SpecFixtures.NOW,
                        false);
        OrderObservation order =
                OrderObservation.of(
                        SpecFixtures.ORDER_REF,
                        OrderStatus.PAID_UNFULFILLED,
                        SpecFixtures.try_(orderTotal),
                        SpecFixtures.ONE_HOUR_AGO,
                        SpecFixtures.ONE_HOUR_AGO,
                        SpecFixtures.NOW);
        return engine.reconcile(SpecFixtures.input(payment, order, List.of(), policy, SpecFixtures.NOW));
    }
}
