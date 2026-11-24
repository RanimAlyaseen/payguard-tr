package com.payguard.core.policy;

import com.payguard.core.error.InvalidReconciliationInputException;
import java.time.Duration;

/**
 * The tunable thresholds, versioned because they are guesses that pilot data will change (BR-05).
 *
 * <p><strong>There is no grace ceiling.</strong> Revision 0.2 had one, keyed on a finding key that
 * included the FindingType, so a discrepancy that changed type restarted it. The guarantee also
 * needed caller-held state that a pure core does not have. It was removed (FR-04.3): in Scope A
 * {@code businessStateChangedAt} is the order's creation time, which never advances, so the window
 * cannot be restarted by anything and the ceiling protects against nothing.
 */
public record ReconciliationPolicy(
        Duration gracePeriod,
        Duration staleInProgressThreshold,
        Duration staleAuthorizationThreshold,
        Duration clockSkewTolerance,
        long amountToleranceMinorUnits,
        boolean allowInstallmentExcess,
        String version) {

    public ReconciliationPolicy {
        // DEF-003. Previously Objects.requireNonNull, which raised a raw NPE from a public
        // constructor. FR-08 requires a typed, field-naming error instead; the checks now live
        // in validated(), which the engine calls at the reconciliation boundary.
    }

    /** Defaults from BR-05. Every value is an assumption to be tuned from pilot data. */
    public static ReconciliationPolicy defaults() {
        return new ReconciliationPolicy(
                Duration.ofMinutes(15),
                Duration.ofHours(24),
                Duration.ofHours(72),
                Duration.ofMinutes(5),
                0L,
                true,
                "2026.11.1");
    }

    public ReconciliationPolicy validated() throws InvalidReconciliationInputException {
        requirePresent(gracePeriod, "policy.gracePeriod");
        requirePresent(staleInProgressThreshold, "policy.staleInProgressThreshold");
        requirePresent(staleAuthorizationThreshold, "policy.staleAuthorizationThreshold");
        requirePresent(clockSkewTolerance, "policy.clockSkewTolerance");
        requirePresent(version, "policy.version");
        if (gracePeriod.isNegative() || gracePeriod.isZero()) {
            throw new InvalidReconciliationInputException(
                    "policy.gracePeriod", "must be positive but was " + gracePeriod);
        }
        if (staleInProgressThreshold.isNegative()) {
            throw new InvalidReconciliationInputException(
                    "policy.staleInProgressThreshold", "must not be negative");
        }
        if (staleAuthorizationThreshold.isNegative()) {
            throw new InvalidReconciliationInputException(
                    "policy.staleAuthorizationThreshold", "must not be negative");
        }
        if (clockSkewTolerance.isNegative()) {
            throw new InvalidReconciliationInputException(
                    "policy.clockSkewTolerance", "must not be negative");
        }
        if (amountToleranceMinorUnits < 0) {
            throw new InvalidReconciliationInputException(
                    "policy.amountToleranceMinorUnits",
                    "must not be negative but was " + amountToleranceMinorUnits);
        }
        return this;
    }

    public ReconciliationPolicy withGracePeriod(Duration d) {
        return new ReconciliationPolicy(
                d, staleInProgressThreshold, staleAuthorizationThreshold, clockSkewTolerance,
                amountToleranceMinorUnits, allowInstallmentExcess, version);
    }

    public ReconciliationPolicy withAmountTolerance(long minorUnits) {
        return new ReconciliationPolicy(
                gracePeriod, staleInProgressThreshold, staleAuthorizationThreshold,
                clockSkewTolerance, minorUnits, allowInstallmentExcess, version);
    }

    public ReconciliationPolicy withAllowInstallmentExcess(boolean allow) {
        return new ReconciliationPolicy(
                gracePeriod, staleInProgressThreshold, staleAuthorizationThreshold,
                clockSkewTolerance, amountToleranceMinorUnits, allow, version);
    }

    public ReconciliationPolicy withClockSkewTolerance(Duration d) {
        return new ReconciliationPolicy(
                gracePeriod, staleInProgressThreshold, staleAuthorizationThreshold, d,
                amountToleranceMinorUnits, allowInstallmentExcess, version);
    }

    private static void requirePresent(Object value, String field)
            throws InvalidReconciliationInputException {
        if (value == null) {
            throw new InvalidReconciliationInputException(field, "must not be null");
        }
    }
}
