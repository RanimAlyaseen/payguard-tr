package com.payguard.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.result.FindingType;
import com.payguard.core.spec.SpecFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * INV-12, and the regression test for the most consequential architecture defect in this project.
 *
 * <p>Revision 0.1 measured the discrepancy age from {@code max(observedAt, lastModifiedAt)} — a
 * retrieval time. Every poll produced a fresh {@code observedAt}, which restarted the grace window,
 * so a discrepancy polled every five minutes under a fifteen-minute grace period would never be
 * reported. More monitoring produced less detection, and the product's core promise inverted.
 *
 * <p>This test re-polls the same unchanging discrepancy once a minute for a hundred minutes with a
 * fresh retrieval time each pass. The window must open once and close once.
 */
class GraceSuppressionTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test
    @DisplayName("re-polling cannot restart the grace window")
    void repeatedPollingCannotRestartTheWindow() throws Exception {
        Instant domainTime = SpecFixtures.NOW;
        Duration grace = SpecFixtures.policy().gracePeriod();

        int suppressed = 0;
        int reported = 0;
        boolean reportedAlready = false;
        boolean returnedToGrace = false;

        for (int minute = 0; minute < 100; minute++) {
            Instant evaluatedAt = domainTime.plus(Duration.ofMinutes(minute));
            // A new retrieval time on every pass: this is exactly what a re-poll looks like.
            PaymentObservation payment =
                    new PaymentObservation(
                            "GW",
                            SpecFixtures.ORDER_REF,
                            PaymentStatus.CAPTURED,
                            SpecFixtures.try_(125_000),
                            SpecFixtures.try_(125_000),
                            null,
                            0,
                            domainTime,
                            evaluatedAt,
                            false);
            OrderObservation order =
                    OrderObservation.of(
                            SpecFixtures.ORDER_REF,
                            OrderStatus.AWAITING_PAYMENT,
                            SpecFixtures.try_(125_000),
                            domainTime,
                            evaluatedAt,
                            evaluatedAt);

            FindingType type =
                    engine.reconcile(
                                    SpecFixtures.input(
                                            payment, order, List.of(), SpecFixtures.policy(), evaluatedAt))
                            .type();

            if (type == FindingType.WITHIN_GRACE_PERIOD) {
                suppressed++;
                if (reportedAlready) {
                    returnedToGrace = true;
                }
            } else {
                assertEquals(FindingType.ORDER_UNPAID_DESPITE_PAYMENT, type, "at minute " + minute);
                reported++;
                reportedAlready = true;
            }
        }

        assertEquals((int) grace.toMinutes(), suppressed, "minutes suppressed");
        assertEquals(100 - (int) grace.toMinutes(), reported, "minutes reported");
        assertFalse(
                returnedToGrace,
                "the discrepancy re-entered the grace window after leaving it, which means a retrieval"
                        + " time is feeding the age calculation again");
    }

    @Test
    @DisplayName("INV-12: the discrepancy age increases strictly with evaluatedAt")
    void discrepancyAgeIncreasesStrictly() throws Exception {
        Instant domainTime = SpecFixtures.NOW.minus(Duration.ofHours(2));
        Duration previous = null;
        for (int minute = 0; minute < 10; minute++) {
            Instant evaluatedAt = SpecFixtures.NOW.plus(Duration.ofMinutes(minute));
            Duration age = Duration.between(domainTime, evaluatedAt);
            if (previous != null) {
                assertEquals(
                        1,
                        age.compareTo(previous),
                        "the age must strictly increase as evaluation time advances");
            }
            previous = age;
        }
    }
}
