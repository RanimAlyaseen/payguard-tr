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
import com.payguard.core.spec.SpecPermutations;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-05.3. When a provider hands back two rows it claims are the same event, with different content,
 * there is no defensible basis for preferring either — and "first wins" or "last wins" would make the
 * answer depend on retrieval order, which INV-09 forbids.
 */
class ConflictingIdentityTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("the verdict is identical in all six permutations of a conflicting history")
    void verdictIsPermutationInvariant() throws Exception {
        List<TransactionRecord> conflicting =
                List.of(
                        SpecFixtures.capture("same", 125_000),
                        SpecFixtures.capture("same", 999_999),
                        SpecFixtures.capture("other", 125_000));

        List<List<TransactionRecord>> permutations = SpecPermutations.of(conflicting);
        assertEquals(6, permutations.size());
        for (List<TransactionRecord> permutation : permutations) {
            ReconciliationFinding finding = evaluate(permutation);
            assertEquals(FindingType.INSUFFICIENT_DATA, finding.type());
            assertTrue(finding.reasons().contains("conflicting_records_same_identity"));
            assertTrue(finding.reasons().contains("conflicting_identity=same"));
        }
    }

    @Test
    @DisplayName("a differing occurrence time under one identity is a conflict")
    void differingTimeIsAConflict() throws Exception {
        assertEquals(
                FindingType.INSUFFICIENT_DATA,
                evaluate(
                                List.of(
                                        SpecFixtures.capture("dup", 125_000),
                                        SpecFixtures.record("dup", RecordKind.CAPTURE, 125_000, SpecFixtures.NOW.minusSeconds(30), 2L)))
                        .type());
    }

    @Test
    @DisplayName("a differing kind under one identity is a conflict")
    void differingKindIsAConflict() throws Exception {
        assertEquals(
                FindingType.INSUFFICIENT_DATA,
                evaluate(
                                List.of(
                                        SpecFixtures.capture("dup", 125_000),
                                        SpecFixtures.record("dup", RecordKind.REFUND, 125_000, SpecFixtures.ONE_HOUR_AGO, 2L)))
                        .type());
    }

    @Test
    @DisplayName("INV-11: a differing retrieval sequence alone is not a conflict")
    void differingRetrievalSequenceIsNotAConflict() throws Exception {
        assertEquals(
                FindingType.MATCHED,
                evaluate(
                                List.of(
                                        SpecFixtures.capture("dup", 125_000),
                                        SpecFixtures.record("dup", RecordKind.CAPTURE, 125_000, SpecFixtures.ONE_HOUR_AGO, 777L)))
                        .type(),
                "two fetches of one row differ only in provenance, which is not content");
    }

    private ReconciliationFinding evaluate(List<TransactionRecord> history) throws Exception {
        return engine.reconcile(
                SpecFixtures.input(
                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                        history));
    }
}
