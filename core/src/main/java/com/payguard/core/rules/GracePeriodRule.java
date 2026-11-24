package com.payguard.core.rules;

import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import java.time.Duration;
import java.util.Optional;

/**
 * Priority 5. A disagreement younger than the grace period is reported as
 * {@code WITHIN_GRACE_PERIOD} rather than as a problem (FR-04.2).
 *
 * <p>The two systems are eventually consistent: iyzico's first webhook arrives 10-15 s after the
 * attempt and PayTR retries after a minute, so a disagreement seen seconds after checkout means
 * nothing.
 *
 * <p><strong>There is no ceiling</strong> (FR-04.3). It is unnecessary here because the reference
 * instant cannot move: in Scope A {@code businessStateChangedAt} is the order's creation time, so
 * the age is strictly increasing and a discrepancy leaves the window exactly once, never to return
 * (INV-12). Revision 0.2's ceiling also depended on episode state the pure core does not hold, so
 * its guarantee lived outside the module.
 *
 * <p><strong>Applies only to a disagreement.</strong> FR-04.2 scopes the rule to one, so the engine
 * determines the candidate finding first and offers it here for suppression; a clean MATCHED is never
 * reported as "too early to judge". Priority 5 still outranks the table and the amount rule, because
 * it suppresses both.
 */
public final class GracePeriodRule {

    /** @return a grace finding when the candidate should be suppressed, else empty */
    public Optional<ReconciliationFinding> suppress(RuleContext ctx, FindingType candidate) {
        if (candidate == FindingType.MATCHED) {
            ctx.note("gate 5: candidate is MATCHED; nothing to suppress");
            return Optional.empty();
        }

        Duration grace = ctx.input().policy().gracePeriod();
        Optional<Duration> age = ReferenceInstant.discrepancyAge(ctx.input());

        if (age.isEmpty()) {
            ctx.note("gate 5: no domain time available; grace cannot apply");
            return Optional.empty();
        }
        if (age.get().compareTo(grace) >= 0) {
            ctx.note("gate 5: age " + age.get() + " >= gracePeriod " + grace + "; evaluate normally");
            return Optional.empty();
        }

        ctx.reason("within_grace_period");
        ctx.reason("suppressed_candidate=" + candidate);
        ctx.reason("discrepancy_age=" + age.get());
        ctx.note(
                "gate 5: age "
                        + age.get()
                        + " < gracePeriod "
                        + grace
                        + "; suppressing "
                        + candidate);
        return Optional.of(
                ReconciliationFinding.of(
                        FindingType.WITHIN_GRACE_PERIOD,
                        Exposure.none(),
                        ctx.reasons(),
                        ctx.decisionPath(),
                        ctx.input().policy().version()));
    }
}
