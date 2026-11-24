package com.payguard.verify;

import java.util.ArrayList;
import java.util.List;

/** Exhaustive permutations for small n, used by the order-independence properties (INV-09). */
public final class Permutations {

    private Permutations() {}

    public static <T> List<List<T>> of(List<T> items) {
        List<List<T>> out = new ArrayList<>();
        permute(new ArrayList<>(items), 0, out);
        return out;
    }

    private static <T> void permute(List<T> working, int index, List<List<T>> out) {
        if (index == working.size()) {
            out.add(new ArrayList<>(working));
            return;
        }
        for (int i = index; i < working.size(); i++) {
            swap(working, index, i);
            permute(working, index + 1, out);
            swap(working, index, i);
        }
    }

    private static <T> void swap(List<T> list, int a, int b) {
        T tmp = list.get(a);
        list.set(a, list.get(b));
        list.set(b, tmp);
    }
}
