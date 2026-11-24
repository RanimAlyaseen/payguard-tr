package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.RecordKind;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * INV-06 as a property: repeating the same rows changes nothing.
 *
 * <p>Property rather than example, because the failure this guards against is systemic. PayTR
 * retries its callback after a minute and its transaction report can return the same row in
 * overlapping windows, so in production the same content arrives repeatedly as a matter of course.
 * An example test would pin one repetition count; this pins the invariant.
 */
class RepetitionInvariancePropertyTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @ParameterizedTest(name = "{0} repetition(s) of the same history")
    @ValueSource(ints = {1, 2, 3, 4, 5, 10})
    @DisplayName("the finding is identical however many times the rows repeat")
    void findingIsInvariantUnderRepetition(int repetitions) throws Exception {
        TransactionRecord capture = SpecFixtures.capture("c1", 125_000);
        TransactionRecord refund =
                SpecFixtures.record("f1", RecordKind.REFUND, 1_000, SpecFixtures.ONE_HOUR_AGO, 2L);

        ReconciliationFinding once = evaluate(List.of(capture, refund));

        List<TransactionRecord> repeated = new ArrayList<>();
        for (int i = 0; i < repetitions; i++) {
            repeated.add(capture);
            repeated.add(refund);
        }
        ReconciliationFinding many = evaluate(repeated);

        assertEquals(once.type(), many.type(), "finding type");
        assertEquals(once.severity(), many.severity(), "severity");
        assertEquals(once.amountAtRisk(), many.amountAtRisk(), "amount at risk");
        assertEquals(once.reasons(), many.reasons(), "reasons");
    }

    private ReconciliationFinding evaluate(List<TransactionRecord> history) throws Exception {
        return engine.reconcile(
                SpecFixtures.input(
                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                        history));
    }
}
