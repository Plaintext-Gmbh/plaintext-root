/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.lader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Soll-Liste der Module aus {@code module.yaml} im PV (Karte 1513).
 *
 * <p>Nur JDK, darum kein YAML-Parser, sondern eine feste, kleine Teilmenge:
 * <pre>
 * module:
 *   - name: plaintext-z-verrechnung
 *     version: 2.1954.0
 *     sha256: 59b09084b7f9...
 *     root-min: 1.766.0
 *     gruppe: ch.plaintext        # optional, Standard ch.plaintext
 *     bibliotheken: [org.example:lib:1.2.0]   # optional, Stueckliste (Karte 1514)
 *   - name: lib                   # Bibliothek: eigener Eintrag mit Hash, ohne root-min
 *     gruppe: org.example
 *     version: 1.2.0
 *     sha256: 0f1e...
 * </pre>
 * Stueckliste (Karte 1514): Jede Bibliothek, die ein Modul nennt, muss genau so (Gruppe, Name, Version)
 * als Eintrag mit Hash dastehen. Ein Fremd-Jar, das kein Modul nennt, wird abgewiesen. Module brauchen
 * {@code root-min}, Bibliotheken nicht.
 * Alles andere (unbekannte Schluessel, insbesondere eine URL, Einrueckung ausserhalb des Musters,
 * doppelte Namen) ist ein Fehler: lieber kein Start als ein still falsch gelesener.
 * Die Werte werden eng geprueft, weil sie in Download-Pfad und Cache-Dateinamen landen.
 */
final class ModulKonfig {

    static final String STANDARD_GRUPPE = "ch.plaintext";

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*(\\.[a-z0-9-]+)*");
    private static final Pattern GRUPPE = Pattern.compile("[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*");
    static final Pattern VERSION = Pattern.compile("[0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*");
    static final Pattern ROOT_VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> SCHLUESSEL = Set.of("name", "version", "sha256", "root-min", "gruppe", "bibliotheken");
    private static final Pattern KOORDINATE = Pattern.compile("([^:]+):([^:]+):([^:]+)");

    /**
     * Ein Jar: Modul ({@code bibliothek=false}, mit root-min) oder Bibliothek aus der Stueckliste eines Moduls.
     */
    record Eintrag(String gruppe, String name, String version, String sha256, String rootMin,
                   List<String> bibliotheken, boolean bibliothek) {

        Eintrag(String gruppe, String name, String version, String sha256, String rootMin) {
            this(gruppe, name, version, sha256, rootMin, List.of(), false);
        }

        String koordinate() {
            return gruppe + ":" + name + ":" + version;
        }

        /** Dateiname im Cache, eindeutig je Name und Version. */
        String dateiname() {
            return name + "-" + version + ".jar";
        }

        /** Maven-Pfad relativ zur Basis-URL. */
        String mavenPfad() {
            return gruppe.replace('.', '/') + "/" + name + "/" + version + "/" + dateiname();
        }
    }

    private ModulKonfig() {
    }

    static List<Eintrag> lesen(Path datei) throws IOException {
        return parsen(Files.readString(datei, StandardCharsets.UTF_8), datei.toString());
    }

