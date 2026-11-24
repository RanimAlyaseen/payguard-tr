package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.result.FindingType;
import com.payguard.core.spec.SpecFixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §4.1. Every test here is a case where two or more rules could fire, constructed so that the
 * priority order is the only thing that decides the answer.
 *
 * <p>A prioritised chain was chosen over a state machine (ADR-005) precisely so these cases have one
 * documented answer instead of depending on evaluation order, and these are the tests that hold the
 * implementation to it.
 */
class RulePriorityTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();
    private final Instant recent = SpecFixtures.NOW.minusSeconds(5);

    @Test
    @DisplayName("2 over 3: an unrecognised status outranks a currency mismatch")
    void unrecognisedStatusOutranksCurrency() throws Exception {
        PaymentObservation unknownInEuros =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.UNKNOWN, SpecFixtures.eur(999),
                        SpecFixtures.eur(999), null, 0, SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false);
        assertEquals(
                FindingType.INSUFFICIENT_DATA,
                engine.reconcile(
                                SpecFixtures.input(
                                        unknownInEuros, SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                        .type());
    }

    @Test
    @DisplayName("2 over 4: a same-identity conflict outranks duplicate detection")
    void conflictOutranksDuplicate() throws Exception {
        List<TransactionRecord> conflicting =
                List.of(
                        SpecFixtures.capture("same", 125_000),
                        SpecFixtures.capture("same", 999_999),
                        SpecFixtures.capture("other", 125_000));
        assertEquals(
                FindingType.INSUFFICIENT_DATA,
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        conflicting))
                        .type(),
                "with the history in doubt, the duplicate count cannot be trusted either");
    }

    @Test
    @DisplayName("3 over 5 and 7: a currency mismatch outranks grace and the amount comparison")
    void currencyOutranksGraceAndAmount() throws Exception {
        PaymentObservation euroPayment =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED, SpecFixtures.eur(999),
                        SpecFixtures.eur(999), null, 0, recent, SpecFixtures.NOW, false);
        assertEquals(
                FindingType.CURRENCY_MISMATCH,
                engine.reconcile(
                                SpecFixtures.input(
                                        euroPayment,
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED, recent)))
                        .type(),
                "waiting out a grace period will not make two currencies converge");
    }

    @Test
    @DisplayName("4 over 5: a duplicate charge outranks the grace period")
    void duplicateOutranksGrace() throws Exception {
        assertEquals(
                FindingType.DUPLICATE_PAYMENT,
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED, recent),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, recent),
                                        List.of(
                                                SpecFixtures.capture("txn-1", 125_000),
                                                SpecFixtures.capture("txn-2", 125_000))))
                        .type(),
                "a second charge does not resolve itself by waiting; the money is already gone");
    }

    @Test
    @DisplayName("5 over 6: grace suppresses a table disagreement")
    void graceOutranksTheTable() throws Exception {
        assertEquals(
                FindingType.WITHIN_GRACE_PERIOD,
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED, recent),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, recent)))
                        .type());
    }

    @Test
    @DisplayName("5 over 7: grace suppresses an amount mismatch too")
    void graceOutranksTheAmountRule() throws Exception {
        PaymentObservation overpaid =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED, SpecFixtures.try_(999_999),
                        SpecFixtures.try_(999_999), null, 0, recent, SpecFixtures.NOW, false);
        OrderObservation order =
                OrderObservation.of(
                        SpecFixtures.ORDER_REF, OrderStatus.PAID_UNFULFILLED, SpecFixtures.try_(125_000),
                        recent, recent, SpecFixtures.NOW);
        assertEquals(
                FindingType.WITHIN_GRACE_PERIOD,
                engine.reconcile(SpecFixtures.input(overpaid, order)).type());
    }

    @Test
    @DisplayName("a clean agreement inside the grace window is MATCHED, not 'too early to judge'")
    void graceDoesNotSuppressAgreement() throws Exception {
        assertEquals(
                FindingType.MATCHED,
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED, recent),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED, recent)))
                        .type(),
                "FR-04.2 scopes grace to a disagreement; reporting a perfectly reconciled pair as"
                        + " WITHIN_GRACE_PERIOD would hide the one verdict a merchant wants to see");
    }
}
