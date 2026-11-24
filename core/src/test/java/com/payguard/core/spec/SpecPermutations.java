package com.payguard.core.spec;

import java.util.ArrayList;
import java.util.List;

/**
 * Exhaustive permutations, for the order-independence properties (INV-09).
 *
 * <p>Exhaustive rather than random: the record lists under test have four elements at most, so all
 * 24 orderings cost nothing and a failure is reproducible without a seed.
 */
public final class SpecPermutations {

    private SpecPermutations() {}

    public static <T> List<List<T>> of(List<T> source) {
        List<List<T>> out = new ArrayList<>();
        permute(new ArrayList<>(source), 0, out);
        return out;
    }

    private static <T> void permute(List<T> working, int index, List<List<T>> out) {
        if (index == working.size()) {
            out.add(List.copyOf(working));
            return;
        }
        for (int i = index; i < working.size(); i++) {
            swap(working, index, i);
            permute(working, index + 1, out);
            swap(working, index, i);
        }
    }

    private static <T> void swap(List<T> list, int a, int b) {
        T temp = list.get(a);
        list.set(a, list.get(b));
        list.set(b, temp);
    }
}