    static List<Eintrag> parsen(String text, String quelle) {
        List<Map<String, String>> roh = new ArrayList<>();
        Map<String, String> aktuell = null;
        boolean kopf = false;
        int nr = 0;
        for (String zeile : text.split("\r?\n", -1)) {
            nr++;
            String ohneKommentar = ohneKommentar(zeile);
            if (ohneKommentar.isBlank()) {
                continue;
            }
            if (!kopf) {
                String k = ohneKommentar.strip();
                if (k.equals("module: []")) {
                    kopf = true;
                    aktuell = null;
                    roh = null;
                    continue;
                }
                if (!ohneKommentar.equals("module:")) {
                    throw fehler(quelle, nr, "erwartet 'module:' als erste Zeile");
                }
                kopf = true;
                continue;
            }
            if (roh == null) {
                throw fehler(quelle, nr, "nach 'module: []' folgt nichts mehr");
            }
            String rest;
            if (ohneKommentar.startsWith("  - ")) {
                aktuell = new LinkedHashMap<>();
                roh.add(aktuell);
                rest = ohneKommentar.substring(4);
            } else if (ohneKommentar.startsWith("    ") && !ohneKommentar.startsWith("     ") && aktuell != null) {
                rest = ohneKommentar.substring(4);
            } else {
                throw fehler(quelle, nr, "Einrueckung passt nicht (erwartet '  - schluessel: wert' bzw. '    schluessel: wert')");
            }
            int doppelpunkt = rest.indexOf(':');
            if (doppelpunkt <= 0) {
                throw fehler(quelle, nr, "erwartet 'schluessel: wert'");
            }
            String schluessel = rest.substring(0, doppelpunkt).strip();
            String wert = ohneAnfuehrung(rest.substring(doppelpunkt + 1).strip());
            if (!SCHLUESSEL.contains(schluessel)) {
                throw fehler(quelle, nr, "unbekannter Schluessel '" + schluessel + "' (erlaubt: " + SCHLUESSEL + ")");
            }
            if (aktuell.put(schluessel, wert) != null) {
                throw fehler(quelle, nr, "Schluessel '" + schluessel + "' doppelt");
            }
        }
        if (!kopf) {
            throw new IllegalArgumentException(quelle + ": leer, erwartet 'module:'");
        }
        if (roh == null) {
            return List.of();
        }
        List<Eintrag> eintraege = new ArrayList<>();
        Set<String> namen = new HashSet<>();
        for (Map<String, String> m : roh) {
            Eintrag e = eintrag(m, quelle);
            if (!namen.add(e.name())) {
                throw new IllegalArgumentException(quelle + ": Modul '" + e.name() + "' doppelt");
            }
            eintraege.add(e);
        }
        return stueckliste(eintraege, quelle);
    }

    /** Karte 1514: Stueckliste aufloesen und pruefen; markiert die Bibliotheken. */
    private static List<Eintrag> stueckliste(List<Eintrag> eintraege, String quelle) {
        Map<String, Eintrag> jeName = new LinkedHashMap<>();
        eintraege.forEach(e -> jeName.put(e.name(), e));
        Set<String> genannt = new HashSet<>();
        for (Eintrag e : eintraege) {
            for (String k : e.bibliotheken()) {
                java.util.regex.Matcher m = KOORDINATE.matcher(k);
                if (!m.matches()) {
                    throw new IllegalArgumentException(quelle + ": Modul '" + e.name()
                            + "', Bibliothek '" + k + "' ist nicht gruppe:name:version");
                }
                Eintrag b = jeName.get(m.group(2));
                if (b == null) {
                    throw new IllegalArgumentException(quelle + ": Stueckliste von '" + e.name() + "' nennt " + k
                            + ", dazu fehlt ein Eintrag mit Hash");
                }
                if (!b.koordinate().equals(k)) {
                    throw new IllegalArgumentException(quelle + ": Stueckliste von '" + e.name() + "' nennt " + k
                            + ", eingetragen ist " + b.koordinate());
                }
                if (!b.bibliotheken().isEmpty()) {
                    throw new IllegalArgumentException(quelle + ": Bibliothek '" + b.name()
                            + "' hat selbst eine Stueckliste (die Liste ist flach, je Modul vollstaendig)");
                }
                genannt.add(b.name());
            }
        }
        List<Eintrag> ergebnis = new ArrayList<>();
        for (Eintrag e : eintraege) {
            boolean bibliothek = genannt.contains(e.name());
            if (!bibliothek && e.rootMin() == null) {
                String grund = istPlaintext(e.gruppe())
                        ? "Modul '" + e.name() + "' ohne 'root-min'"
                        : "Fremd-Jar '" + e.koordinate() + "' steht in keiner Stueckliste";
                throw new IllegalArgumentException(quelle + ": " + grund);
            }
            ergebnis.add(new Eintrag(e.gruppe(), e.name(), e.version(), e.sha256(), e.rootMin(),
                    e.bibliotheken(), bibliothek));
        }
        return List.copyOf(ergebnis);
    }

