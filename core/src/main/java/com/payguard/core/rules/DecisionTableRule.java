package com.payguard.core.rules;

import com.payguard.core.engine.DecisionTable;
import com.payguard.core.engine.TableOutcome;
import com.payguard.core.error.CurrencyMismatchException;
import com.payguard.core.model.Money;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import java.time.Duration;
import java.util.Optional;

/**
 * Priority 6. Selects a cell from the 80-cell table using <strong>{@code
 * PaymentObservation.status} alone</strong> (INV-13).
 *
 * <p>Transaction history cannot influence which cell is chosen. It has three permitted uses — the
 * duplicate rule at priority 4, the contradiction gate at priority 2, and evidence — and nothing
 * else (ADR-016). Revision 0.2's FR-06 let the last history record decide the state, which
 * contradicted the query-first architecture and would have been unimplementable anyway, because
 * PayTR's history service returns no status field at all.
 */
public final class DecisionTableRule implements ReconciliationRule {

    /** An agreement outcome is signalled by an empty result plus this flag on the context. */
    private boolean lastWasAgreement;

    @Override
    public String name() {
        return "decision-table";
    }

    public boolean lastWasAgreement() {
        return lastWasAgreement;
    }

    @Override
    public Optional<ReconciliationFinding> evaluate(RuleContext ctx) throws CurrencyMismatchException {
        lastWasAgreement = false;
        PaymentStatus payment = ctx.input().payment().status();
        OrderStatus order = ctx.input().order().status();
        TableOutcome outcome = DecisionTable.lookup(payment, order);

        if (outcome instanceof TableOutcome.Agreement) {
            lastWasAgreement = true;
            ctx.note("table[" + payment + "][" + order + "] = agreement; proceed to amounts");
            return Optional.empty();
        }

        FindingType type;
        if (outcome instanceof TableOutcome.Fixed fixed) {
            type = fixed.type();
            ctx.note("table[" + payment + "][" + order + "] = " + type);
        } else if (outcome instanceof TableOutcome.ThresholdConditional conditional) {
            type = resolveThreshold(ctx, conditional, payment, order);
        } else {
            throw new IllegalStateException("unhandled table outcome: " + outcome);
        }

        if (type == FindingType.REFUND_DIVERGENCE) {
            ctx.reason("refund_direction=" + refundDirection(payment, order));
        }

        return Optional.of(
                ReconciliationFinding.of(
                        type,
                        exposureFor(type, ctx, payment, order),
                        ctx.reasons(),
                        ctx.decisionPath(),
                        ctx.input().policy().version()));
    }

    /**
     * A conditional cell compares age against a stale threshold, measured from the same reference
     * instant as grace, so it too cannot be restarted by polling (BR-07).
     */
    private static FindingType resolveThreshold(
            RuleContext ctx, TableOutcome.ThresholdConditional cell, PaymentStatus p, OrderStatus o) {
        Duration threshold =
                cell.threshold() == TableOutcome.Threshold.AUTHORIZATION
                        ? ctx.input().policy().staleAuthorizationThreshold()
                        : ctx.input().policy().staleInProgressThreshold();
        Optional<Duration> age = ReferenceInstant.discrepancyAge(ctx.input());

        if (age.isEmpty()) {
            ctx.note(
                    "table["
                            + p
                            + "]["
                            + o
                            + "] conditional, but no domain time available; treated as below threshold");
            return cell.below();
        }
        boolean atOrAbove = age.get().compareTo(threshold) >= 0;
        ctx.note(
                "table["
                        + p
                        + "]["
                        + o
                        + "] conditional: age "
                        + age.get()
                        + (atOrAbove ? " >= " : " < ")
                        + threshold
                        + " -> "
                        + (atOrAbove ? cell.atOrAbove() : cell.below()));
        ctx.reason("stale_threshold=" + threshold);
        return atOrAbove ? cell.atOrAbove() : cell.below();
    }

    /**
     * Which direction a refund divergence runs. The remedy differs sharply: {@code shop_only} means
     * the customer is owed money and has not received it.
     */
    static String refundDirection(PaymentStatus payment, OrderStatus order) {
        boolean gatewayRefunded =
                payment == PaymentStatus.REFUNDED || payment == PaymentStatus.PARTIALLY_REFUNDED;
        boolean shopRefunded = order == OrderStatus.REFUNDED;

        if (payment == PaymentStatus.PARTIALLY_REFUNDED
                && (order == OrderStatus.REFUNDED || order == OrderStatus.CANCELLED)) {
            return "partial_vs_full";
        }
        if (gatewayRefunded && !shopRefunded) {
            return "gateway_only";
        }
        if (!gatewayRefunded && shopRefunded) {
            return "shop_only";
        }
        return "partial_vs_full";
    }

    /** Exposure per requirements §4.4, which is the authoritative table. */
    private static Optional<Money> exposureFor(
            FindingType type, RuleContext ctx, PaymentStatus payment, OrderStatus order)
            throws CurrencyMismatchException {
        PaymentObservation p = ctx.input().payment();
        return switch (type) {
            case PAYMENT_WITHOUT_ORDER -> Exposure.collectedLessRefunded(p);
            case ORDER_UNPAID_DESPITE_PAYMENT, PAYMENT_AGAINST_CANCELLED_ORDER ->
                    Exposure.collected(p);
            case ORDER_PAID_WITHOUT_PAYMENT -> ctx.input().order().total();
            case AUTHORIZATION_PENDING, STALE_AUTHORIZATION -> Exposure.requested(p);
            case REFUND_DIVERGENCE ->
                    "gateway_only".equals(refundDirection(payment, order))
                            ? p.refunded()
                            : Exposure.none();
            // MATCHED, STATUS_CONFLICT, IN_PROGRESS_PENDING, STALE_IN_PROGRESS,
            // INSUFFICIENT_DATA: no computable exposure (§4.4).
            default -> Exposure.none();
        };
    }
}
