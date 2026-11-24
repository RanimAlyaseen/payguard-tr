package com.payguard.core.spec;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The expected decision table, <strong>mechanically transcribed from requirements.md section
 * 4.2</strong>.
 *
 * <p>GENERATED FILE - do not hand-edit. It was produced by parsing the specification's markdown
 * table, which matters for a verification project: the expected values provably come from the
 * specification document and not from the implementation's own table in
 * com.payguard.core.engine.DecisionTable. The two are independent transcriptions of one source, so
 * a disagreement between them is a real defect rather than a tautology.
 *
 * <p>This class deliberately has <strong>no test-framework imports</strong>, so the same data can be
 * consumed both by the JUnit 5 suite and by the dependency-free verification harness.
 *
 * <p>80 cells: 8 payment statuses x 10 order statuses.
 */
public final class SpecDecisionTable {

    /** Number of cells transcribed from the specification. */
    public static final int CELL_COUNT = 80;

    /** One cell of the specification table. */
    public sealed interface SpecCell {

        /** The cell names a finding outright. */
        record Fixed(FindingType type) implements SpecCell {}

        /** The two sides agree; proceed to the amount comparison, then MATCHED. */
        record Agreement() implements SpecCell {}

        /** Conditional on a stale threshold: below it, or at or above it. */
        record Conditional(FindingType below, FindingType atOrAbove) implements SpecCell {}

        static SpecCell fixed(FindingType t) {
            return new Fixed(t);
        }

        static SpecCell agreement() {
            return new Agreement();
        }

        static SpecCell conditional(FindingType below, FindingType atOrAbove) {
            return new Conditional(below, atOrAbove);
        }
    }

    /** One transcribed cell, for parameterised tests. */
    public record Entry(PaymentStatus payment, OrderStatus order, SpecCell expected) {}

    private static final Map<PaymentStatus, Map<OrderStatus, SpecCell>> CELLS =
            new LinkedHashMap<>();
    private static final List<Entry> ENTRIES = new ArrayList<>();

    private SpecDecisionTable() {}

    private static void put(PaymentStatus payment, OrderStatus order, SpecCell cell) {
        CELLS.computeIfAbsent(payment, k -> new LinkedHashMap<>()).put(order, cell);
        ENTRIES.add(new Entry(payment, order, cell));
    }

