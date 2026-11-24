package com.payguard.core.rules;

import com.payguard.core.error.CurrencyMismatchException;
import com.payguard.core.error.InvalidReconciliationInputException;
import com.payguard.core.model.Money;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Priority 7, reached only for the two agreement cells. Three independent comparisons (FR-02).
 *
 * <p><strong>(b) exists because of a documented provider behaviour.</strong> PayTR states that the
 * collected total can legitimately exceed the requested amount, for instance with instalments. A
 * reconciler comparing the order total against what the customer actually paid would therefore raise
 * a false mismatch on a large share of real Turkish card payments. So (a) compares the
 * <em>requested</em> amount against the order total, and an excess in the collected amount is
 * expected when an instalment count is present.
 *
 * <p><strong>(c) is one-directional in the other sense.</strong> The documented allowance covers a
 * collected total <em>above</em> the requested amount only, so under-collection is a finding
 * regardless of instalment count. Revision 0.2's wording left that open.
 */
public final class AmountComparisonRule implements ReconciliationRule {

    @Override
    public String name() {
        return "amount-comparison";
    }

    @Override
    public Optional<ReconciliationFinding> evaluate(RuleContext ctx)
            throws CurrencyMismatchException, InvalidReconciliationInputException {
        PaymentObservation payment = ctx.input().payment();
        Optional<Money> requested = payment.requested();
        Optional<Money> collected = payment.collected();
        Optional<Money> orderTotal = ctx.input().order().total();

        long toleranceMinor = ctx.input().policy().amountToleranceMinorUnits();
        List<String> firedReasons = new ArrayList<>();
        Money largestDifference = null;

        // (a) order agreement: requested vs order total
        if (requested.isPresent() && orderTotal.isPresent()) {
            Money difference = requested.get().absoluteDifference(orderTotal.get());
            if (difference.minorUnits() > toleranceMinor) {
                firedReasons.add("order_total_disagrees");
                largestDifference = max(largestDifference, difference);
            }
        }

        // (b) over-collection
        if (requested.isPresent() && collected.isPresent()) {
            if (collected.get().isGreaterThan(requested.get())) {
                boolean instalmentsAllow =
                        payment.installmentCount() > 0 && ctx.input().policy().allowInstallmentExcess();
                if (instalmentsAllow) {
                    ctx.note(
                            "amounts: collected exceeds requested by "
                                    + collected.get().absoluteDifference(requested.get())
                                    + " with "
                                    + payment.installmentCount()
                                    + " instalment(s); documented as expected");
                    ctx.reason("installment_excess_allowed");
                } else {
                    firedReasons.add("over_collected_without_installments");
                    largestDifference =
                            max(largestDifference, collected.get().absoluteDifference(requested.get()));
                }
            }

            // (c) under-collection, never excused by instalments
            Money underBy = requested.get().absoluteDifference(collected.get());
            if (collected.get().isLessThan(requested.get()) && underBy.minorUnits() > toleranceMinor) {
                firedReasons.add("under_collected");
                largestDifference = max(largestDifference, underBy);
            }
        }

        if (firedReasons.isEmpty()) {
            ctx.note("amounts: within tolerance " + toleranceMinor + " minor units");
            return Optional.empty();
        }

        firedReasons.forEach(ctx::reason);
        ctx.note("amounts: " + String.join(", ", firedReasons));
        return Optional.of(
                ReconciliationFinding.of(
                        FindingType.AMOUNT_MISMATCH,
                        Optional.ofNullable(largestDifference),
                        ctx.reasons(),
                        ctx.decisionPath(),
                        ctx.input().policy().version()));
    }

    private static Money max(Money current, Money candidate) throws CurrencyMismatchException {
        if (current == null) {
            return candidate;
        }
        return candidate.isGreaterThan(current) ? candidate : current;
    }
}
