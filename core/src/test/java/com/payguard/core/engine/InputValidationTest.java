package com.payguard.core.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payguard.core.error.InvalidReconciliationInputException;
import com.payguard.core.model.OrderObservation;
import com.payguard.core.model.OrderStatus;
import com.payguard.core.model.PaymentObservation;
import com.payguard.core.model.PaymentStatus;
import com.payguard.core.policy.ReconciliationPolicy;
import com.payguard.core.spec.SpecFixtures;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-08: malformed input raises a typed, named exception; it never produces a finding.
 *
 * <p>The negative tests are the point of this class. The distinction it enforces is between data
 * PayGuard cannot interpret (an exception, because a finding would be a lie) and data that is
 * perfectly valid but uninformative (a finding of INSUFFICIENT_DATA). Getting that line wrong in
 * either direction is a real failure: throwing on an unrecognised status would make PayGuard fall
 * over on any WooCommerce plugin, and returning a finding for a negative amount would launder bad
 * data into a report a merchant acts on.
 */
class InputValidationTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Nested
    @DisplayName("rejected input")
    class Rejected {

        @Test
        @DisplayName("a null input is rejected")
        void nullInput() {
            assertThrows(InvalidReconciliationInputException.class, () -> engine.reconcile(null));
        }

        @ParameterizedTest
        @ValueSource(longs = {-1L, -125_000L})
        @DisplayName("a negative amount is rejected and the field is named")
        void negativeAmount(long minorUnits) {
            InvalidReconciliationInputException thrown =
                    assertThrows(
                            InvalidReconciliationInputException.class,
                            () -> SpecFixtures.try_(minorUnits));
            assertEquals("money.minorUnits", thrown.field());
        }

        @Test
        @DisplayName("a negative instalment count is rejected")
        void negativeInstalmentCount() {
            assertThrows(
                    InvalidReconciliationInputException.class,
                    () -> new PaymentObservation(
                                    "GW", SpecFixtures.ORDER_REF, PaymentStatus.CAPTURED, null, null,
                                    null, -1, null, SpecFixtures.NOW, false)
                            .validated());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank merchant order reference is rejected: nothing can be matched without it")
        void blankOrderReference(String reference) {
            assertThrows(
                    InvalidReconciliationInputException.class,
                    () -> new PaymentObservation(
                                    "GW", reference, PaymentStatus.CAPTURED, null, null, null, 0, null,
                                    SpecFixtures.NOW, false)
                            .validated());
        }

        @Test
        @DisplayName("a non-positive grace period is rejected")
        void nonPositiveGracePeriod() {
            assertThrows(
                    InvalidReconciliationInputException.class,
                    () -> SpecFixtures.policy().withGracePeriod(Duration.ZERO).validated());
            assertThrows(
                    InvalidReconciliationInputException.class,
                    () -> SpecFixtures.policy().withGracePeriod(Duration.ofMinutes(-1)).validated());
        }

        @Test
        @DisplayName("a negative amount tolerance is rejected")
        void negativeTolerance() {
            assertThrows(
                    InvalidReconciliationInputException.class,
                    () -> SpecFixtures.policy().withAmountTolerance(-1).validated());
        }

        @Test
        @DisplayName("DEF-003: every null component yields a typed error naming the field")
        void nullComponentsAreTypedErrors() {
            // FR-08 states that no raw exception escapes the module. The original version of this
            // test asserted NullPointerException - transcribed from what the code did rather than
            // from the requirement, so it endorsed the defect instead of catching it.
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
            assertEquals(
                    "input.order",
                    assertThrows(
                                    InvalidReconciliationInputException.class,
                                    () -> engine.reconcile(
                                            new ReconciliationInput(
                                                    SpecFixtures.payment(PaymentStatus.CAPTURED), null,
                                                    List.of(), ReconciliationPolicy.defaults(),
                                                    SpecFixtures.NOW)))
                            .field());
            assertEquals(
                    "input.policy",
                    assertThrows(
                                    InvalidReconciliationInputException.class,
                                    () -> engine.reconcile(
                                            new ReconciliationInput(
                                                    SpecFixtures.payment(PaymentStatus.CAPTURED),
                                                    SpecFixtures.order(OrderStatus.FULFILLED), List.of(),
                                                    null, SpecFixtures.NOW)))
                            .field());
            assertEquals(
                    "input.evaluatedAt",
                    assertThrows(
                                    InvalidReconciliationInputException.class,
                                    () -> engine.reconcile(
                                            new ReconciliationInput(
                                                    SpecFixtures.payment(PaymentStatus.CAPTURED),
                                                    SpecFixtures.order(OrderStatus.FULFILLED), List.of(),
                                                    ReconciliationPolicy.defaults(), null)))
                            .field());
        }

        @Test
        @DisplayName("DEF-003: a null history is malformed, and so is a null element inside one")
        void nullHistoryIsMalformed() {
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

        @Test
        @DisplayName("DEF-004: BR-07b is enforced, not merely offered by the factory")
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
    }

    @Nested
    @DisplayName("accepted input that merely lacks information")
    class Accepted {

        @Test
        @DisplayName("an unrecognised status is valid input and yields a finding")
        void unrecognisedStatusIsValid() {
            assertDoesNotThrow(
                    () -> engine.reconcile(
                            SpecFixtures.tableCase(PaymentStatus.UNKNOWN, OrderStatus.FULFILLED)));
        }

        @Test
        @DisplayName("an empty history is valid input")
        void emptyHistoryIsValid() {
            assertDoesNotThrow(
                    () -> engine.reconcile(
                            SpecFixtures.tableCase(PaymentStatus.CAPTURED, OrderStatus.FULFILLED)));
        }

        @Test
        @DisplayName("an empty history is valid: it limits detection, it is not malformed")
        void emptyHistoryRemainsValid() {
            assertDoesNotThrow(
                    () -> engine.reconcile(
                            new ReconciliationInput(
                                    SpecFixtures.payment(PaymentStatus.CAPTURED),
                                    SpecFixtures.order(OrderStatus.FULFILLED),
                                    List.of(),
                                    ReconciliationPolicy.defaults(),
                                    SpecFixtures.NOW)));
        }

        @Test
        @DisplayName("a payment with no amounts at all is valid")
        void missingAmountsAreValid() {
            assertDoesNotThrow(
                    () -> engine.reconcile(
                            SpecFixtures.input(
                                    new PaymentObservation(
                                            "GW", SpecFixtures.ORDER_REF, PaymentStatus.FAILED, null,
                                            null, null, 0, SpecFixtures.ONE_HOUR_AGO,
                                            SpecFixtures.NOW, false),
                                    SpecFixtures.order(OrderStatus.AWAITING_PAYMENT))));
        }
    }
}
