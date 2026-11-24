package com.payguard.core.engine;

import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.policy.ReconciliationPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything {@code reconcile} is allowed to see.
 *
 * <p>{@code evaluatedAt} is <strong>injected</strong>, never read from a clock (INV-02). That single
 * decision is what makes the grace boundary testable at an exact value rather than as a race.
 *
 * <p>There is no {@code firstDetectedAt}: the grace ceiling it served was removed in revision 0.3
 * (FR-04.3), so the core needs no state carried in from storage.
 */
public record ReconciliationInput(
        PaymentObservation payment,
        OrderObservation order,
        List<TransactionRecord> history,
        ReconciliationPolicy policy,
        Instant evaluatedAt) {

    public ReconciliationInput {
        // DEF-003. These components were previously guarded with Objects.requireNonNull, which
        // raised a raw NullPointerException from a public constructor. FR-08 requires malformed
        // input to surface as InvalidReconciliationInputException naming the offending field, and
        // states that no untyped exception escapes the module. Validation therefore happens at the
        // reconciliation boundary (ReconciliationEngine.validate) where a typed error can be
        // raised, and this constructor only makes defensive copies.
        //
        // A null history is preserved as null rather than silently becoming an empty list: an
        // empty history is valid input that merely limits duplicate detection (FR-05.4), whereas a
        // null history is malformed. Collapsing the two hid a caller's bug behind a valid-looking
        // verdict.
        history = history == null ? null : Collections.unmodifiableList(new ArrayList<>(history));
    }

    /** No history supplied: valid, but duplicate captures become undetectable (FR-05.4). */
    public boolean hasHistory() {
        return history != null && !history.isEmpty();
    }
}
