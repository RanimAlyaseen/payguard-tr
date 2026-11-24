package com.payguard.core.engine;

import com.payguard.core.model.TransactionRecord;
import java.util.List;

/**
 * The outcome of normalising a transaction history (FR-05, FR-06).
 *
 * @param records the deduplicated records in canonical order
 * @param conflictingIdentities identities whose members differ in significant content — a
 *     data-integrity fault that forces INSUFFICIENT_DATA (FR-05.3)
 * @param collapsedRepetitions how many content-identical repetitions were removed, for audit
 */
public record NormalizedHistory(
        List<TransactionRecord> records, List<String> conflictingIdentities, int collapsedRepetitions) {

    public NormalizedHistory {
        records = List.copyOf(records);
        conflictingIdentities = List.copyOf(conflictingIdentities);
    }

    public boolean hasConflicts() {
        return !conflictingIdentities.isEmpty();
    }

    public List<TransactionRecord> captures() {
        return records.stream().filter(TransactionRecord::isCapture).toList();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }
}
