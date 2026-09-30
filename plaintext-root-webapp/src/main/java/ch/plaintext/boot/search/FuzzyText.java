/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.search;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Small in-memory fuzzy matching for short lists such as the menu entries (card 1348).
 * <p>
 * Two building blocks:
 * <ul>
 *   <li>{@link #normalize(String)}: lower case, accents and umlauts folded ({@code ä -> a},
 *       {@code ß -> ss}), everything that is neither letter nor digit becomes a single space.
 *       Applied to query and target alike, so "Menü" and "menu" meet.</li>
 *   <li>{@link #substringDistance(String, String)}: the smallest Damerau-Levenshtein distance
 *       (optimal string alignment) between the query and <b>any substring</b> of the target
 *       (Sellers' approximate substring matching: the start in the target is free). This is what
 *       makes "wandereise" hit "wanderreisen" (one missing letter) while the user is still typing,
 *       and "rollenzuteilnug" hit "Rollenzuteilung" (one swapped pair).</li>
 * </ul>
 * How many errors a query token may carry depends on its length ({@link #allowedErrors(int)}):
 * short tokens must match exactly, otherwise two letters would find half the menu.
 *
 * @author plaintext.ch
 */
final class FuzzyText {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private FuzzyText() {
    }

    /**
     * Normalises a text for comparison: lower case, diacritics removed, {@code ß -> ss}, runs of
     * non-alphanumeric characters collapsed into one space, trimmed.
     *
     * @param s the text (may be {@code null})
     * @return the normalised text, never {@code null}
     */
    static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ROOT).replace("ß", "ss");
        String folded = COMBINING_MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
        return NON_ALNUM.matcher(folded).replaceAll(" ").trim();
    }

    /**
     * The normalised text without any spaces — compound words and their spelled-apart variants
     * ("Wander Reisen" / "Wanderreisen") then look the same.
     *
     * @param normalized an already {@link #normalize(String) normalised} text
     * @return the text without spaces
     */
    static String compact(String normalized) {
        return normalized.replace(" ", "");
    }

    /**
     * Number of edit errors a query token of the given length may carry.
     *
     * @param length length of the (normalised) query token
     * @return 0 below 4 characters, 1 up to 6, 2 up to 10, 3 above
     */
    static int allowedErrors(int length) {
        if (length < 4) {
            return 0;
        }
        if (length <= 6) {
            return 1;
        }
        if (length <= 10) {
            return 2;
        }
        return 3;
    }

    /**
     * Smallest edit distance (insert, delete, substitute, swap of two neighbours) between
     * {@code query} and any substring of {@code target}.
     *
     * @param query  the query token (normalised, not empty)
     * @param target the text to search in (normalised)
     * @return the distance; {@code 0} if {@code query} occurs literally in {@code target}
     */
    static int substringDistance(String query, String target) {
        int m = query.length();
        int n = target.length();
        if (m == 0) {
            return 0;
        }
        if (n == 0) {
            return m;
        }
        // d[i][j]: distance of query[0..i) to the best substring of target ending at j.
        int[][] d = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) {
            d[i][0] = i;
        }
        // d[0][j] = 0: the match may start anywhere in the target.
        for (int i = 1; i <= m; i++) {
            char qc = query.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = qc == target.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && qc == target.charAt(j - 2) && query.charAt(i - 2) == target.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
            }
        }
        int best = m;
        for (int j = 0; j <= n; j++) {
            best = Math.min(best, d[m][j]);
        }
        return best;
    }

    /**
     * Like {@link #substringDistance(String, String)}, but only as far as the token may err:
     * returns the distance if it is within {@link #allowedErrors(int)}, otherwise {@code -1}.
     *
     * @param token  the query token (normalised, not empty)
     * @param target the text to search in (normalised, may be empty)
     * @return the distance, or {@code -1} for no match
     */
    static int fuzzyErrors(String token, String target) {
        if (target.isEmpty()) {
            return -1;
        }
        if (target.contains(token)) {
            return 0;
        }
        int allowed = allowedErrors(token.length());
        if (allowed == 0) {
            return -1;
        }
        int dist = substringDistance(token, target);
        return dist <= allowed ? dist : -1;
    }
}
