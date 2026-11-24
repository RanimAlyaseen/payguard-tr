package com.payguard.core.result;

import java.util.EnumMap;
import java.util.Map;

/**
 * Severity and recommended action per {@link FindingType}, from requirements §4.4.
 *
 * <p><strong>Severity is a function of the type alone</strong> (INV-08). No amount influences it.
 * Revision 0.2 let a zero {@code amountAtRisk} demote a HIGH finding, which was unsafe because zero
 * meant both "no exposure" and "not computable" — so REFUND_DIVERGENCE(shop_only), where the
 * customer may still be owed money, was demoted precisely when it mattered. The two meanings are now
 * distinguished by {@code Optional} on the amount, and the amount no longer touches severity.
 */
public final class FindingCatalog {

    private static final Map<FindingType, Severity> SEVERITY = new EnumMap<>(FindingType.class);
    private static final Map<FindingType, RecommendedAction> ACTION =
            new EnumMap<>(FindingType.class);

    static {
        put(FindingType.MATCHED, Severity.INFO, RecommendedAction.NONE);
        put(FindingType.WITHIN_GRACE_PERIOD, Severity.INFO, RecommendedAction.OBSERVE);
        put(FindingType.AUTHORIZATION_PENDING, Severity.INFO, RecommendedAction.OBSERVE);
        put(FindingType.IN_PROGRESS_PENDING, Severity.INFO, RecommendedAction.OBSERVE);
        put(FindingType.INSUFFICIENT_DATA, Severity.MEDIUM, RecommendedAction.MANUAL_INVESTIGATION);
        put(FindingType.STALE_AUTHORIZATION, Severity.MEDIUM, RecommendedAction.ALERT_MERCHANT);
        put(FindingType.STALE_IN_PROGRESS, Severity.MEDIUM, RecommendedAction.ALERT_MERCHANT);
        put(FindingType.AMOUNT_MISMATCH, Severity.MEDIUM, RecommendedAction.ALERT_MERCHANT);
        put(FindingType.STATUS_CONFLICT, Severity.MEDIUM, RecommendedAction.MANUAL_INVESTIGATION);
        put(FindingType.PAYMENT_WITHOUT_ORDER, Severity.HIGH, RecommendedAction.MANUAL_INVESTIGATION);
        put(FindingType.ORDER_UNPAID_DESPITE_PAYMENT, Severity.HIGH, RecommendedAction.ALERT_MERCHANT);
        put(FindingType.PAYMENT_AGAINST_CANCELLED_ORDER, Severity.HIGH, RecommendedAction.MANUAL_INVESTIGATION);
        put(FindingType.ORDER_PAID_WITHOUT_PAYMENT, Severity.HIGH, RecommendedAction.MANUAL_INVESTIGATION);
        put(FindingType.REFUND_DIVERGENCE, Severity.HIGH, RecommendedAction.MANUAL_INVESTIGATION);
        put(FindingType.CURRENCY_MISMATCH, Severity.HIGH, RecommendedAction.MANUAL_INVESTIGATION);
        put(FindingType.DUPLICATE_PAYMENT, Severity.HIGH, RecommendedAction.MANUAL_INVESTIGATION);
    }

    private FindingCatalog() {}

    private static void put(FindingType t, Severity s, RecommendedAction a) {
        SEVERITY.put(t, s);
        ACTION.put(t, a);
    }

    public static Severity severityOf(FindingType type) {
        Severity s = SEVERITY.get(type);
        if (s == null) {
            throw new IllegalStateException("no severity mapped for " + type);
        }
        return s;
    }

    public static RecommendedAction actionOf(FindingType type) {
        RecommendedAction a = ACTION.get(type);
        if (a == null) {
            throw new IllegalStateException("no action mapped for " + type);
        }
        return a;
    }
}
