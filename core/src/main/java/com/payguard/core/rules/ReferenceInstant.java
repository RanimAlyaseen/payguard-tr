package com.payguard.core.rules;

import com.payguard.core.engine.ReconciliationInput;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The one place {@code discrepancyAge} is computed (FR-04.2).
 *
 * <p>{@code referenceInstant = max(transactionOccurredAt, businessStateChangedAt)} — both
 * <strong>domain</strong> times. No retrieval time appears here, which is the whole correction:
 * revision 0.1 used {@code max(observedAt, lastModifiedAt)}, so every poll reset the age to near
 * zero and a real discrepancy sat inside the grace window forever. More monitoring meant less
 * detection.
 *
 * <p>Because in Scope A {@code businessStateChangedAt} is the order's creation time and never
 * advances (BR-07b), the reference instant is fixed per pair and the age is strictly increasing in
 * {@code evaluatedAt} (INV-12). That is what makes a grace ceiling unnecessary.
 */
final class ReferenceInstant {

    private ReferenceInstant() {}

    static Optional<Instant> of(ReconciliationInput input) {
        Optional<Instant> transaction = input.payment().occurredAt();
        Optional<Instant> business = input.order().businessStateChange();

        if (transaction.isPresent() && business.isPresent()) {
            return Optional.of(
                    transaction.get().isAfter(business.get()) ? transaction.get() : business.get());
        }
        return transaction.isPresent() ? transaction : business;
    }

    /** Empty when neither domain time is available, in which case grace cannot apply. */
    static Optional<Duration> discrepancyAge(ReconciliationInput input) {
        return of(input).map(reference -> Duration.between(reference, input.evaluatedAt()));
    }
}
