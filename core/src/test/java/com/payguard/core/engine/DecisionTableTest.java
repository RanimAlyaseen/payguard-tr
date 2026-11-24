package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.spec.SpecDecisionTable;
import com.payguard.core.spec.SpecDecisionTable.SpecCell;
import com.payguard.core.spec.SpecFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * All 80 cells of the state-pair table.
 *
 * <p>Expected values come from {@link SpecDecisionTable}, which is mechanically transcribed from
 * {@code requirements.md} §4.2. The implementation has its own table in {@link DecisionTable}, so
 * these are two independent transcriptions of one source and a failure means they disagree — which
 * is the point. An expected value must never be changed to match observed output: change
 * requirements §4.2 first, then regenerate.
 */
class DecisionTableTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    static Stream<SpecDecisionTable.Entry> allCells() {
        return SpecDecisionTable.entries().stream();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allCells")
    @DisplayName("every specification cell yields its specified finding")
    void everyCellMatchesTheSpecification(SpecDecisionTable.Entry entry) throws Exception {
        FindingType expected = expectedForHourOldPair(entry.expected());
        FindingType actual = engine.reconcile(SpecFixtures.tableCase(entry.payment(), entry.order())).type();
        assertEquals(
                expected,
                actual,
                () -> entry.payment() + " + " + entry.order() + " (requirements §4.2)");
    }

    /**
     * The fixture is an hour-old pair with equal amounts, inside both stale thresholds: an agreement
     * cell therefore resolves to MATCHED and a conditional cell to its below-threshold value.
     */
    private static FindingType expectedForHourOldPair(SpecCell cell) {
        if (cell instanceof SpecCell.Fixed fixed) {
            return fixed.type();
        }
        if (cell instanceof SpecCell.Agreement) {
            return FindingType.MATCHED;
        }
        if (cell instanceof SpecCell.Conditional conditional) {
            return conditional.below();
        }
        throw new IllegalStateException("unknown cell: " + cell);
    }

    static Stream<SpecDecisionTable.Entry> conditionalCells() {
        return SpecDecisionTable.entries().stream()
                .filter(e -> e.expected() instanceof SpecCell.Conditional);
    }

    @ParameterizedTest(name = "[{index}] {0} at threshold")
    @MethodSource("conditionalCells")
    @DisplayName("a conditional cell resolves to its at-or-above value once the threshold is reached")
    void conditionalCellsResolveAtThreshold(SpecDecisionTable.Entry entry) throws Exception {
        SpecCell.Conditional conditional = (SpecCell.Conditional) entry.expected();
        Duration threshold =
                entry.payment() == PaymentStatus.AUTHORIZED
                        ? SpecFixtures.policy().staleAuthorizationThreshold()
                        : SpecFixtures.policy().staleInProgressThreshold();

        Instant atThreshold = SpecFixtures.NOW.minus(threshold);
        assertEquals(
                conditional.atOrAbove(),
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(entry.payment(), atThreshold),
                                        SpecFixtures.order(entry.order(), atThreshold)))
                        .type(),
                "at the threshold");

        Instant justBelow = SpecFixtures.NOW.minus(threshold.minusMillis(1));
        assertEquals(
                conditional.below(),
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(entry.payment(), justBelow),
                                        SpecFixtures.order(entry.order(), justBelow)))
                        .type(),
                "one millisecond below the threshold");
    }

    @Test
    @DisplayName("the specification and the implementation agree on the table's size")
    void tableSizesAgree() {
        assertEquals(80, SpecDecisionTable.CELL_COUNT, "specification cells");
        assertEquals(80, DecisionTable.cellCount(), "implementation cells");
        assertEquals(8, PaymentStatus.values().length, "payment statuses");
        assertEquals(10, OrderStatus.values().length, "order statuses");
    }
}
