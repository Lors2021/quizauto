package com.lors.quizauto.match;

import androidx.annotation.NonNull;

public final class QuestionMatcher {

    private QuestionMatcher() {}

    public static double similarity(@NonNull String a, @NonNull String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return 0.0;

        if (na.equals(nb)) return 1.0;
        if (na.contains(nb) || nb.contains(na)) {
            double ratio = (double) Math.min(na.length(), nb.length())
                    / Math.max(na.length(), nb.length());
            return 0.85 + 0.15 * ratio;
        }

        double jw = jaroWinkler(na, nb);
        double lev = 1.0 - ((double) levenshtein(na, nb)
                / Math.max(na.length(), nb.length()));
        double tokenBonus = tokenOverlap(na, nb) * 0.15;

        return Math.min(1.0, 0.55 * jw + 0.30 * lev + tokenBonus);
    }

    @NonNull
    public static String normalize(@NonNull String s) {
        String lower = s.toLowerCase().replace('ё', 'е').replace('й', 'и');
        StringBuilder sb = new StringBuilder(lower.length());
        boolean prevSpace = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                prevSpace = false;
            } else if (!prevSpace) {
                sb.append(' ');
                prevSpace = true;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    static double jaroWinkler(@NonNull String s1, @NonNull String s2) {
        double j = jaro(s1, s2);
        int prefix = 0;
        int max = Math.min(4, Math.min(s1.length(), s2.length()));
        for (int i = 0; i < max; i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }
        return j + prefix * 0.1 * (1 - j);
    }

    static double jaro(@NonNull String s1, @NonNull String s2) {
        int l1 = s1.length(), l2 = s2.length();
        if (l1 == 0 && l2 == 0) return 1.0;
        if (l1 == 0 || l2 == 0) return 0.0;
        int matchDist = Math.max(l1, l2) / 2 - 1;
        if (matchDist < 0) matchDist = 0;

        boolean[] m1 = new boolean[l1];
        boolean[] m2 = new boolean[l2];
        int matches = 0;
        for (int i = 0; i < l1; i++) {
            int lo = Math.max(0, i - matchDist);
            int hi = Math.min(i + matchDist + 1, l2);
            for (int j = lo; j < hi; j++) {
                if (m2[j]) continue;
                if (s1.charAt(i) != s2.charAt(j)) continue;
                m1[i] = true; m2[j] = true; matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;

        int k = 0, transpositions = 0;
        for (int i = 0; i < l1; i++) {
            if (!m1[i]) continue;
            while (!m2[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }
        double m = matches;
        return (m / l1 + m / l2 + (m - transpositions / 2.0) / m) / 3.0;
    }

    static int levenshtein(@NonNull String a, @NonNull String b) {
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[m];
    }

    static double tokenOverlap(@NonNull String a, @NonNull String b) {
        String[] ta = a.split(" ");
        String[] tb = b.split(" ");
        if (ta.length == 0 || tb.length == 0) return 0.0;
        int hits = 0;
        for (String x : ta) {
            if (x.isEmpty()) continue;
            for (String y : tb) {
                if (x.equals(y)) { hits++; break; }
            }
        }
        return (double) hits / Math.max(ta.length, tb.length);
    }
}
