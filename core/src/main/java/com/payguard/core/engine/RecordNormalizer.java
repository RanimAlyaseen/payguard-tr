package com.payguard.core.engine;

import com.payguard.core.model.TransactionRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups transaction history by {@code recordIdentity}, collapses content-identical repetitions,
 * reports same-identity conflicts, and puts the survivors in a canonical order (FR-05, FR-06).
 *
 * <p>The canonical sort key is {@code (occurredAt, recordIdentity)} — both <strong>set-intrinsic
 * </strong>. It deliberately does <em>not</em> use {@code retrievalSequence}: revision 0.2 tie-broke
 * on retrieval order, so its ordering rule depended on the very thing permutation invariance says
 * must not matter, and INV-09 was unenforceable by its own tie-break. With a set-intrinsic key the
 * sorted sequence is a function of the set alone, which makes INV-09 provable rather than asserted.
 *
 * <p>Repetition is routine, not exceptional: overlapping discovery windows re-retrieve the same rows
 * by design (ADR-014), which is why inertness under repetition is a working requirement.
 */
public final class RecordNormalizer {

    private RecordNormalizer() {}

    public static NormalizedHistory normalize(List<TransactionRecord> history) {
        if (history == null || history.isEmpty()) {
            return new NormalizedHistory(List.of(), List.of(), 0);
        }

        // Insertion-ordered so the conflict list is itself deterministic.
        Map<String, List<TransactionRecord>> byIdentity = new LinkedHashMap<>();
        for (TransactionRecord record : history) {
            byIdentity.computeIfAbsent(record.recordIdentity(), k -> new ArrayList<>()).add(record);
        }

        List<TransactionRecord> kept = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        int collapsed = 0;

        for (Map.Entry<String, List<TransactionRecord>> group : byIdentity.entrySet()) {
            List<TransactionRecord> members = group.getValue();
            String first = members.get(0).significantContent();
            boolean allIdentical = members.stream().allMatch(r -> r.significantContent().equals(first));
            if (allIdentical) {
                kept.add(members.get(0));
                collapsed += members.size() - 1;
            } else {
                // No member contributes: there is no defensible basis for choosing between two
                // records a provider claims are the same event (FR-05.3).
                conflicts.add(group.getKey());
            }
        }

        kept.sort(
                Comparator.comparing(TransactionRecord::occurredAt)
                        .thenComparing(TransactionRecord::recordIdentity));

        // Conflicts sorted too, so the reason list does not depend on input order.
        conflicts.sort(Comparator.naturalOrder());

        return new NormalizedHistory(kept, conflicts, collapsed);
    }
}
