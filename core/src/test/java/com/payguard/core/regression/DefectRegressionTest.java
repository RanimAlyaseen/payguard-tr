package com.payguard.core.regression;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payguard.core.engine.ReconciliationEngine;
import com.payguard.core.engine.ReconciliationInput;
import com.payguard.core.error.InvalidReconciliationInputException;
import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.policy.ReconciliationPolicy;
import com.payguard.core.result.FindingType;
import com.payguard.core.result.ReconciliationFinding;
import com.payguard.core.spec.SpecFixtures;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * One nested class per recorded implementation defect.
 *
 * <p>{@code testing-strategy.md} requires every discovered defect to receive a regression test in
 * its own package, and these deliberately duplicate assertions that also exist in the equivalence,
 * negative and property suites. The duplication is the point: a test in {@code AmountAtRiskTest}
 * says what the exposure rule should do, while a test here says that one specific past failure
 * stays fixed. The two can be deleted for different reasons, so they are kept separately.
 *
 * <p>Each class names the defect, the requirement it violated, and what the wrong behaviour was,
 * so that a future failure here is self-explaining without the defect log to hand.
 */
class DefectRegressionTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Nested
    @DisplayName("DEF-001: exposure must be absent when it cannot be computed (§4.4)")
    class Def001ExposureAbsence {

        /** Was: returned the collected amount, treating an unrecorded refund as a refund of zero. */
        @Test
        @DisplayName("a captured orphan payment with no refund figure reports no exposure")
        void capturedOrphanHasNoComputableExposure() throws Exception {
            ReconciliationFinding finding =
                    engine.reconcile(SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.ABSENT));
            assertEquals(FindingType.PAYMENT_WITHOUT_ORDER, finding.type());
            assertTrue(
                    finding.amountAtRisk().isEmpty(),
                    "an absent refund amount means the net exposure is unknown, not zero");
        }

        @Test
        @DisplayName("a fully refunded orphan still reports a legitimate zero")
        void fullyRefundedOrphanReportsZero() throws Exception {
            ReconciliationFinding finding =
                    engine.reconcile(SpecFixtures.tableCase(PaymentStatus.REFUNDED, OrderStatus.ABSENT));
            assertTrue(finding.amountAtRisk().isPresent(), "both amounts are known here");
            assertTrue(
                    finding.amountAtRisk().orElseThrow().isZero(),
                    "the fix must not over-apply and suppress a computable zero");
        }
    }

    @Nested
    @DisplayName("DEF-002: the no-history limitation belongs in decisionPath only (FR-05.4)")
    class Def002LimitationPlacement {

        /** Was: also pushed into reasons, so a clean MATCHED carried a non-reason. */
        @Test
        @DisplayName("a clean match carries no reasons but still records the limitation")
        void cleanMatchCarriesNoReasons() throws Exception {
            ReconciliationFinding finding =
                    engine.reconcile(
                            SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.PAID_UNFULFILLED));
            assertEquals(FindingType.MATCHED, finding.type());
            assertTrue(finding.reasons().isEmpty(), "reasons explain a finding; this one needs none");
            assertTrue(
                    finding.decisionPath().stream()
                            .anyMatch(step -> step.contains("duplicate captures undetectable")),
                    "the limitation is documented, just not as a reason");
        }
    }

    @Nested
    @DisplayName("DEF-003: malformed input raises a typed, field-naming error (FR-08)")
    class Def003TypedValidation {

        /** Was: Objects.requireNonNull in the record, so a raw NPE escaped the module. */
        @Test
        @DisplayName("a null component names the field it rejected")
        void nullComponentNamesTheField() {
            assertEquals(
                    "input.payment",
                    assertThrows(
                                    InvalidReconciliationInputException.class,
                                    () -> engine.reconcile(
                                            new ReconciliationInput(
                                                    null, SpecFixtures.order(OrderStatus.FULFILLED),
                                                    List.of(), ReconciliationPolicy.defaults(),
                                                    SpecFixtures.NOW)))
                            .field());
        }

        /** Was: a null history was silently normalised to empty, hiding a caller's bug. */
        @Test
        @DisplayName("null history is malformed; empty history is valid")
        void nullAndEmptyHistoryAreDifferent() {
            assertEquals(
                    "input.history",
                    assertThrows(
                                    InvalidReconciliationInputException.class,
                                    () -> engine.reconcile(
                                            new ReconciliationInput(
                                                    SpecFixtures.payment(PaymentStatus.CAPTURED),
                                                    SpecFixtures.order(OrderStatus.FULFILLED), null,
                                                    ReconciliationPolicy.defaults(), SpecFixtures.NOW)))
                            .field());
            assertDoesNotThrow(
                    () -> engine.reconcile(
                            new ReconciliationInput(
                                    SpecFixtures.payment(PaymentStatus.CAPTURED),
                                    SpecFixtures.order(OrderStatus.FULFILLED), List.of(),
                                    ReconciliationPolicy.defaults(), SpecFixtures.NOW)));
        }

        @Test
        @DisplayName("a null element inside the history is malformed too")
        void nullHistoryElementIsMalformed() {
            assertThrows(
                    InvalidReconciliationInputException.class,
                    () -> engine.reconcile(
                            new ReconciliationInput(
                                    SpecFixtures.payment(PaymentStatus.CAPTURED),
                                    SpecFixtures.order(OrderStatus.FULFILLED),
                                    Arrays.asList(SpecFixtures.capture("c1", 1), null),
                                    ReconciliationPolicy.defaults(),
                                    SpecFixtures.NOW)));
        }
    }

    @Nested
    @DisplayName("DEF-004: BR-07b is enforced at validation, not only by the factory")
    class Def004BusinessStateTime {

        /**
         * Was: the static factory pinned businessStateChangedAt to the creation time, but the
         * record's canonical constructor is public and the engine accepted any value. INV-12 and
         * the removal of the grace ceiling both depend on the caller being unable to move it.
         */
        @Test
        @DisplayName("a divergent business-state time is rejected")
        void divergentBusinessStateTimeIsRejected() {
            assertEquals(
                    "order.businessStateChangedAt",
                    assertThrows(
                                    InvalidReconciliationInputException.class,
                                    () -> engine.reconcile(
                                            SpecFixtures.input(
                                                    SpecFixtures.payment(PaymentStatus.CAPTURED),
                                                    new OrderObservation(
                                                            SpecFixtures.ORDER_REF,
                                                            OrderStatus.AWAITING_PAYMENT,
                                                            SpecFixtures.try_(125_000),
                                                            SpecFixtures.ONE_HOUR_AGO,
                                                            SpecFixtures.NOW,
                                                            SpecFixtures.NOW,
                                                            SpecFixtures.NOW))))
                            .field());
        }

        @Test
        @DisplayName("a caller cannot move the business-state time forward to re-enter grace")
        void graceWindowCannotBeReEntered() throws Exception {
            // The legitimate route: an hour-old discrepancy, reported.
            assertEquals(
                    FindingType.ORDER_UNPAID_DESPITE_PAYMENT,
                    engine.reconcile(
                                    SpecFixtures.input(
                                            SpecFixtures.payment(
                                                    PaymentStatus.CAPTURED, SpecFixtures.ONE_HOUR_AGO),
                                            SpecFixtures.order(
                                                    OrderStatus.AWAITING_PAYMENT, SpecFixtures.ONE_HOUR_AGO)))
                            .type());

            // The attack: an order created an hour ago, claiming its business state changed just now.
            // Were this accepted, the discrepancy age would reset to zero and the finding would be
            // suppressed as WITHIN_GRACE_PERIOD on every evaluation, forever.
            assertThrows(
                    InvalidReconciliationInputException.class,
                    () -> engine.reconcile(
                            SpecFixtures.input(
                                    SpecFixtures.payment(PaymentStatus.CAPTURED, SpecFixtures.ONE_HOUR_AGO),
                                    new OrderObservation(
                                            SpecFixtures.ORDER_REF, OrderStatus.AWAITING_PAYMENT,
                                            SpecFixtures.try_(125_000), SpecFixtures.ONE_HOUR_AGO,
                                            SpecFixtures.NOW, SpecFixtures.NOW, SpecFixtures.NOW))),
                    "accepting this would restore the defect the grace ceiling was invented to prevent");
        }
    }

    @Nested
    @DisplayName("DEF-005: every monetary field participates in the currency gate (FR-03)")
    class Def005CurrencyGate {

        /**
         * Was: the gate compared one currency per side. A payment requested in TRY but collected in
         * EUR passed it, and the mismatch surfaced as a CurrencyMismatchException thrown out of the
         * amount rule — an untyped arithmetic failure in place of a finding.
         */
        @Test
        @DisplayName("requested TRY with collected EUR is a finding, not an escaping exception")
        void requestedAndCollectedDiffer() throws Exception {
            ReconciliationInput input =
                    SpecFixtures.input(
                            new PaymentObservation(
                                    "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                                    SpecFixtures.try_(125_000), SpecFixtures.eur(125_000), null, 0,
                                    SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW, false),
                            SpecFixtures.order(OrderStatus.PAID_UNFULFILLED));
            assertDoesNotThrow(() -> engine.reconcile(input));
            assertEquals(FindingType.CURRENCY_MISMATCH, engine.reconcile(input).type());
        }

        @Test
        @DisplayName("a refund in another currency is caught too")
        void refundedCurrencyDiffers() throws Exception {
            assertEquals(
                    FindingType.CURRENCY_MISMATCH,
                    engine.reconcile(
                                    SpecFixtures.input(
                                            new PaymentObservation(
                                                    "GW", SpecFixtures.ORDER_REF,
                                                    PaymentStatus.PARTIALLY_REFUNDED,
                                                    SpecFixtures.try_(125_000), SpecFixtures.try_(125_000),
                                                    SpecFixtures.eur(1_000), 0, SpecFixtures.ONE_HOUR_AGO,
                                                    SpecFixtures.NOW, false),
                                            SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                            .type());
        }

        @Test
        @DisplayName("payment TRY against an order in EUR is still caught")
        void paymentAndOrderDiffer() throws Exception {
            assertEquals(
                    FindingType.CURRENCY_MISMATCH,
                    engine.reconcile(
                                    SpecFixtures.input(
                                            new PaymentObservation(
                                                    "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED,
                                                    SpecFixtures.eur(125_000), SpecFixtures.eur(125_000),
                                                    null, 0, SpecFixtures.ONE_HOUR_AGO, SpecFixtures.NOW,
                                                    false),
                                            SpecFixtures.order(OrderStatus.PAID_UNFULFILLED)))
                            .type());
        }

        @Test
        @DisplayName("control: all four amounts in one currency reconciles normally")
        void sameCurrencyControl() throws Exception {
            ReconciliationFinding finding =
                    engine.reconcile(
                            SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.PAID_UNFULFILLED));
            assertEquals(FindingType.MATCHED, finding.type());
            assertFalse(
                    finding.reasons().contains("currency_mismatch"),
                    "the widened gate must not produce false positives");
        }
    }
}
