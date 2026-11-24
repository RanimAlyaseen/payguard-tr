package com.payguard.core.rules;

import com.payguard.core.error.CurrencyMismatchException;
import com.payguard.core.model.Money;
import com.payguard.core.model.PaymentObservation;
import java.util.Optional;

/**
 * Computes {@code amountAtRisk} per requirements §4.4, which is the single authoritative table.
 *
 * <p>The distinction this class exists to preserve: <strong>absent</strong> means not computable,
 * <strong>present and zero</strong> means a computed zero exposure. Revision 0.2 used zero for both
 * and coupled severity to it, so a serious finding was demoted precisely when its exposure happened
 * to be unavailable.
 */
final class Exposure {

    private Exposure() {}

    static Optional<Money> none() {
        return Optional.empty();
    }

    static Optional<Money> collected(PaymentObservation payment) {
        return payment.collected();
    }

    static Optional<Money> requested(PaymentObservation payment) {
        return payment.requested();
    }

    /**
     * Collected minus refunded, via {@code absoluteDifference} because {@code Money} is non-negative
     * and has no {@code minus()} (ADR-003). Legitimately zero for a fully refunded orphan payment,
     * and absent when either amount is unknown.
     */
    static Optional<Money> collectedLessRefunded(PaymentObservation payment)
            throws CurrencyMismatchException {
        Optional<Money> collected = payment.collected();
        Optional<Money> refunded = payment.refunded();
        if (collected.isEmpty()) {
            return Optional.empty();
        }
        if (refunded.isEmpty()) {
            // DEF-001. An earlier implementation returned the whole collected amount here, on the
            // reasoning that "no refund recorded" means "nothing refunded". The specification says
            // otherwise: §4.4 requires the exposure to be "present when both known" and "absent if
            // either amount is unknown". An unrecorded refund amount is unknown, not zero, and
            // reporting a figure PayGuard cannot stand behind is the exact conflation between a
            // value and an absence that revision 0.3 removed. Severity is unaffected either way
            // (INV-08), so the finding stays HIGH and the merchant is simply not given a number.
            return Optional.empty();
        }
        return Optional.of(collected.get().absoluteDifference(refunded.get()));
    }
}
