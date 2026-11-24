package com.payguard.core.engine;

import com.payguard.core.result.FindingType;

/**
 * What a cell of the 80-cell state-pair table yields.
 *
 * <p>Every cell carries one of these three; none is blank. Revision 0.2 had eleven cells marked
 * "handled by a later rule", which is not a specification.
 */
public sealed interface TableOutcome {

    /** The cell names a finding outright. */
    record Fixed(FindingType type) implements TableOutcome {}

    /**
     * The two sides agree on state, so there is no status finding: proceed to the amount comparison
     * and then MATCHED. Two cells only — CAPTURED with PAID_UNFULFILLED or FULFILLED.
     */
    record Agreement() implements TableOutcome {}

    /**
     * Conditional on age against a stale threshold: {@code below} under it, {@code atOrAbove} at or
     * over it. Fourteen cells use this.
     */
    record ThresholdConditional(FindingType below, FindingType atOrAbove, Threshold threshold)
            implements TableOutcome {}

    /** Which threshold a conditional cell compares against. The two differ for different reasons. */
    enum Threshold {
        /** Funds reserved on a customer's card and never captured. */
        AUTHORIZATION,
        /** The gateway never finalised — typically PayTR never received an {@code OK}. */
        IN_PROGRESS
    }
}
