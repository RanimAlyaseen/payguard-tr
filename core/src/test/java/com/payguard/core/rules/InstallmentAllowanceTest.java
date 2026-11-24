package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.policy.ReconciliationPolicy;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-02b. The rule that only exists because the provider's documentation was read before any code
 * was written: PayTR's callback documents that the collected total can legitimately exceed the
 * requested amount on an instalment payment, because the customer pays the bank's instalment
 * surcharge. A naive "collected != requested means trouble" check would flag every instalment sale
 * in the country.
 */
class InstallmentAllowanceTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 6, 9, 12})
    @DisplayName("an excess over the requested amount is expected when instalments are present")
    void excessIsAllowedWithInstalments(int installments) throws Exception {
        ReconciliationFinding finding = evaluate(125_000, 131_250, installments, SpecFixtures.policy());
        assertEquals(FindingType.MATCHED, finding.type());
        assertTrue(
                finding.reasons().contains("installment_excess_allowed"),
                "the allowance must be visible in the reasons, not applied silently");
    }

    @Test
    @DisplayName("the same excess with no instalments is flagged")
    void excessWithoutInstalmentsIsFlagged() throws Exception {
        ReconciliationFinding finding = evaluate(125_000, 131_250, 0, SpecFixtures.policy());
        assertEquals(FindingType.AMOUNT_MISMATCH, finding.type());
        assertTrue(finding.reasons().contains("over_collected_without_installments"));
    }

    @Test
    @DisplayName("an instalment count above the documented maximum is still a valid count")
    void aboveDocumentedMaximumIsStillValid() throws Exception {
        assertEquals(
                FindingType.MATCHED,
                evaluate(125_000, 131_250, 13, SpecFixtures.policy()).type(),
                "the documented 0-12 range describes what PayTR offers today, not a validity rule;"
                        + " rejecting 13 would make PayGuard brittle to a provider change");
    }

    @Test
    @DisplayName("the allowance is policy-controlled, not hard-coded")
    void allowanceCanBeDisabled() throws Exception {
        ReconciliationPolicy strict = SpecFixtures.policy().withAllowInstallmentExcess(false);
        assertEquals(FindingType.AMOUNT_MISMATCH, evaluate(125_000, 131_250, 3, strict).type());
    }

    private ReconciliationFinding evaluate(
            long requested, long collected, int installments, ReconciliationPolicy policy)
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
        return engine.reconcile(
                SpecFixtures.input(
                        payment,
                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                        List.of(),
                        policy,
                        SpecFixtures.NOW));
    }
}
