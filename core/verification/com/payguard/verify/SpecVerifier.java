package com.payguard.verify;

import static com.payguard.verify.Harness.check;
import static com.payguard.verify.Harness.doesNotThrow;
import static com.payguard.verify.Harness.equal;
import static com.payguard.verify.Harness.group;
import static com.payguard.verify.Harness.throwsType;

import com.payguard.core.engine.DecisionTable;
import com.payguard.core.engine.NormalizedHistory;
import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.engine.ReconciliationInput;
import com.payguard.core.engine.RecordNormalizer;
import com.payguard.core.error.CurrencyMismatchException;
import com.payguard.core.error.InvalidReconciliationInputException;
import com.payguard.core.error.MoneyOverflowException;
import com.payguard.core.model.Currency;
import com.payguard.core.model.Money;
import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.model.RecordKind;
import com.payguard.core.model.TransactionRecord;
import com.payguard.core.policy.ReconciliationPolicy;
import com.payguard.core.result.FindingCatalog;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.result.Severity;
import com.payguard.core.spec.SpecDecisionTable;
import com.payguard.core.spec.SpecDecisionTable.SpecCell;
import com.payguard.core.spec.SpecFixtures;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Executes the specification against the implementation.
 *
 * <p>Group names match the JUnit 5 class names in {@code testing-strategy.md} §8, so the numbers
 * this prints map onto the canonical suite.
 */
public final class SpecVerifier {

    private static final ReconciliationEngine ENGINE = new ReconciliationEngine();

    public static void main(String[] args) throws Exception {
        System.out.println("PayGuard TR - specification verification");
        System.out.println("=".repeat(72));

        decisionTable();
        totality();
        rulePriority();
        authoritativeState();
        money();
        amountComparison();
        installmentAllowance();
        currencyRule();
        gracePeriod();
        graceSuppression();
        staleThresholds();
        duplicatePayment();
        refundDivergence();
        unknownStatus();
        recordIdentity();
        recordNormalizer();
        conflictingIdentity();
        repetitionInvariance();
        orderIndependence();
        severity();
        amountAtRisk();
        inputValidation();
        purity();
        timeSource();
        immutability();
        regressions();
        conditionalCellsAtThreshold();
        exposureEdges();
        graceOverAmount();
        historyIdentityEdges();

        System.exit(Harness.summarise());
    }

    /** All 80 cells, expected values transcribed from requirements §4.2. */
    private static void decisionTable() throws Exception {
        group("DecisionTableTest");
        equal("specification cell count", 80, SpecDecisionTable.CELL_COUNT);
        equal("implementation cell count", 80, DecisionTable.cellCount());

        for (SpecDecisionTable.Entry entry : SpecDecisionTable.entries()) {
            SpecCell expected = entry.expected();
            FindingType want = expectedFinding(expected);
            ReconciliationInput input = SpecFixtures.tableCase(entry.payment(), entry.order());
            FindingType got = ENGINE.reconcile(input).type();
            equal(entry.payment() + " + " + entry.order(), want, got);
        }
    }

    /**
     * What the specification cell means for the fixture used here: an hour-old pair, inside both
     * stale thresholds, with equal amounts, so an agreement cell resolves to MATCHED and a
     * conditional cell resolves to its below-threshold value.
     */
    private static FindingType expectedFinding(SpecCell cell) {
        if (cell instanceof SpecCell.Fixed fixed) {
            return fixed.type();
        }
        if (cell instanceof SpecCell.Agreement) {
            return FindingType.MATCHED;
        }
        if (cell instanceof SpecCell.Conditional conditional) {
            return conditional.below();
        }
        throw new IllegalStateException("unknown cell " + cell);
    }

    private static void totality() throws Exception {
        group("TotalityTest");
        int seen = 0;
        for (PaymentStatus p : PaymentStatus.values()) {
            for (OrderStatus o : OrderStatus.values()) {
                ReconciliationFinding finding = ENGINE.reconcile(SpecFixtures.tableCase(p, o));
                check("non-null finding for " + p + "/" + o, finding != null && finding.type() != null);
                seen++;
            }
        }
        equal("valid-domain combinations evaluated", 80, seen);
        equal("payment statuses", 8, PaymentStatus.values().length);
        equal("order statuses", 10, OrderStatus.values().length);
        equal("finding types", 16, FindingType.values().length);
    }

