package com.payguard.core.result;

/**
 * What PayGuard suggests a human do.
 *
 * <p>There is deliberately no {@code AUTO_REPAIR}: a repair cannot be represented, let alone
 * executed, so no classification bug can move money (ADR-013).
 */
public enum RecommendedAction {
    NONE,
    OBSERVE,
    ALERT_MERCHANT,
    MANUAL_INVESTIGATION
}
