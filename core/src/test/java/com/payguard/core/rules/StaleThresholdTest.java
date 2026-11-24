package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.spec.SpecFixtures;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-04.1: the two threshold-conditional branches, at their boundaries.
 *
 * <p>An authorisation that is 71 hours old is pending; at 72 hours it is stale and the merchant may
 * be about to lose the hold entirely. The boundary is where the finding changes severity, so it is
 * tested at the threshold and one millisecond either side of it.
 */
class StaleThresholdTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("the authorisation threshold at 72 hours")
    void authorizationThreshold() throws Exception {
        Duration threshold = SpecFixtures.policy().staleAuthorizationThreshold();
        assertEquals(
                FindingType.AUTHORIZATION_PENDING,
                at(PaymentStatus.AUTHORIZED, threshold.minusMillis(1)));
        assertEquals(FindingType.STALE_AUTHORIZATION, at(PaymentStatus.AUTHORIZED, threshold));
        assertEquals(
                FindingType.STALE_AUTHORIZATION, at(PaymentStatus.AUTHORIZED, threshold.plusMillis(1)));
    }

    @Test
    @DisplayName("the in-progress threshold at 24 hours")
    void inProgressThreshold() throws Exception {
        Duration threshold = SpecFixtures.policy().staleInProgressThreshold();
        assertEquals(
                FindingType.IN_PROGRESS_PENDING, at(PaymentStatus.IN_PROGRESS, threshold.minusMillis(1)));
        assertEquals(FindingType.STALE_IN_PROGRESS, at(PaymentStatus.IN_PROGRESS, threshold));
        assertEquals(
                FindingType.STALE_IN_PROGRESS, at(PaymentStatus.IN_PROGRESS, threshold.plusMillis(1)));
    }

    private FindingType at(PaymentStatus status, Duration age) throws Exception {
        Instant reference = SpecFixtures.NOW.minus(age);
        return engine.reconcile(
                        SpecFixtures.input(
                                SpecFixtures.payment(status, reference),
                                SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, reference)))
                .type();
    }
}
