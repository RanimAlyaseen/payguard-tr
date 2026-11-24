package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.RecordKind;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import com.payguard.core.spec.SpecPermutations;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * INV-09 as a property: the verdict is a function of the <em>set</em> of records, not their order.
 *
 * <p>This matters because nothing guarantees the order rows arrive in. PayTR's transaction report is
 * paginated over a three-day window, its callback can be retried, and the shop-first sweep and
 * gateway-first discovery reach the same order by different routes. If retrieval order could change
 * a verdict, two runs over the same data would disagree and no finding could be trusted.
 *
 * <p>All 24 permutations of four records are checked rather than a random sample: the space is small
 * enough to exhaust, and an exhausted space needs no seed to reproduce.
 */
class OrderIndependencePropertyTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("every permutation of the history produces the identical finding")
    void findingIsInvariantUnderPermutation() throws Exception {
        List<TransactionRecord> history =
                List.of(
                        SpecFixtures.record("c1", RecordKind.CAPTURE, 125_000, SpecFixtures.NOW.minusSeconds(300), 1L),
                        SpecFixtures.record("f1", RecordKind.REFUND, 5_000, SpecFixtures.NOW.minusSeconds(200), 2L),
                        SpecFixtures.record("o1", RecordKind.OTHER, 0, SpecFixtures.NOW.minusSeconds(100), 3L),
                        SpecFixtures.record("o2", RecordKind.OTHER, 0, SpecFixtures.NOW.minusSeconds(100), 4L));

        List<List<TransactionRecord>> permutations = SpecPermutations.of(history);
        assertEquals(24, permutations.size(), "4! permutations");

        ReconciliationFinding reference = evaluate(permutations.get(0));
        for (List<TransactionRecord> permutation : permutations) {
            ReconciliationFinding finding = evaluate(permutation);
            assertEquals(reference.type(), finding.type(), "finding type");
            assertEquals(reference.severity(), finding.severity(), "severity");
            assertEquals(reference.amountAtRisk(), finding.amountAtRisk(), "amount at risk");
            assertEquals(reference.reasons(), finding.reasons(), "reasons");
            assertEquals(
                    reference.decisionPath(),
                    finding.decisionPath(),
                    "even the decision path must match: a differing path means a rule read the order");
        }
    }

    @Test
    @DisplayName("the property holds for a conflicting history too")
    void propertyHoldsForConflictingHistory() throws Exception {
        List<TransactionRecord> conflicting =
                List.of(
                        SpecFixtures.capture("same", 125_000),
                        SpecFixtures.capture("same", 999_999),
                        SpecFixtures.capture("other", 60_000));

        ReconciliationFinding reference = evaluate(conflicting);
        for (List<TransactionRecord> permutation : SpecPermutations.of(conflicting)) {
            ReconciliationFinding finding = evaluate(permutation);
            assertEquals(reference.type(), finding.type());
            assertEquals(reference.reasons(), finding.reasons());
        }
    }

    private ReconciliationFinding evaluate(List<TransactionRecord> history) throws Exception {
        return engine.reconcile(
                SpecFixtures.input(
                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                        history));
    }
}
