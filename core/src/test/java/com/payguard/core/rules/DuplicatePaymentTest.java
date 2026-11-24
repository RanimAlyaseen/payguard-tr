package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.RecordKind;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import com.payguard.core.spec.SpecPermutations;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-05: a double charge is two <em>distinct</em> capture rows, never one row seen twice.
 *
 * <p>This distinction is the whole rule. PayTR retries its callback after a minute, and its
 * transaction report can return the same row across overlapping windows, so a detector that counts
 * rows instead of identities would accuse honest merchants of double-charging their customers.
 */
class DuplicatePaymentTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("two distinct captures for one order reference are a duplicate")
    void twoDistinctCapturesAreADuplicate() throws Exception {
        ReconciliationFinding finding =
                evaluate(List.of(SpecFixtures.capture("txn-1", 125_000), SpecFixtures.capture("txn-2", 60_000)));
        assertEquals(FindingType.DUPLICATE_PAYMENT, finding.type());
        assertTrue(finding.reasons().contains("distinct_capture_records=2"));
        assertEquals(
                Optional.of(SpecFixtures.try_(60_000)),
                finding.amountAtRisk(),
                "the exposure is what was taken beyond the first capture");
    }

    @Test
    @DisplayName("every capture beyond the first contributes to the exposure")
    void exposureSumsEveryCaptureBeyondTheFirst() throws Exception {
        ReconciliationFinding finding =
                evaluate(
                        List.of(
                                SpecFixtures.capture("txn-1", 125_000),
                                SpecFixtures.capture("txn-2", 20_000),
                                SpecFixtures.capture("txn-3", 30_000)));
        assertEquals(Optional.of(SpecFixtures.try_(50_000)), finding.amountAtRisk());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 5, 8})
    @DisplayName("INV-06: one capture repeated any number of times is never a duplicate")
    void repeatedIdenticalCaptureIsNotADuplicate(int repetitions) throws Exception {
        List<TransactionRecord> history = new ArrayList<>();
        TransactionRecord sameRow = SpecFixtures.capture("txn-1", 125_000);
        for (int i = 0; i < repetitions; i++) {
            history.add(sameRow);
        }
        assertEquals(
                FindingType.MATCHED,
                evaluate(history).type(),
                "a retried callback is not a second charge");
    }

    @Test
    @DisplayName("FR-05.4: with no history the limitation is recorded in the decision path")
    void withoutHistoryTheLimitationIsRecorded() throws Exception {
        ReconciliationFinding finding = evaluate(List.of());
        assertEquals(FindingType.MATCHED, finding.type());
        assertTrue(
                finding.decisionPath().stream().anyMatch(s -> s.contains("duplicate captures undetectable")),
                "an undetectable duplicate is a documented limitation, not a silent gap");
        assertTrue(
                finding.reasons().isEmpty(),
                "the limitation belongs in the decision path only; a clean match has no reasons");
    }

    @Test
    @DisplayName("refund rows never count towards duplicate captures")
    void refundsAreNotCaptures() throws Exception {
        assertEquals(
                FindingType.MATCHED,
                evaluate(
                                List.of(
                                        SpecFixtures.capture("txn-1", 125_000),
                                        SpecFixtures.record("txn-2", RecordKind.REFUND, 125_000, SpecFixtures.ONE_HOUR_AGO, 2L)))
                        .type());
    }

    @Test
    @DisplayName("INV-09: the verdict holds under every permutation of the rows")
    void verdictIsPermutationInvariant() throws Exception {
        List<TransactionRecord> history =
                List.of(SpecFixtures.capture("txn-1", 125_000), SpecFixtures.capture("txn-2", 60_000));
        for (List<TransactionRecord> permutation : SpecPermutations.of(history)) {
            ReconciliationFinding finding = evaluate(permutation);
            assertEquals(FindingType.DUPLICATE_PAYMENT, finding.type());
            assertEquals(Optional.of(SpecFixtures.try_(60_000)), finding.amountAtRisk());
        }
    }

    @Test
    @DisplayName("an unknown capture amount makes the exposure uncomputable, not zero")
    void unknownAmountMakesExposureAbsent() throws Exception {
        TransactionRecord withoutAmount =
                new TransactionRecord(
                        "txn-2", SpecFixtures.ORDER_REF, RecordKind.CAPTURE, null, SpecFixtures.ONE_HOUR_AGO, 2L);
        ReconciliationFinding finding =
                evaluate(List.of(SpecFixtures.capture("txn-1", 125_000), withoutAmount));
        assertEquals(FindingType.DUPLICATE_PAYMENT, finding.type());
        assertFalse(
                finding.amountAtRisk().isPresent(),
                "a missing amount must not be reported as a zero exposure");
    }

    private ReconciliationFinding evaluate(List<TransactionRecord> history) throws Exception {
        return engine.reconcile(
                SpecFixtures.input(
                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                        history));
    }
}
