package com.payguard.core.rules;

import com.payguard.core.engine.NormalizedHistory;
import com.payguard.core.engine.ReconciliationInput;
import java.util.ArrayList;
import java.util.List;

/** Carries the input, the normalised history, and the accumulating explanation. */
public final class RuleContext {

    private final ReconciliationInput input;
    private final NormalizedHistory history;
    private final List<String> decisionPath = new ArrayList<>();
    private final List<String> reasons = new ArrayList<>();

    public RuleContext(ReconciliationInput input, NormalizedHistory history) {
        this.input = input;
        this.history = history;
    }

    public ReconciliationInput input() {
        return input;
    }

    public NormalizedHistory history() {
        return history;
    }

    public void note(String step) {
        decisionPath.add(step);
    }

    public void reason(String reason) {
        reasons.add(reason);
    }

    public List<String> decisionPath() {
        return List.copyOf(decisionPath);
    }

    public List<String> reasons() {
        return List.copyOf(reasons);
    }
}
