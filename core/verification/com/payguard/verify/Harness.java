package com.payguard.verify;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal, dependency-free assertion and reporting harness.
 *
 * <p><strong>Why this exists.</strong> JUnit 5 Jupiter cannot be obtained in this sandbox:
 * {@code repo.maven.apache.org} is refused by the egress proxy with HTTP 403, and no Jupiter jar is
 * bundled locally. The canonical suite is still the JUnit 5 one under {@code src/test/java} — this
 * harness executes the <em>same</em> specification fixtures so that real pass/fail numbers exist
 * rather than claimed ones. It is not a substitute for the JUnit run and is not presented as one.
 */
public final class Harness {

    /** One failed check. */
    public record Failure(String group, String check, String detail) {}

    private static final List<Failure> FAILURES = new ArrayList<>();
    private static int checks;
    private static int groups;
    private static String current = "(none)";
    private static int groupChecks;
    private static int groupFailures;

    private Harness() {}

    public static void group(String name) {
        if (!"(none)".equals(current)) {
            report();
        }
        current = name;
        groupChecks = 0;
        groupFailures = 0;
        groups++;
    }

    private static void report() {
        System.out.printf(
                "  %-36s %3d checks  %s%n",
                current, groupChecks, groupFailures == 0 ? "ok" : groupFailures + " FAILED");
    }

    public static void check(String what, boolean condition) {
        checks++;
        groupChecks++;
        if (!condition) {
            groupFailures++;
            FAILURES.add(new Failure(current, what, "condition was false"));
        }
    }

    public static void equal(String what, Object expected, Object actual) {
        checks++;
        groupChecks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            groupFailures++;
            FAILURES.add(new Failure(current, what, "expected " + expected + " but was " + actual));
        }
    }

    /** Asserts that {@code body} throws the given type. */
    public static void throwsType(String what, Class<? extends Throwable> expected, ThrowingRunnable body) {
        checks++;
        groupChecks++;
        try {
            body.run();
            groupFailures++;
            FAILURES.add(new Failure(current, what, "expected " + expected.getSimpleName() + ", nothing thrown"));
        } catch (Throwable thrown) {
            if (!expected.isInstance(thrown)) {
                groupFailures++;
                FAILURES.add(
                        new Failure(
                                current,
                                what,
                                "expected "
                                        + expected.getSimpleName()
                                        + " but got "
                                        + thrown.getClass().getSimpleName()
                                        + ": "
                                        + thrown.getMessage()));
            }
        }
    }

    /** Asserts that {@code body} completes without throwing. */
    public static void doesNotThrow(String what, ThrowingRunnable body) {
        checks++;
        groupChecks++;
        try {
            body.run();
        } catch (Throwable thrown) {
            groupFailures++;
            FAILURES.add(
                    new Failure(
                            current,
                            what,
                            "unexpected " + thrown.getClass().getSimpleName() + ": " + thrown.getMessage()));
        }
    }

    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    /** @return process exit code: 0 when every check passed */
    public static int summarise() {
        report();
        System.out.println();
        System.out.println("-".repeat(72));
        System.out.printf(
                "groups: %d    checks executed: %d    passed: %d    failed: %d%n",
                groups, checks, checks - FAILURES.size(), FAILURES.size());
        if (!FAILURES.isEmpty()) {
            System.out.println();
            System.out.println("FAILURES");
            for (Failure f : FAILURES) {
                System.out.printf("  [%s] %s%n      %s%n", f.group(), f.check(), f.detail());
            }
        }
        System.out.println("-".repeat(72));
        return FAILURES.isEmpty() ? 0 : 1;
    }

    public static int failureCount() {
        return FAILURES.size();
    }

    public static int checkCount() {
        return checks;
    }
}
