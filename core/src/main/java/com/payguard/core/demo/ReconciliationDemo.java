package com.payguard.core.demo;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.engine.ReconciliationInput;
import com.payguard.core.error.PayGuardCoreException;
import com.payguard.core.model.Currency;
import com.payguard.core.model.Money;
import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.RecordKind;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.policy.ReconciliationPolicy;
import com.payguard.core.result.ReconciliationFinding;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The independently executable entry point for the Scope A deliverable.
 *
 * <p>Runs a handful of hard-coded scenarios and prints each verdict. It reads nothing and writes
 * only to standard output: no framework, no dependency, no database, no network, no file I/O, no
 * argument parsing. The core module therefore still declares zero runtime dependencies (INV-10).
 *
 * <p>Run with: {@code mvn -q compile exec:java} or
 * {@code java -cp target/classes com.payguard.core.demo.ReconciliationDemo}
 */
public final class ReconciliationDemo {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final Instant AN_HOUR_AGO = NOW.minus(Duration.ofHours(1));
    private static final Instant FIVE_SECONDS_AGO = NOW.minusSeconds(5);
    private static final String REF = "ORD-1001";

    private static final ReconciliationEngine ENGINE = new ReconciliationEngine();
    private static int scenarioNumber;

    private ReconciliationDemo() {}

    public static void main(String[] args) throws PayGuardCoreException {
        System.out.println("PayGuard TR - reconciliation core demonstration");
        System.out.println("evaluatedAt = " + NOW + " (injected, never read from a clock)");
        System.out.println("=".repeat(78));

        cleanMatch();
        paymentWithoutOrder();
        orderUnpaidDespitePayment();
        legitimateInstalmentExcess();
        duplicateCapture();
        conflictingSameIdentityRecords();
        historyContradictsQueriedStatus();
        withinGracePeriod();

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println(scenarioNumber + " scenarios evaluated.");
    }

    /** Everything agrees, amounts included. */
    private static void cleanMatch() throws PayGuardCoreException {
        run(
                "Clean match",
                "The gateway captured 1,250.00 TRY and the shop has a paid, unfulfilled order for the same amount.",
                input(
                        payment(PaymentStatus.CAPTURED, 125_000, 125_000, 0, null, AN_HOUR_AGO),
                        order(OrderStatus.PAID_UNFULFILLED, 125_000, AN_HOUR_AGO),
                        List.of()));
    }

    /** The scenario the product is named for, and the one only gateway-first discovery can find. */
    private static void paymentWithoutOrder() throws PayGuardCoreException {
        run(
                "Payment without order",
                "A capture exists at the gateway; the shop has no order for that reference at all. "
                        + "Note the absent amount at risk: no refund figure is available, so the net "
                        + "exposure is not computable (DEF-001) - and severity stays HIGH regardless.",
                input(
                        payment(PaymentStatus.CAPTURED, 125_000, 125_000, 0, null, AN_HOUR_AGO),
                        OrderObservation.absent(REF, NOW),
                        List.of()));
    }

    /** Money taken, order still unpaid: the classic missed-callback consequence. */
    private static void orderUnpaidDespitePayment() throws PayGuardCoreException {
        run(
                "Paid payment, unpaid order",
                "The customer was charged 1,250.00 TRY an hour ago and the order is still awaiting payment.",
                input(
                        payment(PaymentStatus.CAPTURED, 125_000, 125_000, 0, null, AN_HOUR_AGO),
                        order(OrderStatus.AWAITING_PAYMENT, 125_000, AN_HOUR_AGO),
                        List.of()));
    }

    /** The rule that came out of reading PayTR's documentation before writing any code. */
    private static void legitimateInstalmentExcess() throws PayGuardCoreException {
        run(
                "Legitimate instalment excess",
                "Requested 1,250.00 but collected 1,312.50 across 3 instalments. PayTR documents that the "
                        + "collected total can exceed the requested amount, so this must NOT be flagged.",
                input(
                        payment(PaymentStatus.CAPTURED, 125_000, 131_250, 3, null, AN_HOUR_AGO),
                        order(OrderStatus.PAID_UNFULFILLED, 125_000, AN_HOUR_AGO),
                        List.of()));
    }

    /** Two distinct-identity capture rows: the customer was charged twice. */
    private static void duplicateCapture() throws PayGuardCoreException {
        run(
                "Duplicate capture",
                "Two distinct capture rows in the gateway's transaction history for one order reference.",
                input(
                        payment(PaymentStatus.CAPTURED, 125_000, 125_000, 0, null, AN_HOUR_AGO),
                        order(OrderStatus.PAID_UNFULFILLED, 125_000, AN_HOUR_AGO),
                        List.of(
                                record("txn-1", RecordKind.CAPTURE, 125_000, AN_HOUR_AGO, 1L),
                                record("txn-2", RecordKind.CAPTURE, 125_000, AN_HOUR_AGO.plusSeconds(90), 2L))));
    }

