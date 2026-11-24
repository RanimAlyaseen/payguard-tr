package com.payguard.core.error;

/**
 * Input outside the valid domain (FR-08). Always names the offending field, because an operator
 * reading a log needs the field and not a stack trace.
 */
public final class InvalidReconciliationInputException extends PayGuardCoreException {
    private static final long serialVersionUID = 1L;
    private final String field;

    public InvalidReconciliationInputException(String field, String problem) {
        super("invalid input: " + field + " " + problem);
        this.field = field;
    }

    /** The field that was rejected. */
    public String field() {
        return field;
    }
}
