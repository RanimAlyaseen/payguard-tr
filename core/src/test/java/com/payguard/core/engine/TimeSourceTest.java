package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.RecordKind;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * INV-11: no rule may read {@code observedAt} or {@code retrievalSequence}.
 *
 * <p>Those two fields are provenance — when PayGuard fetched something and in what order. Domain
 * time is when the event happened at the provider. Revision 0.1 conflated them in one field, which
 * is how the grace-period defect got in. Keeping the two separate is only half the fix; this test is
 * the other half, because nothing in the type system stops a future rule from reading the wrong one.
 */
class TimeSourceTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("varying the retrieval time and order leaves the finding untouched")
    void provenanceDoesNotAffectTheFinding() throws Exception {
        Instant domainTime = SpecFixtures.ONE_HOUR_AGO;
        ReconciliationFinding reference = null;

        for (int offset = 1; offset <= 5; offset++) {
            Instant retrievedAt = SpecFixtures.NOW.minusSeconds(offset);
            PaymentObservation payment =
                    new PaymentObservation(
                            "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                            SpecFixtures.try_(125_000), SpecFixtures.try_(125_000), null, 0,
                            domainTime, retrievedAt, false);
            OrderObservation order =
                    OrderObservation.of(
                            SpecFixtures.ORDER_REF, OrderStatus.AWAITING_PAYMENT,
                            SpecFixtures.try_(125_000), domainTime, retrievedAt, retrievedAt);
            List<TransactionRecord> history =
                    List.of(
                            SpecFixtures.record(
                                    "c1", RecordKind.CAPTURE, 125_000, domainTime, offset * 100L));

            ReconciliationFinding finding = engine.reconcile(SpecFixtures.input(payment, order, history));
            if (reference == null) {
                reference = finding;
            } else {
                assertEquals(reference.type(), finding.type(), "finding type");
                assertEquals(reference.reasons(), finding.reasons(), "reasons");
                assertEquals(
                        reference.decisionPath(),
                        finding.decisionPath(),
                        "a differing decision path means a rule consulted a retrieval field");
            }
        }
    }

    @Test
    @DisplayName("BR-07b: in Scope A the business state change is the order creation time")
    void businessStateChangeIsCreationTimeInScopeA() throws Exception {
        OrderObservation order =
                OrderObservation.of(
                        SpecFixtures.ORDER_REF,
                        OrderStatus.AWAITING_PAYMENT,
                        SpecFixtures.try_(125_000),
                        SpecFixtures.ONE_HOUR_AGO,
                        SpecFixtures.NOW,
                        SpecFixtures.NOW);
        assertEquals(
                SpecFixtures.ONE_HOUR_AGO,
                order.businessStateChangedAt(),
                "the factory must pin this to creation time: WooCommerce exposes no per-transition"
                        + " timestamp, and date_modified moves on edits that are not state changes");
    }
}
