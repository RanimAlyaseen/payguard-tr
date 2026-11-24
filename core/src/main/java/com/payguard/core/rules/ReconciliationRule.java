package com.payguard.core.rules;

import com.payguard.core.error.PayGuardCoreException;
import com.payguard.core.result.ReconciliationFinding;
import java.util.Optional;

/**
 * One entry of the priority chain in requirements §4.1.
 *
 * <p>Returns empty when the rule does not apply; it never throws for a non-applicable case.
 */
public interface ReconciliationRule {

    /** A short name for the decision path. */
    String name();

    Optional<ReconciliationFinding> evaluate(RuleContext context) throws PayGuardCoreException;
}
