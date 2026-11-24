package com.payguard.core.result;

/**
 * How much a merchant should care.
 *
 * <p>{@code LOW} is part of the specified vocabulary but no {@link FindingType} currently maps to it:
 * INSUFFICIENT_DATA was raised from LOW to MEDIUM in revision 0.3, because every route to it is now
 * a real integrity problem. Retained so the scale is complete.
 */
public enum Severity {
    INFO,
    LOW,
    MEDIUM,
    HIGH
}
