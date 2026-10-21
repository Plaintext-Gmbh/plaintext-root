/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Aggregation of the stored consumption rows for the monitoring page. */
public final class ClaudeNutzung {

    private ClaudeNutzung() {
    }

    /** One stored row: one day, calling module and model. */
    public record Zeile(LocalDate tag, String quelle, String modell, long aufrufe, long fehler,
                       long inputTokens, long outputTokens, long cacheTokens, long latenzMs) {
    }

    /** Sum over several rows, labelled (period, module or model). */
    public record Summe(String name, long aufrufe, long fehler, long inputTokens, long outputTokens, long cacheTokens,
                        long latenzMs) {

        /** Error rate in percent, 0 without calls. */
        public double fehlerquote() {
            return aufrufe == 0 ? 0 : 100.0 * fehler / aufrufe;
        }

        /** Mean latency per call in ms, 0 without calls. */
        public long mittlereLatenz() {
            return aufrufe == 0 ? 0 : latenzMs / aufrufe;
        }
    }

    /** Totals for today, the last 7 and the last 30 days (both including today). */
    public static List<Summe> zeitraeume(List<Zeile> zeilen, LocalDate heute) {
        return List.of(summe("Heute", zeilen, z -> z.tag().equals(heute)),
                summe("7 Tage", zeilen, z -> z.tag().isAfter(heute.minusDays(7))),
                summe("30 Tage", zeilen, z -> z.tag().isAfter(heute.minusDays(30))));
    }

    /** Totals of the last 30 days per key (module or model), largest token count first. */
    public static List<Summe> je(List<Zeile> zeilen, LocalDate heute, Function<Zeile, String> schluessel) {
        Map<String, List<Zeile>> gruppen = zeilen.stream()
                .filter(z -> z.tag().isAfter(heute.minusDays(30)))
                .collect(Collectors.groupingBy(schluessel, TreeMap::new, Collectors.toList()));
        return gruppen.entrySet().stream()
                .map(e -> summe(e.getKey(), e.getValue(), z -> true))
                .sorted(Comparator.comparingLong((Summe s) -> s.inputTokens() + s.outputTokens()).reversed())
                .toList();
    }

    private static Summe summe(String name, List<Zeile> zeilen, java.util.function.Predicate<Zeile> filter) {
        long a = 0, f = 0, in = 0, out = 0, c = 0, l = 0;
        for (Zeile z : zeilen) {
            if (filter.test(z)) {
                a += z.aufrufe(); f += z.fehler(); in += z.inputTokens(); out += z.outputTokens();
                c += z.cacheTokens(); l += z.latenzMs();
            }
        }
        return new Summe(name, a, f, in, out, c, l);
    }
}
