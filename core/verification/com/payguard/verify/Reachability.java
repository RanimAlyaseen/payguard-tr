package com.payguard.verify;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.RecordKind;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.result.FindingType;
import com.payguard.core.spec.SpecFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/**
 * Which finding types the specification suite actually produces.
 *
 * <p>This is reachability, not code coverage. It answers "does every declared outcome occur in at
 * least one test?" by running the suite's own scenarios through the public API and collecting the
 * types that come out. It cannot tell you which lines or branches ran — only JaCoCo can, and these
 * numbers must never be presented as coverage.
 */
public final class Reachability {

    public static void main(String[] args) throws Exception {
        ReconciliationEngine engine = new ReconciliationEngine();
        EnumSet<FindingType> reached = EnumSet.noneOf(FindingType.class);

        // Every table cell at an hour old.
        for (PaymentStatus p : PaymentStatus.values()) {
            for (OrderStatus o : OrderStatus.values()) {
                reached.add(engine.reconcile(SpecFixtures.tableCase(p, o)).type());
            }
        }

        // Threshold branches, both sides.
        for (PaymentStatus p : List.of(PaymentStatus.AUTHORIZED, PaymentStatus.IN_PROGRESS)) {
            Duration threshold =
                    p == PaymentStatus.AUTHORIZED
                            ? SpecFixtures.policy().staleAuthorizationThreshold()
                            : SpecFixtures.policy().staleInProgressThreshold();
            for (Duration age : List.of(threshold.minusMillis(1), threshold)) {
                Instant at = SpecFixtures.NOW.minus(age);
                for (OrderStatus o : OrderStatus.values()) {
                    reached.add(
                            engine.reconcile(
                                            SpecFixtures.input(
                                                    SpecFixtures.payment(p, at),
                                                    SpecFixtures.order(o, at)))
                                    .type());
                }
            }
        }

        // Grace, currency, duplicate, conflict, amount, contradiction.
        Instant recent = SpecFixtures.NOW.minusSeconds(5);
        reached.add(
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED, recent),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, recent)))
                        .type());
        reached.add(
                engine.reconcile(
                                SpecFixtures.input(
                                        new com.payguard.core.model.PaymentObservation(
                                                "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                                                SpecFixtures.eur(1), SpecFixtures.eur(1), null, 0,
                                                SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                        .type());
        reached.add(
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        List.of(
                                                SpecFixtures.capture("a", 1),
                                                SpecFixtures.capture("b", 1))))
                        .type());
        reached.add(
                engine.reconcile(
                                SpecFixtures.input(
                                        new com.payguard.core.model.PaymentObservation(
                                                "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                                                SpecFixtures.try_(999_999), SpecFixtures.try_(999_999),
                                                null, 0, SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                        .type());
        TransactionRecord refund =
                SpecFixtures.record("f", RecordKind.REFUND, 1, SpecFixtures.ONE_HOUR_AGO, 1L);
        reached.add(
                engine.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.PARTIALLY_REFUNDED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        List.of(refund)))
                        .type());

        EnumSet<FindingType> unreached = EnumSet.allOf(FindingType.class);
        unreached.removeAll(reached);

        System.out.println("Finding-type reachability (NOT code coverage)");
        System.out.println("-".repeat(60));
        System.out.println("declared finding types : " + FindingType.values().length);
        System.out.println("reached by the suite   : " + reached.size());
        System.out.println("never reached          : " + unreached.size());
        for (FindingType type : reached) {
            System.out.println("  reached    " + type);
        }
        for (FindingType type : unreached) {
            System.out.println("  UNREACHED  " + type);
        }
    }
}
