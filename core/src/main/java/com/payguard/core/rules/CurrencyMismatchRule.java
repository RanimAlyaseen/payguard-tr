package com.payguard.core.rules;

import com.payguard.core.model.Currency;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Priority 3. Runs <strong>only when both observations are present and each carries a
 * currency</strong> (FR-03).
 *
 * <p>Fewer than two monetary amounts in the whole input — an absent side, or a present observation
 * with no amounts, since PayTR omits {@code currency} on a failed callback — <strong>skips</strong>
 * the check. It is not a mismatch and not an error.
 * Revision 0.2 said only "currency check precedes all amount comparison", which three different
 * implementations would all have satisfied.
 *
 * <p>Sits above the grace period because currencies do not converge with time, and above the amount
 * rule because comparing across currencies is meaningless rather than merely wrong.
 *
 * <p>{@code amountAtRisk} is absent: cross-currency arithmetic is forbidden (INV-04), so no exposure
 * is computable without a rate PayGuard has no business inventing.
 */
public final class CurrencyMismatchRule implements ReconciliationRule {

    @Override
    public String name() {
        return "currency-mismatch";
    }

    @Override
    public Optional<ReconciliationFinding> evaluate(RuleContext ctx) {
        // DEF-005. This gate previously compared PaymentObservation.currency() against the order's
        // currency. That accessor reports the requested amount's currency, falling back to the
        // collected amount's - so a payment requested in TRY but collected in EUR reported TRY, the
        // gate passed, and the mismatch surfaced later as a CurrencyMismatchException thrown out of
        // the amount rule. FR-03 requires amount currencies to be resolved before any comparison,
        // and FR-08 forbids an untyped arithmetic failure escaping in place of a finding. Every
        // present monetary field is therefore examined, not just one representative of each side.
        Map<String, Currency> present = new LinkedHashMap<>();
        ctx.input().payment().requested().ifPresent(m -> present.put("payment.requested", m.currency()));
        ctx.input().payment().collected().ifPresent(m -> present.put("payment.collected", m.currency()));
        ctx.input().payment().refunded().ifPresent(m -> present.put("payment.refunded", m.currency()));
        ctx.input().order().total().ifPresent(m -> present.put("order.total", m.currency()));

        if (present.size() < 2) {
            ctx.note("gate 3: currency check skipped (fewer than two monetary amounts present)");
            return Optional.empty();
        }

        Set<Currency> distinct = new LinkedHashSet<>(present.values());
        if (distinct.size() == 1) {
            ctx.note("gate 3: all amounts in " + distinct.iterator().next());
            return Optional.empty();
        }

        ctx.reason("currency_mismatch");
        for (Map.Entry<String, Currency> entry : present.entrySet()) {
            ctx.reason(entry.getKey() + "_currency=" + entry.getValue());
        }
        ctx.note(
                "gate 3: amounts span "
                        + distinct
                        + " across "
                        + present.keySet()
                        + "; no amount comparison attempted");
        return Optional.of(
                ReconciliationFinding.of(
                        FindingType.CURRENCY_MISMATCH,
                        Exposure.none(),
                        ctx.reasons(),
                        ctx.decisionPath(),
                        ctx.input().policy().version()));
    }
}
