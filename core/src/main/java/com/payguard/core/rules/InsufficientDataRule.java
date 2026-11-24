package com.payguard.core.rules;

import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import java.util.Optional;

/**
 * Priority 2. Three distinct routes to "we cannot judge this", all of which are real integrity
 * problems rather than missing optional data — which is why INSUFFICIENT_DATA is MEDIUM, not LOW.
 *
 * <ol>
 *   <li>Either status is UNKNOWN (INV-07). An unrecognised provider value must never be read as
 *       success or failure.
 *   <li>Two history records claim one identity but differ in content (FR-05.3). Choosing by position
 *       would make the verdict depend on retrieval order; choosing by content would invent a
 *       precedence the provider never stated.
 *   <li>The history contradicts the authoritative status (FR-06). Neither source wins.
 * </ol>
 */
public final class InsufficientDataRule implements ReconciliationRule {

    @Override
    public String name() {
        return "insufficient-data";
    }

    @Override
    public Optional<ReconciliationFinding> evaluate(RuleContext ctx) {
        PaymentStatus payment = ctx.input().payment().status();
        OrderStatus order = ctx.input().order().status();

        if (payment == PaymentStatus.UNKNOWN || order == OrderStatus.UNKNOWN) {
            ctx.reason("unrecognised_status");
            ctx.note("gate 2: unrecognised status (payment=" + payment + ", order=" + order + ")");
            return Optional.of(finding(ctx));
        }

        if (ctx.history().hasConflicts()) {
            ctx.reason("conflicting_records_same_identity");
            for (String identity : ctx.history().conflictingIdentities()) {
                ctx.reason("conflicting_identity=" + identity);
            }
            ctx.note(
                    "gate 2: "
                            + ctx.history().conflictingIdentities().size()
                            + " record identity group(s) with differing content");
            return Optional.of(finding(ctx));
        }

        if (contradictsHistory(payment, ctx)) {
            ctx.reason("gateway_history_contradicts_queried_state");
            ctx.reason("queried_status=" + payment);
            ctx.reason("capture_records=" + ctx.history().captures().size());
            ctx.note(
                    "gate 2: queried status "
                            + payment
                            + " cannot coexist with "
                            + ctx.history().captures().size()
                            + " capture record(s); neither source wins");
            return Optional.of(finding(ctx));
        }

        ctx.note("gate 2: statuses recognised, history consistent with queried status");
        return Optional.empty();
    }

    /**
     * The clearest contradiction: the gateway's query API says nothing was captured, while its own
     * history service lists a capture.
     */
    private static boolean contradictsHistory(PaymentStatus payment, RuleContext ctx) {
        boolean noCaptureClaimed = payment == PaymentStatus.FAILED || payment == PaymentStatus.ABSENT;
        return noCaptureClaimed && !ctx.history().captures().isEmpty();
    }

    private static ReconciliationFinding finding(RuleContext ctx) {
        return ReconciliationFinding.of(
                FindingType.INSUFFICIENT_DATA,
                Exposure.none(),
                ctx.reasons(),
                ctx.decisionPath(),
                ctx.input().policy().version());
    }
}
