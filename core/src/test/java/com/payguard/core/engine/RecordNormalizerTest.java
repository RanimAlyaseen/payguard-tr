package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.model.RecordKind;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.spec.SpecFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-05.1 and FR-05.2: collapse repetitions, order canonically, and never by retrieval order. */
class RecordNormalizerTest {

    @Test
    @DisplayName("content-identical rows under one identity collapse to one")
    void identicalRowsCollapse() throws Exception {
        TransactionRecord firstFetch =
                SpecFixtures.record("a", RecordKind.CAPTURE, 100, SpecFixtures.NOW.minusSeconds(60), 3L);
        TransactionRecord secondFetch =
                SpecFixtures.record("a", RecordKind.CAPTURE, 100, SpecFixtures.NOW.minusSeconds(60), 7L);
        TransactionRecord other =
                SpecFixtures.record("b", RecordKind.REFUND, 50, SpecFixtures.NOW.minusSeconds(30), 1L);

        NormalizedHistory normalized = RecordNormalizer.normalize(List.of(other, firstFetch, secondFetch));
        assertEquals(2, normalized.records().size());
        assertEquals(1, normalized.collapsedRepetitions());
        assertFalse(normalized.hasConflicts());
    }

    @Test
    @DisplayName("the canonical order is by occurrence time, then identity")
    void canonicalOrderIsSetIntrinsic() throws Exception {
        TransactionRecord later =
                SpecFixtures.record("a", RecordKind.CAPTURE, 100, SpecFixtures.NOW.minusSeconds(30), 1L);
        TransactionRecord earlier =
                SpecFixtures.record("b", RecordKind.CAPTURE, 100, SpecFixtures.NOW.minusSeconds(60), 2L);

        NormalizedHistory normalized = RecordNormalizer.normalize(List.of(later, earlier));
        assertEquals("b", normalized.records().get(0).recordIdentity(), "earlier occurrence first");
        assertEquals("a", normalized.records().get(1).recordIdentity());
    }

    @Test
    @DisplayName("INV-11: a tie on occurrence time breaks on identity, never on retrieval order")
    void tiesBreakOnIdentity() throws Exception {
        TransactionRecord zed =
                SpecFixtures.record("z", RecordKind.CAPTURE, 1, SpecFixtures.ONE_HOUR_AGO, 1L);
        TransactionRecord ay =
                SpecFixtures.record("a", RecordKind.CAPTURE, 1, SpecFixtures.ONE_HOUR_AGO, 2L);

        assertEquals("a", RecordNormalizer.normalize(List.of(zed, ay)).records().get(0).recordIdentity());
        assertEquals(
                "a",
                RecordNormalizer.normalize(List.of(ay, zed)).records().get(0).recordIdentity(),
                "the sort key must be intrinsic to the set, so input order cannot influence it");
    }

    @Test
    @DisplayName("an empty history normalises to an empty result rather than failing")
    void emptyHistoryIsValid() {
        NormalizedHistory normalized = RecordNormalizer.normalize(List.of());
        assertTrue(normalized.isEmpty());
        assertTrue(normalized.captures().isEmpty());
        assertFalse(normalized.hasConflicts());
        assertEquals(0, normalized.collapsedRepetitions());
    }

    @Test
    @DisplayName("a null history normalises to empty")
    void nullHistoryIsNormalised() {
        assertTrue(RecordNormalizer.normalize(null).isEmpty());
    }
}
