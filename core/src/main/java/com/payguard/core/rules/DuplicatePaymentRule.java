package com.payguard.core.rules;

import com.payguard.core.error.CurrencyMismatchException;
import com.payguard.core.error.MoneyOverflowException;
import com.payguard.core.model.Money;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import java.util.List;
import java.util.Optional;

/**
 * Priority 4. Two or more <strong>distinct-identity</strong> capture records for one order means the
 * customer was charged twice (FR-05.4).
 *
 * <p>Deliberately above the grace period: a second capture is a completed double charge, not an
 * inconsistency awaiting settlement. Revision 0.2 ranked it below grace, so a double charge inside
 * the window was reported as "too early to judge".
 *
 * <p>Counted over the deduplicated set, so it is a property of the set and not of list order — which
 * is what keeps it permutation-invariant (INV-09).
 *
 * <p><strong>With no history supplied this is undetectable.</strong> That is a documented limitation,
 * not a silent gap: the decision path records that history was unavailable, so a MATCHED finding
 * produced without history is distinguishable from one produced with it.
 */
public final class DuplicatePaymentRule implements ReconciliationRule {

    @Override
    public String name() {
        return "duplicate-payment";
    }

    @Override
    public Optional<ReconciliationFinding> evaluate(RuleContext ctx)
            throws CurrencyMismatchException, MoneyOverflowException {
        List<TransactionRecord> captures = ctx.history().captures();

        if (ctx.history().isEmpty()) {
            // DEF-002. FR-05.4 places this limitation in the decision path and nowhere else. An
            // earlier implementation also pushed it into `reasons`, so a clean MATCHED carried a
            // "reason" that was not a reason for the match - noise on the most common verdict in
            // any real store's data.
            ctx.note("gate 4: no transaction history supplied; duplicate captures undetectable");
            return Optional.empty();
        }
        if (captures.size() < 2) {
            ctx.note("gate 4: " + captures.size() + " distinct capture record(s); no duplicate");
            return Optional.empty();
        }

        ctx.reason("duplicate_payment");
        ctx.reason("distinct_capture_records=" + captures.size());
        ctx.note("gate 4: " + captures.size() + " distinct capture records for one order reference");

        return Optional.of(
                ReconciliationFinding.of(
                        FindingType.DUPLICATE_PAYMENT,
                        excessBeyondFirst(captures),
                        ctx.reasons(),
                        ctx.decisionPath(),
                        ctx.input().policy().version()));
    }

    /**
     * The sum of the capture amounts beyond the first (§4.4). Absent when any amount is unknown,
     * because a partial sum would understate the exposure.
     */
    private static Optional<Money> excessBeyondFirst(List<TransactionRecord> captures)
            throws CurrencyMismatchException, MoneyOverflowException {
        Money total = null;
        for (int i = 1; i < captures.size(); i++) {
            Money amount = captures.get(i).amount();
            if (amount == null) {
                return Optional.empty();
            }
            total = total == null ? amount : total.plus(amount);
        }
        return Optional.ofNullable(total);
    }
}
