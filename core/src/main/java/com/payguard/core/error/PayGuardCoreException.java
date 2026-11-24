package com.payguard.core.error;

/**
 * Base type for every error the reconciliation core raises.
 *
 * <p>Checked, as specified in SAD §5.4. The consequence is deliberate: a caller cannot ignore
 * malformed input or unsafe arithmetic by accident, because the compiler will not let them. It also
 * keeps FR-08's boundary of the valid domain visible in every signature that can leave it.
 */
public class PayGuardCoreException extends Exception {
    private static final long serialVersionUID = 1L;

    public PayGuardCoreException(String message) {
        super(message);
    }
}
