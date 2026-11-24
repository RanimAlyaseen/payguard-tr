package com.payguard.core.spec;

import com.payguard.core.engine.ReconciliationInput;
import com.payguard.core.error.InvalidReconciliationInputException;
import com.payguard.core.model.Currency;
import com.payguard.core.model.Money;
import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.RecordKind;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.policy.ReconciliationPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Input builders shared by the JUnit 5 suite and the dependency-free verification harness.
 *
 * <p>No test-framework imports, deliberately: both runners consume the same fixtures, so the two
 * exercise identical inputs.
 *
 * <p>Every instant here is a fixed literal. Nothing reads a clock, which is what makes the boundary
 * cases exact rather than racy (INV-02).
 */
public final class SpecFixtures {

    /** The evaluation instant for every fixture that does not override it. */
    public static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    /** Comfortably outside the 15-minute default grace, inside both stale thresholds. */
    public static final Instant ONE_HOUR_AGO = NOW.minus(Duration.ofHours(1));

    public static final long AMOUNT = 125_000L;
    public static final String ORDER_REF = "ORD-1001";

    private SpecFixtures() {}

    public static ReconciliationPolicy policy() {
        return ReconciliationPolicy.defaults();
    }

    public static Money try_(long minor) throws InvalidReconciliationInputException {
        return Money.of(minor, Currency.TRY);
    }

    public static Money eur(long minor) throws InvalidReconciliationInputException {
        return Money.of(minor, Currency.EUR);
    }

    /** A payment carrying amounts, with its transaction time an hour before evaluation. */
    public static PaymentObservation payment(PaymentStatus status)
            throws InvalidReconciliationInputException {
        return payment(status, ONE_HOUR_AGO);
    }

    public static PaymentObservation payment(PaymentStatus status, Instant occurredAt)
            throws InvalidReconciliationInputException {
        if (status == PaymentStatus.ABSENT) {
            return PaymentObservation.absent(ORDER_REF, NOW);
        }
        Money refunded =
                switch (status) {
                    case REFUNDED -> try_(AMOUNT);
                    case PARTIALLY_REFUNDED -> try_(AMOUNT / 2);
                    default -> null;
                };
        return new PaymentObservation(
                "GW-1", ORDER_REF, status, try_(AMOUNT), try_(AMOUNT), refunded, 0, occurredAt, NOW,
                false);
    }

    /** A payment with explicit amounts and instalment count, for the FR-02 partitions. */
    public static PaymentObservation payment(
            PaymentStatus status, long requestedMinor, long collectedMinor, int installments)
            throws InvalidReconciliationInputException {
        return new PaymentObservation(
                "GW-1",
                ORDER_REF,
                status,
                try_(requestedMinor),
                try_(collectedMinor),
                null,
                installments,
                ONE_HOUR_AGO,
                NOW,
                false);
    }

    /** An order whose business-state time is its creation time, per BR-07b. */
    public static OrderObservation order(OrderStatus status)
            throws InvalidReconciliationInputException {
        return order(status, ONE_HOUR_AGO);
    }

    public static OrderObservation order(OrderStatus status, Instant createdAt)
            throws InvalidReconciliationInputException {
        if (status == OrderStatus.ABSENT) {
            return OrderObservation.absent(ORDER_REF, NOW);
        }
        return OrderObservation.of(ORDER_REF, status, try_(AMOUNT), createdAt, createdAt, NOW);
    }

    public static OrderObservation order(OrderStatus status, long totalMinor)
            throws InvalidReconciliationInputException {
        return OrderObservation.of(
                ORDER_REF, status, try_(totalMinor), ONE_HOUR_AGO, ONE_HOUR_AGO, NOW);
    }

    public static TransactionRecord record(
            String identity, RecordKind kind, long amountMinor, Instant occurredAt, long sequence)
            throws InvalidReconciliationInputException {
        return new TransactionRecord(
                identity, ORDER_REF, kind, try_(amountMinor), occurredAt, sequence);
    }

    public static TransactionRecord capture(String identity, long amountMinor)
            throws InvalidReconciliationInputException {
        return record(identity, RecordKind.CAPTURE, amountMinor, ONE_HOUR_AGO, 1L);
    }

    public static ReconciliationInput input(PaymentObservation payment, OrderObservation order) {
        return new ReconciliationInput(payment, order, List.of(), policy(), NOW);
    }

    public static ReconciliationInput input(
            PaymentObservation payment, OrderObservation order, List<TransactionRecord> history) {
        return new ReconciliationInput(payment, order, history, policy(), NOW);
    }

    public static ReconciliationInput input(
            PaymentObservation payment,
            OrderObservation order,
            List<TransactionRecord> history,
            ReconciliationPolicy policy,
            Instant evaluatedAt) {
        return new ReconciliationInput(payment, order, history, policy, evaluatedAt);
    }

    /** A pair that reaches the decision table: past grace, inside both stale thresholds. */
    public static ReconciliationInput tableCase(PaymentStatus payment, OrderStatus order)
            throws InvalidReconciliationInputException {
        return input(payment(payment), order(order));
    }
}
