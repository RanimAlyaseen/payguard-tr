package com.payguard.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.error.InvalidReconciliationInputException;
import com.payguard.core.spec.SpecFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-05: what makes two history rows "the same row", and what makes them significantly different.
 *
 * <p>The distinction matters because {@code retrievalSequence} is provenance, not content: two
 * fetches of one row differ in it and must still count as one row (INV-06, INV-11).
 */
class RecordIdentityTest {

    @Test
    @DisplayName("INV-11: significant content excludes the retrieval sequence")
    void significantContentExcludesRetrievalSequence() throws Exception {
        TransactionRecord first =
                SpecFixtures.record("id-1", RecordKind.CAPTURE, 125_000, SpecFixtures.ONE_HOUR_AGO, 1L);
        TransactionRecord refetched =
                SpecFixtures.record("id-1", RecordKind.CAPTURE, 125_000, SpecFixtures.ONE_HOUR_AGO, 99L);
        assertEquals(first.significantContent(), refetched.significantContent());
    }

    @Test
    @DisplayName("significant content distinguishes amount, kind and occurrence time")
    void significantContentDistinguishesRealDifferences() throws Exception {
        TransactionRecord base =
                SpecFixtures.record("id-1", RecordKind.CAPTURE, 125_000, SpecFixtures.ONE_HOUR_AGO, 1L);
        assertNotEquals(
                base.significantContent(),
                SpecFixtures.record("id-1", RecordKind.CAPTURE, 999, SpecFixtures.ONE_HOUR_AGO, 1L)
                        .significantContent(),
                "a different amount is a different row");
        assertNotEquals(
                base.significantContent(),
                SpecFixtures.record("id-1", RecordKind.REFUND, 125_000, SpecFixtures.ONE_HOUR_AGO, 1L)
                        .significantContent(),
                "a different kind is a different row");
        assertNotEquals(
                base.significantContent(),
                SpecFixtures.record("id-1", RecordKind.CAPTURE, 125_000, SpecFixtures.NOW, 1L)
                        .significantContent(),
                "a different occurrence time is a different row");
    }

    @Test
    @DisplayName("only CAPTURE rows count as captures")
    void onlyCapturesCount() throws Exception {
        assertTrue(SpecFixtures.capture("c1", 1).isCapture());
        assertFalse(
                SpecFixtures.record("f1", RecordKind.REFUND, 1, SpecFixtures.ONE_HOUR_AGO, 1L).isCapture());
        assertFalse(
                SpecFixtures.record("o1", RecordKind.OTHER, 1, SpecFixtures.ONE_HOUR_AGO, 1L).isCapture());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("a blank record identity is rejected: without it nothing can be deduplicated")
    void blankIdentityIsRejected(String identity) {
        assertThrows(
                InvalidReconciliationInputException.class,
                () -> SpecFixtures.record(identity, RecordKind.CAPTURE, 1, SpecFixtures.ONE_HOUR_AGO, 1L)
                        .validated());
    }
}
