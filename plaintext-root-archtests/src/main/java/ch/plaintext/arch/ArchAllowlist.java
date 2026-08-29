/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Begruendete Ausnahmen fuer die geteilten Linter — eine Datei je Reactor:
 * {@code <reactor-wurzel>/plaintext-arch-allowlist.txt}.
 *
 * <p>Format, eine Ausnahme je Zeile, Begruendung ist Pflicht:
 * <pre>
 * # Kommentarzeilen und Leerzeilen sind erlaubt
 * header-hygiene  plaintext-foo/src/main/java/ch/plaintext/foo/Alt.java   # Altbestand, Karte 123
 * header-hygiene  plaintext-foo/src/test/java/**                          # Fixtures, Karte 123
 * jsf-view        plaintext-foo/src/main/resources/META-INF/resources/x.xhtml  # eigener Metadaten-Insert
 * facelets-el     plaintext-foo/src/main/resources/META-INF/resources/y.xhtml  # ...
 * i18n-seed       plaintext-foo/src/main/resources/META-INF/resources/z.xhtml  # Altbestand, Uebersetzung folgt
 * session-bean    FooBackingBean.barService                                # Designfrage, Karte 915
 * </pre>
 * Spalte 1 ist die Regel-Kennung, Spalte 2 das Ziel (Pfad relativ zur Reactor-Wurzel mit
 * {@code *}/{@code **} als Platzhalter, bzw. {@code Klasse.feld}), nach {@code #} die
 * Begruendung. Ein Eintrag ohne Begruendung ist ungueltig und laesst den Test fehlschlagen —
 * genau das unterscheidet eine Allowlist von einem Schalter.
 *
 * <p>Warum eine Datei statt eines Markers im Quelltext: Bei Header-/Autor-Verstoessen IST der
 * Quelltext der Verstoss, ein Marker dort waere Selbstbestaetigung. Fuer XHTML gibt es zusaetzlich
 * die Inline-Marker der jeweiligen Linter ({@code jsf-view-ok}, {@code el-quote-ok}) fuer
 * einzelne Zeilen.
 *
 * <p>root fuehrt diese Datei nicht: das Framework muss die eigenen Regeln ohne Ausnahme bestehen.
 * Consumer (app, guild, schuetu, iot) legen sie an, wenn sie Altbestand nicht sofort bereinigen.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
final class ArchAllowlist {

    static final String DATEINAME = "plaintext-arch-allowlist.txt";

    private final String regel;
    private final List<Pattern> ziele = new ArrayList<>();
    private final List<String> fehler = new ArrayList<>();

    private ArchAllowlist(String regel) {
        this.regel = regel;
    }

    /** Laedt die Ausnahmen der Regel {@code regel} aus der Allowlist des Reactors (fehlt sie: keine Ausnahmen). */
    static ArchAllowlist fuer(String regel) {
        Path root = ReactorLayout.repoRoot();
        return fuer(regel, root == null ? null : root.resolve(DATEINAME));
    }

    static ArchAllowlist fuer(String regel, Path datei) {
        ArchAllowlist a = new ArchAllowlist(regel);
        if (datei == null || !Files.isRegularFile(datei)) {
            return a;
        }
        try {
            int nr = 0;
            for (String raw : Files.readAllLines(datei)) {
                nr++;
                a.parse(raw, nr, datei);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Allowlist nicht lesbar: " + datei, e);
        }
        return a;
    }

    private void parse(String raw, int nr, Path datei) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        int hash = line.indexOf('#');
        String eintrag = hash < 0 ? line : line.substring(0, hash).strip();
        String grund = hash < 0 ? "" : line.substring(hash + 1).strip();
        String[] teile = eintrag.split("\\s+", 2);
        if (teile.length < 2) {
            fehler.add(datei.getFileName() + ":" + nr + " -> erwartet '<regel> <ziel>  # <Begruendung>', gefunden: " + raw);
            return;
        }
        if (!teile[0].equals(regel)) {
            return; // Eintrag einer anderen Regel
        }
        if (grund.isEmpty()) {
            fehler.add(datei.getFileName() + ":" + nr + " -> Ausnahme ohne Begruendung (nach '#' angeben): " + raw);
            return;
        }
        ziele.add(glob(teile[1].strip()));
    }

    /** {@code **} = beliebig tief, {@code *} = beliebig innerhalb eines Segments; alles andere woertlich. */
    private static Pattern glob(String ziel) {
        StringBuilder rx = new StringBuilder();
        for (int i = 0; i < ziel.length(); i++) {
            char c = ziel.charAt(i);
            if (c == '*') {
                if (i + 1 < ziel.length() && ziel.charAt(i + 1) == '*') {
                    rx.append(".*");
                    i++;
                } else {
                    rx.append("[^/]*");
                }
            } else {
                rx.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(rx.toString());
    }

    /** Ist {@code ziel} (relativer Pfad bzw. {@code Klasse.feld}) begruendet ausgenommen? */
    boolean erlaubt(String ziel) {
        String z = ziel.replace('\\', '/');
        return ziele.stream().anyMatch(p -> p.matcher(z).matches());
    }

    /** Formfehler der Datei (fehlende Begruendung, unvollstaendige Zeile) — der Test meldet sie als Verstoss. */
    List<String> fehler() {
        return fehler;
    }

    int anzahl() {
        return ziele.size();
    }
}