    static boolean istPlaintext(String gruppe) {
        return STANDARD_GRUPPE.equals(gruppe) || gruppe.startsWith(STANDARD_GRUPPE + ".");
    }

    private static Eintrag eintrag(Map<String, String> m, String quelle) {
        String name = pflicht(m, "name", NAME, quelle, "?");
        String version = pflicht(m, "version", VERSION, quelle, name);
        String sha256 = pflicht(m, "sha256", SHA256, quelle, name, true);
        String rootMin = m.containsKey("root-min") ? pflicht(m, "root-min", ROOT_VERSION, quelle, name) : null;
        String gruppe = m.containsKey("gruppe") ? pflicht(m, "gruppe", GRUPPE, quelle, name) : STANDARD_GRUPPE;
        return new Eintrag(gruppe, name, version, sha256, rootMin, liste(m.get("bibliotheken"), quelle, name), false);
    }

    /** Flusssequenz {@code [a, b]}; Elemente werden mit denselben Mustern geprueft wie Eintraege. */
    private static List<String> liste(String wert, String quelle, String modul) {
        if (wert == null) {
            return List.of();
        }
        if (!wert.startsWith("[") || !wert.endsWith("]")) {
            throw new IllegalArgumentException(quelle + ": Modul '" + modul + "', 'bibliotheken' erwartet [a, b]");
        }
        String innen = wert.substring(1, wert.length() - 1).strip();
        if (innen.isEmpty()) {
            return List.of();
        }
        List<String> l = new ArrayList<>();
        for (String teil : innen.split(",")) {
            String k = ohneAnfuehrung(teil.strip());
            java.util.regex.Matcher m = KOORDINATE.matcher(k);
            if (!m.matches() || !GRUPPE.matcher(m.group(1)).matches() || !NAME.matcher(m.group(2)).matches()
                    || !VERSION.matcher(m.group(3)).matches()) {
                throw new IllegalArgumentException(quelle + ": Modul '" + modul + "', ungueltige Bibliothek: " + k);
            }
            l.add(k);
        }
        return List.copyOf(l);
    }

    private static String pflicht(Map<String, String> m, String schluessel, Pattern muster, String quelle, String modul) {
        return pflicht(m, schluessel, muster, quelle, modul, false);
    }

    private static String pflicht(Map<String, String> m, String schluessel, Pattern muster, String quelle,
                                  String modul, boolean klein) {
        String wert = m.get(schluessel);
        if (wert == null || wert.isEmpty()) {
            throw new IllegalArgumentException(quelle + ": Modul '" + modul + "' ohne '" + schluessel + "'");
        }
        if (klein) {
            wert = wert.toLowerCase(Locale.ROOT);
        }
        if (!muster.matcher(wert).matches()) {
            throw new IllegalArgumentException(quelle + ": Modul '" + modul + "', ungueltiger Wert fuer '"
                    + schluessel + "': " + wert);
        }
        return wert;
    }

    private static String ohneKommentar(String zeile) {
        int i = zeile.indexOf('#');
        String s = i >= 0 ? zeile.substring(0, i) : zeile;
        return s.stripTrailing();
    }

    private static String ohneAnfuehrung(String wert) {
        if (wert.length() >= 2 && (wert.startsWith("\"") && wert.endsWith("\"")
                || wert.startsWith("'") && wert.endsWith("'"))) {
            return wert.substring(1, wert.length() - 1);
        }
        return wert;
    }

    private static IllegalArgumentException fehler(String quelle, int nr, String text) {
        return new IllegalArgumentException(quelle + ":" + nr + ": " + text);
    }

    /** Vergleicht x.y.z numerisch. */
    static int vergleiche(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Prueft das Ziel-Format einer Huellen-Version (ohne -SNAPSHOT). */
    static boolean istRootVersion(String v) {
        return v != null && ROOT_VERSION.matcher(v).matches();
    }
}
