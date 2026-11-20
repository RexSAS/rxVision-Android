package com.andrea.rxvision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ranks app names against what was typed: prefix, then word prefix, then substring, then letters in order. */
final class Search {
    private Search() {}

    /** Lower is better; -1 means no match. An empty query matches everything equally. */
    static int score(String label, String query) {
        String n = label.toLowerCase(Locale.ROOT);
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || n.startsWith(q)) return 0;
        for (String w : n.split("[\\s._-]+")) if (w.startsWith(q)) return 1;
        if (n.contains(q)) return 2;
        int j = 0;
        for (int i = 0; i < n.length() && j < q.length(); i++) if (n.charAt(i) == q.charAt(j)) j++;
        return j == q.length() ? 3 : -1;
    }

    /** Indices of the labels that match, best first; equal scores keep the list's order. */
    static List<Integer> rank(List<String> labels, String query) {
        List<Integer> out = new ArrayList<>();
        int[] scores = new int[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            scores[i] = score(labels.get(i), query);
            if (scores[i] >= 0) out.add(i);
        }
        out.sort((a, b) -> scores[a] - scores[b]); // List.sort is stable
        return out;
    }
}
