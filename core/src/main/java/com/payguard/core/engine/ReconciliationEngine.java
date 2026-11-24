package com.payguard.core.engine;

import com.payguard.core.error.InvalidReconciliationInputException;
import com.payguard.core.error.PayGuardCoreException;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.rules.AmountComparisonRule;
import com.payguard.core.rules.CurrencyMismatchRule;
import com.payguard.core.rules.DecisionTableRule;
import com.payguard.core.rules.DuplicatePaymentRule;
import com.payguard.core.rules.GracePeriodRule;
import com.payguard.core.rules.InsufficientDataRule;
import com.payguard.core.rules.RuleContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Classifies one payment/order pair.
 *
 * <p><strong>Total over the valid domain</strong> (INV-01): every valid input returns exactly one
 * {@link FindingType}; malformed input lies outside that domain and raises a typed exception
 * (FR-08). <strong>Pure</strong> (INV-02): no clock, no I/O, no randomness — {@code evaluatedAt} is
 * injected. <strong>Non-mutating</strong> (INV-03).
 *
 * <p>Priority order from requirements §4.1. One nuance worth stating, because it is an
 * interpretation rather than a change: FR-04.2 scopes the grace period to "a disagreement", and
 * determining whether a disagreement exists requires the table. So the engine computes the candidate
 * finding first and then offers it to the grace rule for suppression. Grace still outranks both the
 * table and the amount rule — it suppresses either — but a clean MATCHED is never reported as "too
 * early to judge", which a literal top-down reading would have produced.
 */
public final class ReconciliationEngine {

    private final InsufficientDataRule insufficientData = new InsufficientDataRule();
    private final CurrencyMismatchRule currencyMismatch = new CurrencyMismatchRule();
    private final DuplicatePaymentRule duplicatePayment = new DuplicatePaymentRule();
    private final GracePeriodRule gracePeriod = new GracePeriodRule();
    private final AmountComparisonRule amountComparison = new AmountComparisonRule();

    public ReconciliationFinding reconcile(ReconciliationInput input) throws PayGuardCoreException {
        validate(input);

        NormalizedHistory history = RecordNormalizer.normalize(input.history());
        RuleContext ctx = new RuleContext(input, history);
        ctx.note("gate 1: input valid");
        if (history.collapsedRepetitions() > 0) {
            ctx.note(
                    "history: collapsed "
                            + history.collapsedRepetitions()
                            + " content-identical repetition(s)");
        }

        // Priority 2, 3, 4 — facts that will not change by waiting.
        Optional<ReconciliationFinding> gated = insufficientData.evaluate(ctx);
        if (gated.isPresent()) {
            return gated.get();
        }
        gated = currencyMismatch.evaluate(ctx);
        if (gated.isPresent()) {
            return gated.get();
        }
        gated = duplicatePayment.evaluate(ctx);
        if (gated.isPresent()) {
            return gated.get();
        }

        // Priority 6 then 7: establish the candidate. A fresh rule instance keeps the engine
        // reusable across calls without carrying state between them (INV-02).
        DecisionTableRule table = new DecisionTableRule();
        Optional<ReconciliationFinding> tableFinding = table.evaluate(ctx);
        ReconciliationFinding candidate;
        if (tableFinding.isPresent()) {
            candidate = tableFinding.get();
        } else if (table.lastWasAgreement()) {
            candidate = amountComparison.evaluate(ctx).orElseGet(() -> matched(ctx));
        } else {
            throw new IllegalStateException("decision table returned neither a finding nor agreement");
        }

        // Priority 5 — applied to the candidate, per the note above.
        Optional<ReconciliationFinding> suppressed = gracePeriod.suppress(ctx, candidate.type());
        return suppressed.orElse(candidate);
    }

    private static ReconciliationFinding matched(RuleContext ctx) {
        ctx.note("priority 8: MATCHED");
        return ReconciliationFinding.of(
                FindingType.MATCHED,
                Optional.empty(),
                ctx.reasons(),
                ctx.decisionPath(),
                ctx.input().policy().version());
    }

    /**
     * FR-08. Defines the boundary of the valid domain that INV-01's totality claim is scoped to.
     *
     * <p>Written without lambdas on purpose: {@link Optional#ifPresent} cannot propagate a checked
     * exception, and an earlier draft smuggled one out as a {@code RuntimeException}. That would
     * have let an untyped exception escape the module, which FR-08 forbids outright.
     */
    private static void validate(ReconciliationInput input)
            throws InvalidReconciliationInputException {
        if (input == null) {
            throw new InvalidReconciliationInputException("input", "must not be null");
        }
        // DEF-003. Every required component is checked here, with the field named, because FR-08
        // forbids an untyped exception leaving the module. Null history is malformed; empty is not.
        requirePresent(input.payment(), "input.payment");
        requirePresent(input.order(), "input.order");
        requirePresent(input.policy(), "input.policy");
        requirePresent(input.evaluatedAt(), "input.evaluatedAt");
        requirePresent(input.history(), "input.history");
        input.payment().validated();
        input.order().validated();
        input.policy().validated();

        Instant latestAllowed = input.evaluatedAt().plus(input.policy().clockSkewTolerance());

        Optional<Instant> transactionTime = input.payment().occurredAt();
        if (transactionTime.isPresent()) {
            requireNotAfter(transactionTime.get(), latestAllowed, "payment.transactionOccurredAt");
        }
        Optional<Instant> businessTime = input.order().businessStateChange();
        if (businessTime.isPresent()) {
            requireNotAfter(businessTime.get(), latestAllowed, "order.businessStateChangedAt");
        }
        for (TransactionRecord record : input.history()) {
            // DEF-003: a null element would otherwise raise a raw NPE from validated().
            requirePresent(record, "history[]");
            record.validated();
            requireNotAfter(record.occurredAt(), latestAllowed, "history.occurredAt");
        }
    }

    /**
     * A domain time beyond {@code evaluatedAt + clockSkewTolerance} is bad data, not something to
     * clamp: clock skew between a provider and PayGuard is an ordinary operational condition, so a
     * tolerance is allowed, but silently adjusting a timestamp would hide a real problem.
     */
    private static void requirePresent(Object value, String field)
            throws InvalidReconciliationInputException {
        if (value == null) {
            throw new InvalidReconciliationInputException(field, "must not be null");
        }
    }

    private static void requireNotAfter(Instant actual, Instant latestAllowed, String field)
            throws InvalidReconciliationInputException {
        if (actual.isAfter(latestAllowed)) {
            throw new InvalidReconciliationInputException(
                    field, "is " + actual + ", after the latest allowed " + latestAllowed);
        }
    }
}
