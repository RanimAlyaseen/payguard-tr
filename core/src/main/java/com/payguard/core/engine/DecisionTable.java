package com.payguard.core.engine;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import java.util.EnumMap;
import java.util.Map;

/**
 * The state-pair decision table: 8 payment statuses x 10 order statuses = 80 cells
 * (requirements §4.2).
 *
 * <p>Reached only after the gates at priority 1-4, so by this point both statuses are recognised,
 * no same-identity conflict exists, the history does not contradict the status, currencies agree or
 * one side is absent, and there is no duplicate capture.
 *
 * <p>The UNKNOWN row and column are unreachable through the normal path because gate 2
 * short-circuits them. They are populated anyway so the table is total over both enums and
 * {@code TotalityTest} needs no carve-out: a cell reachable only by a bug should still have a
 * defined answer.
 *
 * <p>This is the <em>implementation</em> of the table. The test suite transcribes requirements §4.2
 * independently, so a test failure means the two disagree — which is the point.
 */
public final class DecisionTable {

    private static final Map<PaymentStatus, Map<OrderStatus, TableOutcome>> TABLE =
            new EnumMap<>(PaymentStatus.class);

    private static final TableOutcome AGREE = new TableOutcome.Agreement();

    private DecisionTable() {}

    private static TableOutcome f(FindingType t) {
        return new TableOutcome.Fixed(t);
    }

    private static TableOutcome auth() {
        return new TableOutcome.ThresholdConditional(
                FindingType.AUTHORIZATION_PENDING,
                FindingType.STALE_AUTHORIZATION,
                TableOutcome.Threshold.AUTHORIZATION);
    }

    private static TableOutcome prog() {
        return new TableOutcome.ThresholdConditional(
                FindingType.IN_PROGRESS_PENDING,
                FindingType.STALE_IN_PROGRESS,
                TableOutcome.Threshold.IN_PROGRESS);
    }

    private static void row(PaymentStatus p, TableOutcome... cells) {
        OrderStatus[] columns = {
            OrderStatus.ABSENT,
            OrderStatus.UNKNOWN,
            OrderStatus.DRAFT,
            OrderStatus.AWAITING_PAYMENT,
            OrderStatus.PAYMENT_UNCONFIRMED,
            OrderStatus.PAID_UNFULFILLED,
            OrderStatus.FULFILLED,
            OrderStatus.CANCELLED,
            OrderStatus.REFUNDED,
            OrderStatus.FAILED
        };
        if (cells.length != columns.length) {
            throw new IllegalStateException(
                    "row " + p + " has " + cells.length + " cells, expected " + columns.length);
        }
        Map<OrderStatus, TableOutcome> byOrder = new EnumMap<>(OrderStatus.class);
        for (int i = 0; i < columns.length; i++) {
            byOrder.put(columns[i], cells[i]);
        }
        TABLE.put(p, byOrder);
    }

    static {
        TableOutcome insufficient = f(FindingType.INSUFFICIENT_DATA);
        TableOutcome matched = f(FindingType.MATCHED);
        TableOutcome conflict = f(FindingType.STATUS_CONFLICT);
        TableOutcome noOrder = f(FindingType.PAYMENT_WITHOUT_ORDER);
        TableOutcome unpaid = f(FindingType.ORDER_UNPAID_DESPITE_PAYMENT);
        TableOutcome cancelled = f(FindingType.PAYMENT_AGAINST_CANCELLED_ORDER);
        TableOutcome paidNoPayment = f(FindingType.ORDER_PAID_WITHOUT_PAYMENT);
        TableOutcome refundDiv = f(FindingType.REFUND_DIVERGENCE);

        //                        ABSENT     UNKNOWN      DRAFT      AWAITING  UNCONFIRMED  PAID_UNFUL  FULFILLED  CANCELLED  REFUNDED   FAILED
        row(PaymentStatus.CAPTURED,
                noOrder, insufficient, noOrder, unpaid, unpaid, AGREE, AGREE, cancelled, refundDiv, conflict);
        row(PaymentStatus.AUTHORIZED,
                auth(), insufficient, auth(), auth(), auth(), conflict, conflict, auth(), conflict, auth());
        row(PaymentStatus.FAILED,
                matched, insufficient, matched, matched, conflict, paidNoPayment, paidNoPayment, matched, refundDiv, matched);
        row(PaymentStatus.REFUNDED,
                noOrder, insufficient, noOrder, refundDiv, refundDiv, refundDiv, refundDiv, matched, matched, conflict);
        row(PaymentStatus.PARTIALLY_REFUNDED,
                noOrder, insufficient, noOrder, refundDiv, refundDiv, refundDiv, refundDiv, refundDiv, refundDiv, conflict);
        row(PaymentStatus.IN_PROGRESS,
                prog(), insufficient, prog(), prog(), prog(), conflict, conflict, prog(), conflict, prog());
        row(PaymentStatus.ABSENT,
                insufficient, insufficient, matched, matched, conflict, paidNoPayment, paidNoPayment, matched, paidNoPayment, matched);
        row(PaymentStatus.UNKNOWN,
                insufficient, insufficient, insufficient, insufficient, insufficient, insufficient, insufficient, insufficient, insufficient, insufficient);
    }

    /** Never returns null: the table is total over both enums (INV-01). */
    public static TableOutcome lookup(PaymentStatus payment, OrderStatus order) {
        Map<OrderStatus, TableOutcome> byOrder = TABLE.get(payment);
        if (byOrder == null) {
            throw new IllegalStateException("decision table has no row for " + payment);
        }
        TableOutcome outcome = byOrder.get(order);
        if (outcome == null) {
            throw new IllegalStateException("decision table has no cell for " + payment + "/" + order);
        }
        return outcome;
    }

    /** Total cell count, for the totality assertion. */
    public static int cellCount() {
        return TABLE.values().stream().mapToInt(Map::size).sum();
    }
}