    /** Two rows the provider claims are the same event, with different content. */
    private static void conflictingSameIdentityRecords() throws PayGuardCoreException {
        run(
                "Conflicting same-identity records",
                "One recordIdentity, two different amounts. There is no defensible basis for choosing "
                        + "between them, so PayGuard reports that it cannot tell rather than picking one.",
                input(
                        payment(PaymentStatus.CAPTURED, 125_000, 125_000, 0, null, AN_HOUR_AGO),
                        order(OrderStatus.PAID_UNFULFILLED, 125_000, AN_HOUR_AGO),
                        List.of(
                                record("txn-9", RecordKind.CAPTURE, 125_000, AN_HOUR_AGO, 1L),
                                record("txn-9", RecordKind.CAPTURE, 999_999, AN_HOUR_AGO, 2L))));
    }

    /** Two endpoints of one provider disagreeing. Neither wins. */
    private static void historyContradictsQueriedStatus() throws PayGuardCoreException {
        run(
                "Queried status contradicted by history",
                "The status-inquiry API says the payment FAILED, while the gateway's own transaction "
                        + "history lists a capture. History never overrides the queried status (INV-13), "
                        + "and the status does not silently win either.",
                input(
                        payment(PaymentStatus.FAILED, 125_000, 125_000, 0, null, AN_HOUR_AGO),
                        order(OrderStatus.AWAITING_PAYMENT, 125_000, AN_HOUR_AGO),
                        List.of(record("txn-5", RecordKind.CAPTURE, 125_000, AN_HOUR_AGO, 1L))));
    }

    /** The same disagreement, five seconds old instead of an hour. */
    private static void withinGracePeriod() throws PayGuardCoreException {
        run(
                "Within grace period",
                "Identical to 'paid payment, unpaid order' but only 5 seconds old. The two systems are "
                        + "eventually consistent, so this is recorded as observed rather than reported. "
                        + "Age is measured from domain time only, so re-polling cannot restart the window.",
                input(
                        payment(PaymentStatus.CAPTURED, 125_000, 125_000, 0, null, FIVE_SECONDS_AGO),
                        order(OrderStatus.AWAITING_PAYMENT, 125_000, FIVE_SECONDS_AGO),
                        List.of()));
    }

    // ---------------------------------------------------------------- helpers

    private static void run(String title, String narrative, ReconciliationInput input)
            throws PayGuardCoreException {
        scenarioNumber++;
        ReconciliationFinding finding = ENGINE.reconcile(input);

        System.out.println();
        System.out.println(scenarioNumber + ". " + title);
        System.out.println("   " + wrap(narrative));
        System.out.println("   " + "-".repeat(72));
        System.out.println("   finding       : " + finding.type());
        System.out.println("   severity      : " + finding.severity());
        System.out.println("   action        : " + finding.recommendedAction());
        System.out.println(
                "   amountAtRisk  : "
                        + finding.amountAtRisk()
                                .map(Money::toString)
                                .orElse("(absent)"));
        System.out.println("   reasons       : " + (finding.reasons().isEmpty() ? "(none)" : ""));
        for (String reason : finding.reasons()) {
            System.out.println("                   - " + reason);
        }
        System.out.println("   decisionPath  :");
        for (String step : finding.decisionPath()) {
            System.out.println("                   " + step);
        }
    }

    private static String wrap(String text) {
        StringBuilder out = new StringBuilder();
        int lineLength = 0;
        for (String word : text.split(" ")) {
            if (lineLength + word.length() > 70) {
                out.append(System.lineSeparator()).append("   ");
                lineLength = 0;
            }
            out.append(word).append(' ');
            lineLength += word.length() + 1;
        }
        return out.toString().stripTrailing();
    }

    private static PaymentObservation payment(
            PaymentStatus status,
            long requestedMinor,
            long collectedMinor,
            int installments,
            Long refundedMinor,
            Instant occurredAt)
            throws PayGuardCoreException {
        return new PaymentObservation(
                "GW-77",
                REF,
                status,
                Money.of(requestedMinor, Currency.TRY),
                Money.of(collectedMinor, Currency.TRY),
                refundedMinor == null ? null : Money.of(refundedMinor, Currency.TRY),
                installments,
                occurredAt,
                NOW,
                false);
    }

    private static OrderObservation order(OrderStatus status, long totalMinor, Instant createdAt)
            throws PayGuardCoreException {
        return OrderObservation.of(
                REF, status, Money.of(totalMinor, Currency.TRY), createdAt, createdAt, NOW);
    }

    private static TransactionRecord record(
            String identity, RecordKind kind, long amountMinor, Instant occurredAt, long sequence)
            throws PayGuardCoreException {
        return new TransactionRecord(
                identity, REF, kind, Money.of(amountMinor, Currency.TRY), occurredAt, sequence);
    }

    private static ReconciliationInput input(
            PaymentObservation payment, OrderObservation order, List<TransactionRecord> history) {
        return new ReconciliationInput(
                payment, order, history, ReconciliationPolicy.defaults(), NOW);
    }
}