    static {
        put(PaymentStatus.CAPTURED, OrderStatus.ABSENT, SpecCell.fixed(FindingType.PAYMENT_WITHOUT_ORDER));
        put(PaymentStatus.CAPTURED, OrderStatus.UNKNOWN, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.CAPTURED, OrderStatus.DRAFT, SpecCell.fixed(FindingType.PAYMENT_WITHOUT_ORDER));
        put(PaymentStatus.CAPTURED, OrderStatus.AWAITING_PAYMENT, SpecCell.fixed(FindingType.ORDER_UNPAID_DESPITE_PAYMENT));
        put(PaymentStatus.CAPTURED, OrderStatus.PAYMENT_UNCONFIRMED, SpecCell.fixed(FindingType.ORDER_UNPAID_DESPITE_PAYMENT));
        put(PaymentStatus.CAPTURED, OrderStatus.PAID_UNFULFILLED, SpecCell.agreement());
        put(PaymentStatus.CAPTURED, OrderStatus.FULFILLED, SpecCell.agreement());
        put(PaymentStatus.CAPTURED, OrderStatus.CANCELLED, SpecCell.fixed(FindingType.PAYMENT_AGAINST_CANCELLED_ORDER));
        put(PaymentStatus.CAPTURED, OrderStatus.REFUNDED, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.CAPTURED, OrderStatus.FAILED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.AUTHORIZED, OrderStatus.ABSENT, SpecCell.conditional(FindingType.AUTHORIZATION_PENDING, FindingType.STALE_AUTHORIZATION));
        put(PaymentStatus.AUTHORIZED, OrderStatus.UNKNOWN, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.AUTHORIZED, OrderStatus.DRAFT, SpecCell.conditional(FindingType.AUTHORIZATION_PENDING, FindingType.STALE_AUTHORIZATION));
        put(PaymentStatus.AUTHORIZED, OrderStatus.AWAITING_PAYMENT, SpecCell.conditional(FindingType.AUTHORIZATION_PENDING, FindingType.STALE_AUTHORIZATION));
        put(PaymentStatus.AUTHORIZED, OrderStatus.PAYMENT_UNCONFIRMED, SpecCell.conditional(FindingType.AUTHORIZATION_PENDING, FindingType.STALE_AUTHORIZATION));
        put(PaymentStatus.AUTHORIZED, OrderStatus.PAID_UNFULFILLED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.AUTHORIZED, OrderStatus.FULFILLED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.AUTHORIZED, OrderStatus.CANCELLED, SpecCell.conditional(FindingType.AUTHORIZATION_PENDING, FindingType.STALE_AUTHORIZATION));
        put(PaymentStatus.AUTHORIZED, OrderStatus.REFUNDED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.AUTHORIZED, OrderStatus.FAILED, SpecCell.conditional(FindingType.AUTHORIZATION_PENDING, FindingType.STALE_AUTHORIZATION));
        put(PaymentStatus.FAILED, OrderStatus.ABSENT, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.FAILED, OrderStatus.UNKNOWN, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.FAILED, OrderStatus.DRAFT, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.FAILED, OrderStatus.AWAITING_PAYMENT, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.FAILED, OrderStatus.PAYMENT_UNCONFIRMED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.FAILED, OrderStatus.PAID_UNFULFILLED, SpecCell.fixed(FindingType.ORDER_PAID_WITHOUT_PAYMENT));
        put(PaymentStatus.FAILED, OrderStatus.FULFILLED, SpecCell.fixed(FindingType.ORDER_PAID_WITHOUT_PAYMENT));
        put(PaymentStatus.FAILED, OrderStatus.CANCELLED, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.FAILED, OrderStatus.REFUNDED, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.FAILED, OrderStatus.FAILED, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.REFUNDED, OrderStatus.ABSENT, SpecCell.fixed(FindingType.PAYMENT_WITHOUT_ORDER));
        put(PaymentStatus.REFUNDED, OrderStatus.UNKNOWN, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.REFUNDED, OrderStatus.DRAFT, SpecCell.fixed(FindingType.PAYMENT_WITHOUT_ORDER));
        put(PaymentStatus.REFUNDED, OrderStatus.AWAITING_PAYMENT, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.REFUNDED, OrderStatus.PAYMENT_UNCONFIRMED, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.REFUNDED, OrderStatus.PAID_UNFULFILLED, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.REFUNDED, OrderStatus.FULFILLED, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.REFUNDED, OrderStatus.CANCELLED, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.REFUNDED, OrderStatus.REFUNDED, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.REFUNDED, OrderStatus.FAILED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.ABSENT, SpecCell.fixed(FindingType.PAYMENT_WITHOUT_ORDER));
        put(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.UNKNOWN, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.DRAFT, SpecCell.fixed(FindingType.PAYMENT_WITHOUT_ORDER));
        put(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.AWAITING_PAYMENT, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.PAYMENT_UNCONFIRMED, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.PAID_UNFULFILLED, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.FULFILLED, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.CANCELLED, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED, SpecCell.fixed(FindingType.REFUND_DIVERGENCE));
        put(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.FAILED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.IN_PROGRESS, OrderStatus.ABSENT, SpecCell.conditional(FindingType.IN_PROGRESS_PENDING, FindingType.STALE_IN_PROGRESS));
        put(PaymentStatus.IN_PROGRESS, OrderStatus.UNKNOWN, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.IN_PROGRESS, OrderStatus.DRAFT, SpecCell.conditional(FindingType.IN_PROGRESS_PENDING, FindingType.STALE_IN_PROGRESS));
        put(PaymentStatus.IN_PROGRESS, OrderStatus.AWAITING_PAYMENT, SpecCell.conditional(FindingType.IN_PROGRESS_PENDING, FindingType.STALE_IN_PROGRESS));
        put(PaymentStatus.IN_PROGRESS, OrderStatus.PAYMENT_UNCONFIRMED, SpecCell.conditional(FindingType.IN_PROGRESS_PENDING, FindingType.STALE_IN_PROGRESS));
        put(PaymentStatus.IN_PROGRESS, OrderStatus.PAID_UNFULFILLED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.IN_PROGRESS, OrderStatus.FULFILLED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.IN_PROGRESS, OrderStatus.CANCELLED, SpecCell.conditional(FindingType.IN_PROGRESS_PENDING, FindingType.STALE_IN_PROGRESS));
        put(PaymentStatus.IN_PROGRESS, OrderStatus.REFUNDED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.IN_PROGRESS, OrderStatus.FAILED, SpecCell.conditional(FindingType.IN_PROGRESS_PENDING, FindingType.STALE_IN_PROGRESS));
        put(PaymentStatus.ABSENT, OrderStatus.ABSENT, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.ABSENT, OrderStatus.UNKNOWN, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.ABSENT, OrderStatus.DRAFT, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.ABSENT, OrderStatus.AWAITING_PAYMENT, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.ABSENT, OrderStatus.PAYMENT_UNCONFIRMED, SpecCell.fixed(FindingType.STATUS_CONFLICT));
        put(PaymentStatus.ABSENT, OrderStatus.PAID_UNFULFILLED, SpecCell.fixed(FindingType.ORDER_PAID_WITHOUT_PAYMENT));
        put(PaymentStatus.ABSENT, OrderStatus.FULFILLED, SpecCell.fixed(FindingType.ORDER_PAID_WITHOUT_PAYMENT));
        put(PaymentStatus.ABSENT, OrderStatus.CANCELLED, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.ABSENT, OrderStatus.REFUNDED, SpecCell.fixed(FindingType.ORDER_PAID_WITHOUT_PAYMENT));
        put(PaymentStatus.ABSENT, OrderStatus.FAILED, SpecCell.fixed(FindingType.MATCHED));
        put(PaymentStatus.UNKNOWN, OrderStatus.ABSENT, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.UNKNOWN, OrderStatus.UNKNOWN, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.UNKNOWN, OrderStatus.DRAFT, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.UNKNOWN, OrderStatus.AWAITING_PAYMENT, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.UNKNOWN, OrderStatus.PAYMENT_UNCONFIRMED, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.UNKNOWN, OrderStatus.PAID_UNFULFILLED, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.UNKNOWN, OrderStatus.FULFILLED, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.UNKNOWN, OrderStatus.CANCELLED, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.UNKNOWN, OrderStatus.REFUNDED, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
        put(PaymentStatus.UNKNOWN, OrderStatus.FAILED, SpecCell.fixed(FindingType.INSUFFICIENT_DATA));
    }

    /** Every transcribed cell, in specification order. */
    public static List<Entry> entries() {
        return List.copyOf(ENTRIES);
    }

    public static SpecCell expected(PaymentStatus payment, OrderStatus order) {
        Map<OrderStatus, SpecCell> row = CELLS.get(payment);
        if (row == null || row.get(order) == null) {
            throw new IllegalStateException(
                    "specification table has no cell for " + payment + "/" + order);
        }
        return row.get(order);
    }
}