    /** Cells where several rules could apply at once; the priority list resolves them. */
    private static void rulePriority() throws Exception {
        group("RulePriorityTest");

        // Currency mismatch outranks the amount rule and the grace period.
        PaymentObservation eurPayment =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                        SpecFixtures.eur(999), SpecFixtures.eur(999), null, 0,
                        SpecFixtures.NOW.minusSeconds(5), SpecFixtures.NOW, false);
        equal(
                "currency beats grace and amount",
                FindingType.CURRENCY_MISMATCH,
                ENGINE.reconcile(
                                SpecFixtures.input(eurPayment, SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                        .type());

        // Unknown status outranks currency mismatch.
        PaymentObservation unknownEur =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.UNKNOWN,
                        SpecFixtures.eur(999), SpecFixtures.eur(999), null, 0,
                        SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false);
        equal(
                "unrecognised status beats currency",
                FindingType.INSUFFICIENT_DATA,
                ENGINE.reconcile(
                                SpecFixtures.input(unknownEur, SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                        .type());

        // Duplicate payment outranks the grace period: a double charge does not converge.
        List<TransactionRecord> twoCaptures =
                List.of(SpecFixtures.capture("r1", 125_000), SpecFixtures.capture("r2", 125_000));
        equal(
                "duplicate beats grace",
                FindingType.DUPLICATE_PAYMENT,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED, SpecFixtures.NOW.minusSeconds(5)),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, SpecFixtures.NOW.minusSeconds(5)),
                                        twoCaptures))
                        .type());

        // Conflicting identity outranks duplicate detection.
        List<TransactionRecord> conflict =
                List.of(
                        SpecFixtures.capture("same", 125_000),
                        SpecFixtures.capture("same", 999_999),
                        SpecFixtures.capture("other", 125_000));
        equal(
                "same-identity conflict beats duplicate",
                FindingType.INSUFFICIENT_DATA,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT),
                                        conflict))
                        .type());

        // Grace outranks the decision table for a genuine disagreement.
        equal(
                "grace suppresses a table finding",
                FindingType.WITHIN_GRACE_PERIOD,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED, SpecFixtures.NOW.minusSeconds(5)),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, SpecFixtures.NOW.minusSeconds(5))))
                        .type());
    }

    /** FR-06, INV-13: the queried status decides; history is evidence and can only contradict. */
    private static void authoritativeState() throws Exception {
        group("AuthoritativeStateTest");

        // A FAILED status alongside a capture record is a contradiction, not a CAPTURED verdict.
        equal(
                "FAILED status + capture record -> INSUFFICIENT_DATA",
                FindingType.INSUFFICIENT_DATA,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.FAILED),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT),
                                        List.of(SpecFixtures.capture("r1", 125_000))))
                        .type());
        equal(
                "ABSENT status + capture record -> INSUFFICIENT_DATA",
                FindingType.INSUFFICIENT_DATA,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.ABSENT),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT),
                                        List.of(SpecFixtures.capture("r1", 125_000))))
                        .type());

        // Empty history still yields the ordinary table verdict.
        equal(
                "CAPTURED + empty history still gives the table verdict",
                FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT)))
                        .type());

        // Non-contradicting richer history does not trigger the gate.
        TransactionRecord refundRow =
                SpecFixtures.record("ref1", RecordKind.REFUND, 62_500, SpecFixtures.ONE_HOUR_AGO, 1L);
        equal(
                "PARTIALLY_REFUNDED + refund row is not a contradiction",
                FindingType.REFUND_DIVERGENCE,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.PARTIALLY_REFUNDED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        List.of(refundRow)))
                        .type());

        // INV-13: for a fixed status, varying non-contradictory history cannot change the cell.
        List<List<TransactionRecord>> histories =
                List.of(
                        List.of(),
                        List.of(SpecFixtures.capture("c1", 125_000)),
                        List.of(refundRow),
                        List.of(SpecFixtures.capture("c1", 125_000), refundRow));
        for (List<TransactionRecord> history : histories) {
            equal(
                    "INV-13 cell stable for history size " + history.size(),
                    FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                    ENGINE.reconcile(
                                    SpecFixtures.input(
                                            SpecFixtures.payment(PaymentStatus.CAPTURED),
                                            SpecFixtures.order(OrderStatus.AWAITING_PAYMENT),
                                            history))
                            .type());
        }
    }

    private static void money() throws Exception {
        group("MoneyTest");
        equal("zero is valid", 0L, Money.ofTry(0).minorUnits());
        equal("one minor unit", 1L, Money.ofTry(1).minorUnits());
        equal("maximum", Long.MAX_VALUE, Money.ofTry(Long.MAX_VALUE).minorUnits());
        throwsType("negative rejected", InvalidReconciliationInputException.class, () -> Money.ofTry(-1));
        throwsType("null currency rejected", InvalidReconciliationInputException.class, () -> Money.of(1, null));

        throwsType(
                "plus overflow raises, never wraps",
                MoneyOverflowException.class,
                () -> Money.ofTry(Long.MAX_VALUE).plus(Money.ofTry(1)));
        throwsType(
                "cross-currency plus refused",
                CurrencyMismatchException.class,
                () -> Money.ofTry(100).plus(SpecFixtures.eur(100)));
        throwsType(
                "cross-currency absoluteDifference refused",
                CurrencyMismatchException.class,
                () -> Money.ofTry(100).absoluteDifference(SpecFixtures.eur(100)));

        equal(
                "absoluteDifference at the maximum succeeds",
                Long.MAX_VALUE,
                Money.ofTry(0).absoluteDifference(Money.ofTry(Long.MAX_VALUE)).minorUnits());
        equal(
                "absoluteDifference is symmetric",
                Money.ofTry(25),
                Money.ofTry(100).absoluteDifference(Money.ofTry(125)));
        equal("value equality", Money.ofTry(100), Money.ofTry(100));
        check("currency participates in equality", !Money.ofTry(100).equals(SpecFixtures.eur(100)));

        // ADR-003: Money.minus() must not exist.
        boolean hasMinus = false;
        for (Method m : Money.class.getDeclaredMethods()) {
            if (m.getName().equals("minus")) {
                hasMinus = true;
            }
        }
        check("Money.minus() does not exist", !hasMinus);

        // NFR-02: no floating point in the money path.
        boolean floating = false;
        for (Method m : Money.class.getDeclaredMethods()) {
            if (m.getReturnType() == double.class || m.getReturnType() == float.class) {
                floating = true;
            }
            for (Class<?> p : m.getParameterTypes()) {
                if (p == double.class || p == float.class) {
                    floating = true;
                }
            }
        }
        check("no float or double in Money's API", !floating);
    }

    /** FR-02 boundaries, including the under-collection direction. */
    private static void amountComparison() throws Exception {
        group("AmountComparisonTest");
        ReconciliationPolicy tol = SpecFixtures.policy().withAmountTolerance(100);

        // (a) requested vs order total, at tolerance -1 / = / +1
        equal(
                "difference tolerance-1 accepted",
                FindingType.MATCHED,
                amountCase(125_099, 125_099, 125_000, 0, tol).type());
        equal(
                "difference exactly tolerance accepted",
                FindingType.MATCHED,
                amountCase(125_100, 125_100, 125_000, 0, tol).type());
        equal(
                "difference tolerance+1 flagged",
                FindingType.AMOUNT_MISMATCH,
                amountCase(125_101, 125_101, 125_000, 0, tol).type());

        // Zero tolerance
        ReconciliationPolicy zero = SpecFixtures.policy().withAmountTolerance(0);
        equal(
                "zero tolerance, zero difference accepted",
                FindingType.MATCHED,
                amountCase(125_000, 125_000, 125_000, 0, zero).type());
        equal(
                "zero tolerance, one minor unit flagged",
                FindingType.AMOUNT_MISMATCH,
                amountCase(125_001, 125_001, 125_000, 0, zero).type());

        // (c) under-collection, never excused by instalments
        ReconciliationFinding under = amountCase(125_000, 124_899, 125_000, 0, tol);
        equal("under-collection flagged", FindingType.AMOUNT_MISMATCH, under.type());
        check("under-collection reason recorded", under.reasons().contains("under_collected"));
        equal(
                "under-collection just inside tolerance accepted",
                FindingType.MATCHED,
                amountCase(125_000, 124_900, 125_000, 0, tol).type());
        ReconciliationFinding underWithInstalments = amountCase(125_000, 124_899, 125_000, 12, tol);
        equal(
                "under-collection flagged even with 12 instalments",
                FindingType.AMOUNT_MISMATCH,
                underWithInstalments.type());
        check(
                "instalments do not excuse under-collection",
                underWithInstalments.reasons().contains("under_collected"));

        // amountAtRisk is the largest absolute difference
        equal(
                "amountAtRisk is the difference",
                Optional.of(SpecFixtures.try_(101)),
                amountCase(125_101, 125_101, 125_000, 0, tol).amountAtRisk());
    }

    private static ReconciliationFinding amountCase(
            long requested, long collected, long orderTotal, int instalments, ReconciliationPolicy policy)
            throws Exception {
        PaymentObservation payment =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                        SpecFixtures.try_(requested), SpecFixtures.try_(collected), null, instalments,
                        SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false);
        OrderObservation order =
                OrderObservation.of(
                        SpecFixtures.ORDER_REF, OrderStatus.PAID_UNFULFILLED,
                        SpecFixtures.try_(orderTotal), SpecFixtures.ONE_HOUR_AGO,
                        SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW);
        return ENGINE.reconcile(
                SpecFixtures.input(payment, order, List.of(), policy, SpecFixtures.NOW));
    }

    /** FR-02b: the documented instalment allowance is one-directional. */
    private static void installmentAllowance() throws Exception {
        group("InstallmentAllowanceTest");
        ReconciliationPolicy policy = SpecFixtures.policy();

        ReconciliationFinding withInstalments = amountCase(125_000, 131_250, 125_000, 3, policy);
        equal(
                "collected over requested with 3 instalments is not flagged",
                FindingType.MATCHED,
                withInstalments.type());
        check(
                "allowance recorded in reasons",
                withInstalments.reasons().contains("installment_excess_allowed"));

        equal(
                "same excess with 12 instalments is not flagged",
                FindingType.MATCHED,
                amountCase(125_000, 131_250, 125_000, 12, policy).type());

        ReconciliationFinding noInstalments = amountCase(125_000, 131_250, 125_000, 0, policy);
        equal(
                "same excess with zero instalments is flagged",
                FindingType.AMOUNT_MISMATCH,
                noInstalments.type());
        check(
                "over-collection reason recorded",
                noInstalments.reasons().contains("over_collected_without_installments"));

        equal(
                "instalment count above the documented maximum is accepted as valid",
                FindingType.MATCHED,
                amountCase(125_000, 131_250, 125_000, 13, policy).type());

        equal(
                "allowance can be disabled by policy",
                FindingType.AMOUNT_MISMATCH,
                amountCase(
                                125_000, 131_250, 125_000, 3,
                                policy.withAllowInstallmentExcess(false))
                        .type());
    }

    /** FR-03: skip, do not flag and do not throw, when a currency is unavailable. */
    private static void currencyRule() throws Exception {
        group("CurrencyRuleTest");
        PaymentObservation eurPayment =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED, SpecFixtures.eur(125_000),
                        SpecFixtures.eur(125_000), null, 0, SpecFixtures.ONE_HOUR_AGO,
                        SpecFixtures.NOW, false);
        equal(
                "TRY order vs EUR payment flagged",
                FindingType.CURRENCY_MISMATCH,
                ENGINE.reconcile(SpecFixtures.input(eurPayment, SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                        .type());

        equal(
                "ABSENT payment against a TRY order is not a currency mismatch",
                FindingType.MATCHED,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.ABSENT),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT)))
                        .type());

        PaymentObservation noCurrency =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.FAILED, null, null, null, 0,
                        SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false);
        doesNotThrow(
                "present payment with no currency does not throw",
                () -> ENGINE.reconcile(
                        SpecFixtures.input(noCurrency, SpecFixtures.order(OrderStatus.AWAITING_PAYMENT))));
        equal(
                "present payment with no currency is not a mismatch",
                FindingType.MATCHED,
                ENGINE.reconcile(
                                SpecFixtures.input(noCurrency, SpecFixtures.order(OrderStatus.AWAITING_PAYMENT)))
                        .type());

        equal(
                "both absent yields INSUFFICIENT_DATA from the table",
                FindingType.INSUFFICIENT_DATA,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.ABSENT),
                                        SpecFixtures.order(OrderStatus.ABSENT)))
                        .type());
    }

    /** FR-04.2 boundaries. Reference instant is domain time only. */
    private static void gracePeriod() throws Exception {
        group("GracePeriodTest");
        Duration grace = SpecFixtures.policy().gracePeriod();

        equal(
                "age = grace - 1ms is within grace",
                FindingType.WITHIN_GRACE_PERIOD,
                graceCase(grace.minusMillis(1)).type());
        equal(
                "age exactly grace is outside grace",
                FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                graceCase(grace).type());
        equal(
                "age = grace + 1ms is outside grace",
                FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                graceCase(grace.plusMillis(1)).type());
        equal("zero age is within grace", FindingType.WITHIN_GRACE_PERIOD, graceCase(Duration.ZERO).type());

        // Both domain times absent: grace cannot apply.
        PaymentObservation noTime =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED, SpecFixtures.try_(125_000),
                        SpecFixtures.try_(125_000), null, 0, null, SpecFixtures.NOW, false);
        OrderObservation noCreated =
                new OrderObservation(
                        SpecFixtures.ORDER_REF, OrderStatus.AWAITING_PAYMENT, SpecFixtures.try_(125_000),
                        null, null, null, SpecFixtures.NOW);
        equal(
                "no domain time: grace cannot apply",
                FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                ENGINE.reconcile(SpecFixtures.input(noTime, noCreated)).type());

        // Clock-skew tolerance boundary (FR-08).
        Duration skew = SpecFixtures.policy().clockSkewTolerance();
        doesNotThrow(
                "domain time within skew tolerance accepted",
                () -> ENGINE.reconcile(futureCase(skew.minusMillis(1))));
        doesNotThrow(
                "domain time exactly at skew tolerance accepted",
                () -> ENGINE.reconcile(futureCase(skew)));
        throwsType(
                "domain time beyond skew tolerance rejected",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(futureCase(skew.plusMillis(1))));
    }

    private static ReconciliationFinding graceCase(Duration age) throws Exception {
        Instant reference = SpecFixtures.NOW.minus(age);
        return ENGINE.reconcile(
                SpecFixtures.input(
                        SpecFixtures.payment(PaymentStatus.CAPTURED, reference),
                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, reference)));
    }

    private static ReconciliationInput futureCase(Duration ahead) throws Exception {
        Instant future = SpecFixtures.NOW.plus(ahead);
        return SpecFixtures.input(
                SpecFixtures.payment(PaymentStatus.CAPTURED, future),
                SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, SpecFixtures.ONE_HOUR_AGO));
    }

    /**
     * INV-12. The property that would have caught the revision 0.1 defect: a fresh retrieval time on
     * every evaluation must not move the window.
     */
    private static void graceSuppression() throws Exception {
        group("GraceSuppressionTest");
        Instant reference = SpecFixtures.NOW;
        Duration grace = SpecFixtures.policy().gracePeriod();

        int withinGrace = 0;
        int reported = 0;
        boolean everReturnedToGrace = false;
        boolean seenReported = false;

        for (int minute = 0; minute < 100; minute++) {
            Instant evaluatedAt = reference.plus(Duration.ofMinutes(minute));
            // A fresh observedAt on every pass: this is what a re-poll looks like.
            PaymentObservation payment =
                    new PaymentObservation(
                            "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                            SpecFixtures.try_(125_000), SpecFixtures.try_(125_000), null, 0,
                            reference, evaluatedAt, false);
            OrderObservation order =
                    OrderObservation.of(
                            SpecFixtures.ORDER_REF, OrderStatus.AWAITING_PAYMENT,
                            SpecFixtures.try_(125_000), reference, evaluatedAt, evaluatedAt);
            FindingType type =
                    ENGINE.reconcile(
                                    SpecFixtures.input(
                                            payment, order, List.of(), SpecFixtures.policy(), evaluatedAt))
                            .type();
            if (type == FindingType.WITHIN_GRACE_PERIOD) {
                withinGrace++;
                if (seenReported) {
                    everReturnedToGrace = true;
                }
            } else {
                reported++;
                seenReported = true;
                equal("reported finding is the real one", FindingType.ORDER_UNPAID_DESPITE_PAYMENT, type);
            }
        }
        equal("within grace for exactly the first 15 minutes", (int) grace.toMinutes(), withinGrace);
        equal("reported for the remaining evaluations", 100 - (int) grace.toMinutes(), reported);
        check("grace is left exactly once and never re-entered", !everReturnedToGrace);
    }

    private static void staleThresholds() throws Exception {
        group("StaleThresholdTest");
        Duration authThreshold = SpecFixtures.policy().staleAuthorizationThreshold();
        Duration progThreshold = SpecFixtures.policy().staleInProgressThreshold();

        equal(
                "authorisation below threshold is pending",
                FindingType.AUTHORIZATION_PENDING,
                thresholdCase(PaymentStatus.AUTHORIZED, authThreshold.minusMillis(1)).type());
        equal(
                "authorisation exactly at threshold is stale",
                FindingType.STALE_AUTHORIZATION,
                thresholdCase(PaymentStatus.AUTHORIZED, authThreshold).type());
        equal(
                "authorisation above threshold is stale",
                FindingType.STALE_AUTHORIZATION,
                thresholdCase(PaymentStatus.AUTHORIZED, authThreshold.plusMillis(1)).type());

        equal(
                "in-progress below threshold is pending",
                FindingType.IN_PROGRESS_PENDING,
                thresholdCase(PaymentStatus.IN_PROGRESS, progThreshold.minusMillis(1)).type());
        equal(
                "in-progress exactly at threshold is stale",
                FindingType.STALE_IN_PROGRESS,
                thresholdCase(PaymentStatus.IN_PROGRESS, progThreshold).type());
        equal(
                "in-progress above threshold is stale",
                FindingType.STALE_IN_PROGRESS,
                thresholdCase(PaymentStatus.IN_PROGRESS, progThreshold.plusMillis(1)).type());
    }

    private static ReconciliationFinding thresholdCase(PaymentStatus status, Duration age)
            throws Exception {
        Instant reference = SpecFixtures.NOW.minus(age);
        return ENGINE.reconcile(
                SpecFixtures.input(
                        SpecFixtures.payment(status, reference),
                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, reference)));
    }

    private static void duplicatePayment() throws Exception {
        group("DuplicatePaymentTest");

        List<TransactionRecord> two =
                List.of(SpecFixtures.capture("r1", 125_000), SpecFixtures.capture("r2", 60_000));
        ReconciliationFinding duplicate =
                ENGINE.reconcile(
                        SpecFixtures.input(
                                SpecFixtures.payment(PaymentStatus.CAPTURED),
                                SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                two));
        equal("two distinct captures", FindingType.DUPLICATE_PAYMENT, duplicate.type());
        equal(
                "amountAtRisk is the capture beyond the first",
                Optional.of(SpecFixtures.try_(60_000)),
                duplicate.amountAtRisk());

        List<TransactionRecord> repeated = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            repeated.add(SpecFixtures.capture("r1", 125_000));
        }
        equal(
                "one capture repeated five times is never a duplicate",
                FindingType.MATCHED,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        repeated))
                        .type());

        ReconciliationFinding noHistory =
                ENGINE.reconcile(
                        SpecFixtures.input(
                                SpecFixtures.payment(PaymentStatus.CAPTURED),
                                SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)));
        equal("empty history detects no duplicate", FindingType.MATCHED, noHistory.type());
        // DEF-002: FR-05.4 places this in the decision path. The first version of this check
        // asserted `reasons`, which is where the implementation happened to put it - a test derived
        // from the code instead of from the specification, and the reason the defect survived a pass.
        check(
                "empty history records the limitation in the decision path",
                noHistory.decisionPath().stream()
                        .anyMatch(step -> step.contains("duplicate captures undetectable")));
        check(
                "empty history adds no spurious reason",
                noHistory.reasons().stream()
                        .noneMatch(reason -> reason.contains("duplicate_detection_unavailable")));
        check("a clean match carries no reasons at all", noHistory.reasons().isEmpty());

        // Permutation invariance of the duplicate verdict.
        for (List<TransactionRecord> permutation : Permutations.of(two)) {
            equal(
                    "duplicate verdict is permutation-invariant",
                    FindingType.DUPLICATE_PAYMENT,
                    ENGINE.reconcile(
                                    SpecFixtures.input(
                                            SpecFixtures.payment(PaymentStatus.CAPTURED),
                                            SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                            permutation))
                            .type());
        }
    }

    /** FR-07 reduced: three directions, with absent amounts where §4.4 says so. */
    private static void refundDivergence() throws Exception {
        group("RefundDivergenceTest");

        ReconciliationFinding gatewayOnly =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.PAID_UNFULFILLED));
        equal("gateway-only divergence", FindingType.REFUND_DIVERGENCE, gatewayOnly.type());
        check(
                "direction recorded as gateway_only",
                gatewayOnly.reasons().contains("refund_direction=gateway_only"));
        check("gateway_only carries an amount", gatewayOnly.amountAtRisk().isPresent());

        ReconciliationFinding shopOnly =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.REFUNDED));
        equal("shop-only divergence", FindingType.REFUND_DIVERGENCE, shopOnly.type());
        check(
                "direction recorded as shop_only",
                shopOnly.reasons().contains("refund_direction=shop_only"));
        check("shop_only amount is absent", shopOnly.amountAtRisk().isEmpty());
        equal("shop_only severity stays HIGH", Severity.HIGH, shopOnly.severity());

        ReconciliationFinding partial =
                ENGINE.reconcile(
                        SpecFixtures.tableCase(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED));
        equal("partial vs full divergence", FindingType.REFUND_DIVERGENCE, partial.type());
        check(
                "direction recorded as partial_vs_full",
                partial.reasons().contains("refund_direction=partial_vs_full"));
        check("partial_vs_full amount is absent in Scope A", partial.amountAtRisk().isEmpty());

        equal(
                "gateway refund matching an already-refunded order is MATCHED",
                FindingType.MATCHED,
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.REFUNDED)).type());
    }

    private static void unknownStatus() throws Exception {
        group("UnknownStatusTest");
        for (OrderStatus order : OrderStatus.values()) {
            equal(
                    "UNKNOWN payment with order " + order,
                    FindingType.INSUFFICIENT_DATA,
                    ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.UNKNOWN, order)).type());
        }
        for (PaymentStatus payment : PaymentStatus.values()) {
            equal(
                    "UNKNOWN order with payment " + payment,
                    FindingType.INSUFFICIENT_DATA,
                    ENGINE.reconcile(SpecFixtures.tableCase(payment, OrderStatus.UNKNOWN)).type());
        }
        ReconciliationFinding finding =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.UNKNOWN, OrderStatus.FULFILLED));
        check("reason recorded", finding.reasons().contains("unrecognised_status"));
        equal("INSUFFICIENT_DATA severity is MEDIUM", Severity.MEDIUM, finding.severity());
    }

    private static void recordIdentity() throws Exception {
        group("RecordIdentityTest");
        TransactionRecord a = SpecFixtures.record("id-1", RecordKind.CAPTURE, 125_000, SpecFixtures.ONE_HOUR_AGO, 1L);
        TransactionRecord sameContentLaterRetrieval =
                SpecFixtures.record("id-1", RecordKind.CAPTURE, 125_000, SpecFixtures.ONE_HOUR_AGO, 99L);
        equal(
                "significant content ignores retrieval sequence",
                a.significantContent(),
                sameContentLaterRetrieval.significantContent());

        TransactionRecord differentAmount =
                SpecFixtures.record("id-1", RecordKind.CAPTURE, 999, SpecFixtures.ONE_HOUR_AGO, 1L);
        check(
                "significant content distinguishes amounts",
                !a.significantContent().equals(differentAmount.significantContent()));

        check("capture is recognised", a.isCapture());
        check(
                "refund is not a capture",
                !SpecFixtures.record("id-2", RecordKind.REFUND, 10, SpecFixtures.ONE_HOUR_AGO, 1L)
                        .isCapture());
        throwsType(
                "blank identity rejected",
                InvalidReconciliationInputException.class,
                () -> SpecFixtures.record("   ", RecordKind.CAPTURE, 1, SpecFixtures.ONE_HOUR_AGO, 1L)
                        .validated());
    }

    private static void recordNormalizer() throws Exception {
        group("RecordNormalizerTest");
        TransactionRecord r1 = SpecFixtures.record("a", RecordKind.CAPTURE, 100, SpecFixtures.NOW.minusSeconds(60), 3L);
        TransactionRecord r2 = SpecFixtures.record("b", RecordKind.REFUND, 50, SpecFixtures.NOW.minusSeconds(30), 1L);
        TransactionRecord r1Again = SpecFixtures.record("a", RecordKind.CAPTURE, 100, SpecFixtures.NOW.minusSeconds(60), 7L);

        NormalizedHistory normalized = RecordNormalizer.normalize(List.of(r2, r1, r1Again));
        equal("repetition collapsed", 2, normalized.records().size());
        equal("collapsed count recorded", 1, normalized.collapsedRepetitions());
        equal("canonical order by occurredAt", "a", normalized.records().get(0).recordIdentity());
        equal("then by occurredAt", "b", normalized.records().get(1).recordIdentity());
        check("no conflicts", !normalized.hasConflicts());

        NormalizedHistory empty = RecordNormalizer.normalize(List.of());
        check("empty history is empty", empty.isEmpty());
        equal("empty history has no captures", 0, empty.captures().size());

        // Tie on occurredAt breaks on identity, not retrieval order.
        TransactionRecord tieZ = SpecFixtures.record("z", RecordKind.CAPTURE, 1, SpecFixtures.ONE_HOUR_AGO, 1L);
        TransactionRecord tieA = SpecFixtures.record("a", RecordKind.CAPTURE, 1, SpecFixtures.ONE_HOUR_AGO, 2L);
        equal(
                "tie-break is recordIdentity",
                "a",
                RecordNormalizer.normalize(List.of(tieZ, tieA)).records().get(0).recordIdentity());
        equal(
                "tie-break is independent of input order",
                "a",
                RecordNormalizer.normalize(List.of(tieA, tieZ)).records().get(0).recordIdentity());
    }

    /** FR-05.3: the same verdict in every permutation, never a positional choice. */
    private static void conflictingIdentity() throws Exception {
        group("ConflictingIdentityTest");
        List<TransactionRecord> conflicting =
                List.of(
                        SpecFixtures.capture("same", 125_000),
                        SpecFixtures.capture("same", 999_999),
                        SpecFixtures.capture("other", 125_000));

        List<List<TransactionRecord>> permutations = Permutations.of(conflicting);
        equal("permutations generated", 6, permutations.size());
        for (List<TransactionRecord> permutation : permutations) {
            ReconciliationFinding finding =
                    ENGINE.reconcile(
                            SpecFixtures.input(
                                    SpecFixtures.payment(PaymentStatus.CAPTURED),
                                    SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                    permutation));
            equal("conflict yields INSUFFICIENT_DATA", FindingType.INSUFFICIENT_DATA, finding.type());
            check(
                    "conflict reason recorded",
                    finding.reasons().contains("conflicting_records_same_identity"));
        }
    }

    /** INV-06: content-identical repetitions are inert. */
    private static void repetitionInvariance() throws Exception {
        group("RepetitionInvariancePropertyTest");
        TransactionRecord capture = SpecFixtures.capture("c1", 125_000);
        TransactionRecord refund =
                SpecFixtures.record("f1", RecordKind.REFUND, 1_000, SpecFixtures.ONE_HOUR_AGO, 2L);

        for (int repeats = 1; repeats <= 6; repeats++) {
            List<TransactionRecord> history = new ArrayList<>();
            for (int i = 0; i < repeats; i++) {
                history.add(capture);
                history.add(refund);
            }
            FindingType type =
                    ENGINE.reconcile(
                                    SpecFixtures.input(
                                            SpecFixtures.payment(PaymentStatus.CAPTURED),
                                            SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                            history))
                            .type();
            equal("verdict unchanged with " + repeats + " repetition(s)", FindingType.MATCHED, type);
        }
    }

    /** INV-09: the verdict is identical for every permutation of the record list. */
    private static void orderIndependence() throws Exception {
        group("OrderIndependencePropertyTest");
        List<TransactionRecord> history =
                List.of(
                        SpecFixtures.record("c1", RecordKind.CAPTURE, 125_000, SpecFixtures.NOW.minusSeconds(300), 1L),
                        SpecFixtures.record("f1", RecordKind.REFUND, 5_000, SpecFixtures.NOW.minusSeconds(200), 2L),
                        SpecFixtures.record("o1", RecordKind.OTHER, 0, SpecFixtures.NOW.minusSeconds(100), 3L),
                        SpecFixtures.record("o2", RecordKind.OTHER, 0, SpecFixtures.NOW.minusSeconds(100), 4L));

        List<List<TransactionRecord>> permutations = Permutations.of(history);
        equal("permutations generated", 24, permutations.size());

        ReconciliationFinding first = null;
        for (List<TransactionRecord> permutation : permutations) {
            ReconciliationFinding finding =
                    ENGINE.reconcile(
                            SpecFixtures.input(
                                    SpecFixtures.payment(PaymentStatus.CAPTURED),
                                    SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                    permutation));
            if (first == null) {
                first = finding;
            } else {
                equal("type invariant", first.type(), finding.type());
                equal("amountAtRisk invariant", first.amountAtRisk(), finding.amountAtRisk());
                equal("reasons invariant", first.reasons(), finding.reasons());
                equal("decisionPath invariant", first.decisionPath(), finding.decisionPath());
            }
        }
    }

    /** INV-08: severity is a function of the type alone. */
    private static void severity() throws Exception {
        group("SeverityTest");
        for (FindingType type : FindingType.values()) {
            doesNotThrow("severity mapped for " + type, () -> FindingCatalog.severityOf(type));
            doesNotThrow("action mapped for " + type, () -> FindingCatalog.actionOf(type));
        }
        equal("MATCHED is INFO", Severity.INFO, FindingCatalog.severityOf(FindingType.MATCHED));
        equal(
                "INSUFFICIENT_DATA is MEDIUM",
                Severity.MEDIUM,
                FindingCatalog.severityOf(FindingType.INSUFFICIENT_DATA));
        equal(
                "REFUND_DIVERGENCE is HIGH",
                Severity.HIGH,
                FindingCatalog.severityOf(FindingType.REFUND_DIVERGENCE));

        // The same type with a present, absent and zero amount must have identical severity.
        ReconciliationFinding withAmount =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.PAID_UNFULFILLED));
        ReconciliationFinding withoutAmount =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.REFUNDED));
        equal("same type, same severity regardless of amount", withAmount.severity(), withoutAmount.severity());
        check("one has an amount", withAmount.amountAtRisk().isPresent());
        check("the other does not", withoutAmount.amountAtRisk().isEmpty());
    }

    /** §4.4: present, absent, or present-and-zero, per type. */
    private static void amountAtRisk() throws Exception {
        group("AmountAtRiskTest");

        // Present and zero: a fully refunded orphan payment. Severity must stay HIGH.
        ReconciliationFinding refundedOrphan =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.ABSENT));
        equal("refunded orphan is PAYMENT_WITHOUT_ORDER", FindingType.PAYMENT_WITHOUT_ORDER, refundedOrphan.type());
        check("amount present", refundedOrphan.amountAtRisk().isPresent());
        check("amount is zero", refundedOrphan.amountAtRisk().orElseThrow().isZero());
        equal("severity stays HIGH at zero exposure", Severity.HIGH, refundedOrphan.severity());

        // Present and non-zero
        ReconciliationFinding unpaid =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.AWAITING_PAYMENT));
        equal(
                "ORDER_UNPAID_DESPITE_PAYMENT exposes the collected amount",
                Optional.of(SpecFixtures.try_(SpecFixtures.AMOUNT)),
                unpaid.amountAtRisk());

        ReconciliationFinding paidNoPayment =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.ABSENT, OrderStatus.FULFILLED));
        equal(
                "ORDER_PAID_WITHOUT_PAYMENT exposes the order total",
                Optional.of(SpecFixtures.try_(SpecFixtures.AMOUNT)),
                paidNoPayment.amountAtRisk());

        ReconciliationFinding cancelled =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.CANCELLED));
        equal(
                "PAYMENT_AGAINST_CANCELLED_ORDER exposes the collected amount",
                Optional.of(SpecFixtures.try_(SpecFixtures.AMOUNT)),
                cancelled.amountAtRisk());

        ReconciliationFinding staleAuth =
                thresholdCase(PaymentStatus.AUTHORIZED, SpecFixtures.policy().staleAuthorizationThreshold());
        equal(
                "STALE_AUTHORIZATION exposes the requested amount",
                Optional.of(SpecFixtures.try_(SpecFixtures.AMOUNT)),
                staleAuth.amountAtRisk());

        // Absent
        check(
                "STATUS_CONFLICT amount absent",
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.FAILED))
                        .amountAtRisk()
                        .isEmpty());
        check(
                "INSUFFICIENT_DATA amount absent",
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.UNKNOWN, OrderStatus.FULFILLED))
                        .amountAtRisk()
                        .isEmpty());
        check(
                "MATCHED amount absent",
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.FAILED, OrderStatus.AWAITING_PAYMENT))
                        .amountAtRisk()
                        .isEmpty());
        check(
                "STALE_IN_PROGRESS amount absent",
                thresholdCase(PaymentStatus.IN_PROGRESS, SpecFixtures.policy().staleInProgressThreshold())
                        .amountAtRisk()
                        .isEmpty());
        check(
                "IN_PROGRESS_PENDING amount absent",
                thresholdCase(PaymentStatus.IN_PROGRESS, Duration.ofHours(1)).amountAtRisk().isEmpty());
        check(
                "WITHIN_GRACE_PERIOD amount absent",
                graceCase(Duration.ZERO).amountAtRisk().isEmpty());
    }

    /** FR-08 negatives. Every rejection names its field. */
    private static void inputValidation() throws Exception {
        group("InputValidationTest");
        throwsType(
                "null input",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(null));
        throwsType(
                "negative amount",
                InvalidReconciliationInputException.class,
                () -> SpecFixtures.try_(-1));
        throwsType(
                "negative instalment count",
                InvalidReconciliationInputException.class,
                () -> new PaymentObservation(
                                "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED, null, null, null,
                                -1, null, SpecFixtures.NOW, false)
                        .validated());
        throwsType(
                "blank merchant order reference",
                InvalidReconciliationInputException.class,
                () -> new PaymentObservation(
                                "GW", "  ", PaymentStatus.CAPTURED, null, null, null, 0, null,
                                SpecFixtures.NOW, false)
                        .validated());
        throwsType(
                "non-positive grace period",
                InvalidReconciliationInputException.class,
                () -> SpecFixtures.policy().withGracePeriod(Duration.ZERO).validated());
        throwsType(
                "negative grace period",
                InvalidReconciliationInputException.class,
                () -> SpecFixtures.policy().withGracePeriod(Duration.ofMinutes(-1)).validated());
        throwsType(
                "negative amount tolerance",
                InvalidReconciliationInputException.class,
                () -> SpecFixtures.policy().withAmountTolerance(-1).validated());
        // DEF-003: FR-08 requires a typed, field-naming error. The previous expectation here was
        // NullPointerException, transcribed from what the code did rather than from FR-08.
        throwsType(
                "null payment is a typed validation error",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(
                        new ReconciliationInput(
                                null, SpecFixtures.order(OrderStatus.FULFILLED), List.of(),
                                SpecFixtures.policy(), SpecFixtures.NOW)));
        throwsType(
                "null order is a typed validation error",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(
                        new ReconciliationInput(
                                SpecFixtures.payment(PaymentStatus.CAPTURED), null, List.of(),
                                SpecFixtures.policy(), SpecFixtures.NOW)));
        throwsType(
                "null policy is a typed validation error",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(
                        new ReconciliationInput(
                                SpecFixtures.payment(PaymentStatus.CAPTURED),
                                SpecFixtures.order(OrderStatus.FULFILLED), List.of(), null,
                                SpecFixtures.NOW)));
        throwsType(
                "null evaluatedAt is a typed validation error",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(
                        new ReconciliationInput(
                                SpecFixtures.payment(PaymentStatus.CAPTURED),
                                SpecFixtures.order(OrderStatus.FULFILLED), List.of(),
                                SpecFixtures.policy(), null)));

        // An unrecognised status is NOT an input error.
        doesNotThrow(
                "unrecognised status is not an input error",
                () -> ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.UNKNOWN, OrderStatus.FULFILLED)));
        // Empty history is valid.
        doesNotThrow(
                "empty history is valid",
                () -> ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.FULFILLED)));
        // DEF-003: empty history is valid (FR-05.4); null history is malformed. Collapsing the two
        // hid a caller's bug behind a valid-looking verdict, and the old check endorsed it.
        throwsType(
                "null history is malformed input",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(
                        new ReconciliationInput(
                                SpecFixtures.payment(PaymentStatus.CAPTURED),
                                SpecFixtures.order(OrderStatus.FULFILLED),
                                null,
                                SpecFixtures.policy(),
                                SpecFixtures.NOW)));
        doesNotThrow(
                "empty history remains valid",
                () -> ENGINE.reconcile(
                        new ReconciliationInput(
                                SpecFixtures.payment(PaymentStatus.CAPTURED),
                                SpecFixtures.order(OrderStatus.FULFILLED),
                                List.of(),
                                SpecFixtures.policy(),
                                SpecFixtures.NOW)));
        throwsType(
                "a null element inside history is malformed input",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(
                        new ReconciliationInput(
                                SpecFixtures.payment(PaymentStatus.CAPTURED),
                                SpecFixtures.order(OrderStatus.FULFILLED),
                                java.util.Arrays.asList(SpecFixtures.capture("c1", 1), null),
                                SpecFixtures.policy(),
                                SpecFixtures.NOW)));

        // The field name must be present on the exception.
        try {
            SpecFixtures.try_(-5);
            check("expected a rejection", false);
        } catch (InvalidReconciliationInputException e) {
            equal("field named on the exception", "money.minorUnits", e.field());
        }
    }

    /** INV-02: same valid input, same result, every time. */
    private static void purity() throws Exception {
        group("PurityTest");
        ReconciliationInput input = SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.AWAITING_PAYMENT);
        ReconciliationFinding first = ENGINE.reconcile(input);
        for (int i = 0; i < 20; i++) {
            ReconciliationFinding again = ENGINE.reconcile(input);
            equal("type stable on call " + i, first.type(), again.type());
            equal("reasons stable on call " + i, first.reasons(), again.reasons());
            equal("decisionPath stable on call " + i, first.decisionPath(), again.decisionPath());
            equal("amount stable on call " + i, first.amountAtRisk(), again.amountAtRisk());
        }
        // A separate engine instance must agree.
        equal("independent engine agrees", first.type(), new ReconciliationEngine().reconcile(input).type());
    }

    /** INV-11: no rule may read a retrieval time or a retrieval order. */
    private static void timeSource() throws Exception {
        group("TimeSourceTest");
        Instant reference = SpecFixtures.ONE_HOUR_AGO;

        ReconciliationFinding baseline = null;
        for (int i = 1; i <= 5; i++) {
            Instant retrievedAt = SpecFixtures.NOW.minusSeconds(i);
            PaymentObservation payment =
                    new PaymentObservation(
                            "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                            SpecFixtures.try_(125_000), SpecFixtures.try_(125_000), null, 0, reference,
                            retrievedAt, false);
            OrderObservation order =
                    OrderObservation.of(
                            SpecFixtures.ORDER_REF, OrderStatus.AWAITING_PAYMENT,
                            SpecFixtures.try_(125_000), reference, retrievedAt, retrievedAt);
            List<TransactionRecord> history =
                    List.of(SpecFixtures.record("c1", RecordKind.CAPTURE, 125_000, reference, i * 100L));
            ReconciliationFinding finding =
                    ENGINE.reconcile(SpecFixtures.input(payment, order, history));
            if (baseline == null) {
                baseline = finding;
            } else {
                equal("verdict unchanged by observedAt", baseline.type(), finding.type());
                equal("reasons unchanged by observedAt", baseline.reasons(), finding.reasons());
                equal(
                        "decisionPath unchanged by retrievalSequence",
                        baseline.decisionPath(),
                        finding.decisionPath());
            }
        }
    }

    /** INV-03: inputs are not mutated. */
    private static void immutability() throws Exception {
        group("ImmutabilityTest");
        List<TransactionRecord> history =
                new ArrayList<>(
                        List.of(SpecFixtures.capture("c1", 125_000), SpecFixtures.capture("c1", 125_000)));
        int sizeBefore = history.size();
        ReconciliationInput input =
                SpecFixtures.input(
                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                        history);
        ENGINE.reconcile(input);
        equal("caller's list unchanged", sizeBefore, history.size());
        equal("input's history unchanged", 2, input.history().size());

        throwsType(
                "input history is unmodifiable",
                UnsupportedOperationException.class,
                () -> input.history().add(SpecFixtures.capture("c9", 1)));

        ReconciliationFinding finding = ENGINE.reconcile(input);
        throwsType(
                "finding reasons are unmodifiable",
                UnsupportedOperationException.class,
                () -> finding.reasons().add("x"));
        throwsType(
                "finding decisionPath is unmodifiable",
                UnsupportedOperationException.class,
                () -> finding.decisionPath().add("x"));
    }

    /**
     * The at-or-above half of every threshold-conditional cell. The first pass only exercised the
     * below-threshold resolution for one order status, which left 12 cells half-tested.
     */
    private static void conditionalCellsAtThreshold() throws Exception {
        group("DecisionTableTest(threshold)");
        for (SpecDecisionTable.Entry entry : SpecDecisionTable.entries()) {
            if (!(entry.expected() instanceof SpecCell.Conditional conditional)) {
                continue;
            }
            Duration threshold =
                    entry.payment() == PaymentStatus.AUTHORIZED
                            ? SpecFixtures.policy().staleAuthorizationThreshold()
                            : SpecFixtures.policy().staleInProgressThreshold();
            Instant atOrAbove = SpecFixtures.NOW.minus(threshold);
            FindingType got =
                    ENGINE.reconcile(
                                    SpecFixtures.input(
                                            SpecFixtures.payment(entry.payment(), atOrAbove),
                                            SpecFixtures.order(entry.order(), atOrAbove)))
                            .type();
            equal(
                    entry.payment() + " + " + entry.order() + " at threshold",
                    conditional.atOrAbove(),
                    got);

            Instant below = SpecFixtures.NOW.minus(threshold.minusMillis(1));
            FindingType gotBelow =
                    ENGINE.reconcile(
                                    SpecFixtures.input(
                                            SpecFixtures.payment(entry.payment(), below),
                                            SpecFixtures.order(entry.order(), below)))
                            .type();
            equal(
                    entry.payment() + " + " + entry.order() + " just below threshold",
                    conditional.below(),
                    gotBelow);
        }
    }

    /**
     * Exposure cases §4.4 specifies that the first pass did not reach — in particular
     * PAYMENT_WITHOUT_ORDER when only one of the two amounts is known.
     */
    private static void exposureEdges() throws Exception {
        group("AmountAtRiskTest(edges)");

        // §4.4: "present when both known ... Absent if either amount is unknown".
        // A captured orphan payment with no refund amount recorded: refunded is unknown.
        ReconciliationFinding capturedOrphan =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.ABSENT));
        equal("captured orphan is PAYMENT_WITHOUT_ORDER", FindingType.PAYMENT_WITHOUT_ORDER, capturedOrphan.type());
        check(
                "PAYMENT_WITHOUT_ORDER amount absent when refundedAmount is unknown",
                capturedOrphan.amountAtRisk().isEmpty());

        // Both known, partially refunded: collected minus refunded.
        ReconciliationFinding partialOrphan =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.PARTIALLY_REFUNDED, OrderStatus.ABSENT));
        equal(
                "partially refunded orphan exposes collected minus refunded",
                Optional.of(SpecFixtures.try_(SpecFixtures.AMOUNT - SpecFixtures.AMOUNT / 2)),
                partialOrphan.amountAtRisk());

        // Collected unknown: absent.
        PaymentObservation noCollected =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED, SpecFixtures.try_(125_000),
                        null, null, 0, SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false);
        check(
                "ORDER_UNPAID_DESPITE_PAYMENT amount absent when collected unknown",
                ENGINE.reconcile(
                                SpecFixtures.input(noCollected, SpecFixtures.order(OrderStatus.AWAITING_PAYMENT)))
                        .amountAtRisk()
                        .isEmpty());

        // DUPLICATE_PAYMENT sums every capture beyond the first.
        List<TransactionRecord> three =
                List.of(
                        SpecFixtures.capture("r1", 125_000),
                        SpecFixtures.capture("r2", 20_000),
                        SpecFixtures.capture("r3", 30_000));
        equal(
                "duplicate exposure sums captures beyond the first",
                Optional.of(SpecFixtures.try_(50_000)),
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        three))
                        .amountAtRisk());

        // A capture record with no amount makes the sum uncomputable.
        TransactionRecord noAmount =
                new TransactionRecord("r9", SpecFixtures.ORDER_REF, RecordKind.CAPTURE, null,
                        SpecFixtures.ONE_HOUR_AGO, 9L);
        check(
                "duplicate exposure absent when a capture amount is unknown",
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        List.of(SpecFixtures.capture("r1", 125_000), noAmount)))
                        .amountAtRisk()
                        .isEmpty());
    }

    /** Priority 5 outranks priority 7: an amount mismatch inside the window is suppressed. */
    private static void graceOverAmount() throws Exception {
        group("RulePriorityTest(grace/amount)");
        Instant recent = SpecFixtures.NOW.minusSeconds(5);
        PaymentObservation payment =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED, SpecFixtures.try_(999_999),
                        SpecFixtures.try_(999_999), null, 0, recent, SpecFixtures.NOW, false);
        OrderObservation order =
                OrderObservation.of(
                        SpecFixtures.ORDER_REF, OrderStatus.PAID_UNFULFILLED, SpecFixtures.try_(125_000),
                        recent, recent, SpecFixtures.NOW);
        equal(
                "amount mismatch inside grace is suppressed",
                FindingType.WITHIN_GRACE_PERIOD,
                ENGINE.reconcile(SpecFixtures.input(payment, order)).type());

        // Agreement cell with mismatched amounts, past grace, for both agreement cells.
        for (OrderStatus agreeing : List.of(OrderStatus.PAID_UNFULFILLED, OrderStatus.FULFILLED)) {
            PaymentObservation old =
                    new PaymentObservation(
                            "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                            SpecFixtures.try_(999_999), SpecFixtures.try_(999_999), null, 0,
                            SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false);
            equal(
                    "agreement cell " + agreeing + " with amount mismatch past grace",
                    FindingType.AMOUNT_MISMATCH,
                    ENGINE.reconcile(SpecFixtures.input(old, SpecFixtures.order(agreeing))).type());
        }

        // A conditional cell inside grace is also suppressed.
        equal(
                "conditional cell inside grace is suppressed",
                FindingType.WITHIN_GRACE_PERIOD,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.AUTHORIZED, recent),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, recent)))
                        .type());
    }

    /** Identity conflicts arising from fields other than the amount. */
    private static void historyIdentityEdges() throws Exception {
        group("ConflictingIdentityTest(fields)");

        TransactionRecord base = SpecFixtures.capture("dup", 125_000);
        TransactionRecord differentTime =
                SpecFixtures.record("dup", RecordKind.CAPTURE, 125_000, SpecFixtures.NOW.minusSeconds(30), 2L);
        equal(
                "same identity, different occurredAt is a conflict",
                FindingType.INSUFFICIENT_DATA,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        List.of(base, differentTime)))
                        .type());

        TransactionRecord differentKind =
                SpecFixtures.record("dup", RecordKind.REFUND, 125_000, SpecFixtures.ONE_HOUR_AGO, 2L);
        equal(
                "same identity, different kind is a conflict",
                FindingType.INSUFFICIENT_DATA,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        List.of(base, differentKind)))
                        .type());

        // Differing retrieval sequence alone is NOT a conflict (INV-11).
        TransactionRecord laterRetrieval =
                SpecFixtures.record("dup", RecordKind.CAPTURE, 125_000, SpecFixtures.ONE_HOUR_AGO, 777L);
        equal(
                "same identity, different retrievalSequence is not a conflict",
                FindingType.MATCHED,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED),
                                        List.of(base, laterRetrieval)))
                        .type());
    }

    /**
     * One check per recorded implementation defect, named so the defect-to-regression relationship
     * is obvious. These duplicate coverage that exists in the ordinary suites on purpose: a
     * regression check is a statement that a specific past failure stays fixed.
     */
    private static void regressions() throws Exception {
        group("regression/DefectRegressionTest");

        // DEF-001: exposure absent when an input amount is unknown (requirements §4.4).
        check(
                "DEF-001 captured orphan reports no exposure",
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.ABSENT))
                        .amountAtRisk()
                        .isEmpty());
        check(
                "DEF-001 fully refunded orphan still reports a legitimate zero",
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.ABSENT))
                        .amountAtRisk()
                        .orElseThrow()
                        .isZero());

        // DEF-002: the no-history note belongs in decisionPath only (FR-05.4).
        ReconciliationFinding clean =
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.PAID_UNFULFILLED));
        check("DEF-002 a clean match carries no reasons", clean.reasons().isEmpty());
        check(
                "DEF-002 the limitation is still recorded in the decision path",
                clean.decisionPath().stream().anyMatch(s -> s.contains("duplicate captures undetectable")));

        // DEF-003: typed validation errors, never a raw NPE (FR-08).
        throwsType(
                "DEF-003 null payment yields a typed error",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(
                        new ReconciliationInput(
                                null, SpecFixtures.order(OrderStatus.FULFILLED), List.of(),
                                SpecFixtures.policy(), SpecFixtures.NOW)));
        throwsType(
                "DEF-003 null history yields a typed error",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(
                        new ReconciliationInput(
                                SpecFixtures.payment(PaymentStatus.CAPTURED),
                                SpecFixtures.order(OrderStatus.FULFILLED), null,
                                SpecFixtures.policy(), SpecFixtures.NOW)));
        try {
            ENGINE.reconcile(
                    new ReconciliationInput(
                            null, SpecFixtures.order(OrderStatus.FULFILLED), List.of(),
                            SpecFixtures.policy(), SpecFixtures.NOW));
            check("DEF-003 expected a rejection", false);
        } catch (InvalidReconciliationInputException e) {
            equal("DEF-003 the rejected field is named", "input.payment", e.field());
        }

        // DEF-004: BR-07b is enforced, so grace cannot be re-entered by moving domain time.
        throwsType(
                "DEF-004 a hand-built order with a divergent business-state time is rejected",
                InvalidReconciliationInputException.class,
                () -> ENGINE.reconcile(
                        SpecFixtures.input(
                                SpecFixtures.payment(PaymentStatus.CAPTURED, SpecFixtures.ONE_HOUR_AGO),
                                new OrderObservation(
                                        SpecFixtures.ORDER_REF, OrderStatus.AWAITING_PAYMENT,
                                        SpecFixtures.try_(125_000), SpecFixtures.ONE_HOUR_AGO,
                                        SpecFixtures.NOW, SpecFixtures.NOW, SpecFixtures.NOW))));
        equal(
                "DEF-004 an hour-old discrepancy cannot be pushed back into grace",
                FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        SpecFixtures.payment(PaymentStatus.CAPTURED, SpecFixtures.ONE_HOUR_AGO),
                                        SpecFixtures.order(OrderStatus.AWAITING_PAYMENT, SpecFixtures.ONE_HOUR_AGO)))
                        .type());

        // DEF-005: every monetary field participates in the currency gate (FR-03).
        equal(
                "DEF-005 requested TRY with collected EUR is a currency mismatch",
                FindingType.CURRENCY_MISMATCH,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        new PaymentObservation(
                                                "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                                                SpecFixtures.try_(125_000), SpecFixtures.eur(125_000),
                                                null, 0, SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                        .type());
        equal(
                "DEF-005 refunded in another currency is a currency mismatch",
                FindingType.CURRENCY_MISMATCH,
                ENGINE.reconcile(
                                SpecFixtures.input(
                                        new PaymentObservation(
                                                "GW", SpecFixtures.ORDER_REF, PaymentStatus.PARTIALLY_REFUNDED,
                                                SpecFixtures.try_(125_000), SpecFixtures.try_(125_000),
                                                SpecFixtures.eur(1_000), 0, SpecFixtures.ONE_HOUR_AGO,
                                                SpecFixtures.NOW, false),
                                        SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                        .type());
        doesNotThrow(
                "DEF-005 no arithmetic exception escapes a cross-currency input",
                () -> ENGINE.reconcile(
                        SpecFixtures.input(
                                new PaymentObservation(
                                        "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                                        SpecFixtures.try_(125_000), SpecFixtures.eur(125_000), null, 0,
                                        SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false),
                                SpecFixtures.order(OrderStatus.PAID_UNFULFILLED))));
        equal(
                "DEF-005 control: all amounts in one currency still reconciles",
                FindingType.MATCHED,
                ENGINE.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.PAID_UNFULFILLED))
                        .type());

        // testMode is classification-neutral in Scope A.
        group("TestModeNeutralityTest");
        for (PaymentStatus status : List.of(PaymentStatus.CAPTURED, PaymentStatus.AUTHORIZED, PaymentStatus.REFUNDED)) {
            for (OrderStatus order : List.of(OrderStatus.AWAITING_PAYMENT, OrderStatus.PAID_UNFULFILLED, OrderStatus.ABSENT)) {
                ReconciliationFinding live = ENGINE.reconcile(testModeCase(status, order, false));
                ReconciliationFinding test = ENGINE.reconcile(testModeCase(status, order, true));
                equal("testMode does not change " + status + "/" + order, live.type(), test.type());
                equal("testMode does not change severity " + status + "/" + order, live.severity(), test.severity());
                equal("testMode does not change exposure " + status + "/" + order, live.amountAtRisk(), test.amountAtRisk());
            }
        }
    }

    private static ReconciliationInput testModeCase(PaymentStatus status, OrderStatus order, boolean testMode)
            throws Exception {
        PaymentObservation payment =
                new PaymentObservation(
                        "GW", SpecFixtures.ORDER_REF, status, SpecFixtures.try_(SpecFixtures.AMOUNT),
                        SpecFixtures.try_(SpecFixtures.AMOUNT),
                        status == PaymentStatus.REFUNDED ? SpecFixtures.try_(SpecFixtures.AMOUNT) : null,
                        0, SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, testMode);
        return SpecFixtures.input(payment, SpecFixtures.order(order));
    }
}
