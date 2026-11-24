package com.payguard.core.result;

import com.payguard.core.model.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exactly one verdict about one payment/order pair.
 *
 * <p>{@code amountAtRisk} is {@link Optional}: <strong>present</strong> means a computed exposure,
 * which may legitimately be zero (a fully refunded orphan payment); <strong>absent</strong> means
 * PayGuard cannot compute one. An absent amount never lowers severity (INV-08).
 *
 * <p>{@code decisionPath} records which gates were passed and which rule fired, so a finding can be
 * explained to a merchant and audited later, and so a test can assert on the reasoning rather than
 * only on the verdict.
 */
public record ReconciliationFinding(
        FindingType type,
        Severity severity,
        RecommendedAction recommendedAction,
        Optional<Money> amountAtRisk,
        List<String> reasons,
        List<String> decisionPath,
        String policyVersion) {

    public ReconciliationFinding {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(recommendedAction, "recommendedAction");
        Objects.requireNonNull(amountAtRisk, "amountAtRisk");
        reasons = List.copyOf(reasons);
        decisionPath = List.copyOf(decisionPath);
        Objects.requireNonNull(policyVersion, "policyVersion");
    }

    /** Builds a finding with severity and action derived from the type alone (INV-08). */
    public static ReconciliationFinding of(
            FindingType type,
            Optional<Money> amountAtRisk,
            List<String> reasons,
            List<String> decisionPath,
            String policyVersion) {
        return new ReconciliationFinding(
                type,
                FindingCatalog.severityOf(type),
                FindingCatalog.actionOf(type),
                amountAtRisk,
                reasons,
                decisionPath,
                policyVersion);
    }

    public boolean hasAmountAtRisk() {
        return amountAtRisk.isPresent();
    }
}
