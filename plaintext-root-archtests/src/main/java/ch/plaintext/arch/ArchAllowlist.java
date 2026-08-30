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
 * Justified exceptions for the shared linters — one file per reactor:
 * {@code <reactor-wurzel>/plaintext-arch-allowlist.txt}.
 *
 * <p>Format, one exception per line, a justification is mandatory:
 * <pre>
 * # comment lines and blank lines are allowed
 * header-hygiene  plaintext-foo/src/main/java/ch/plaintext/foo/Alt.java   # legacy code, card 123
 * header-hygiene  plaintext-foo/src/test/java/**                          # fixtures, card 123
 * jsf-view        plaintext-foo/src/main/resources/META-INF/resources/x.xhtml  # own metadata insert
 * facelets-el     plaintext-foo/src/main/resources/META-INF/resources/y.xhtml  # ...
 * i18n-seed       plaintext-foo/src/main/resources/META-INF/resources/z.xhtml  # legacy code, translation to follow
 * session-bean    FooBackingBean.barService                                # design question, card 915
 * </pre>
 * Column 1 is the rule identifier, column 2 the target (path relative to the reactor root with
 * {@code *}/{@code **} as wildcards, or {@code Klasse.feld}), after {@code #} the
 * justification. An entry without a justification is invalid and makes the test fail —
 * exactly that is what separates an allowlist from a switch.
 *
 * <p>Why a file instead of a marker in the source: for header/author violations the source itself
 * IS the violation, so a marker there would be self-certification. For XHTML the individual
 * linters additionally offer inline markers ({@code jsf-view-ok}, {@code el-quote-ok}) for
 * single lines.
 *
 * <p>root does not keep such a file: the framework has to pass its own rules without exception.
 * Consumers (app, guild, schuetu, iot) create one when they cannot clean up legacy code right away.
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

    /** Loads the exceptions of rule {@code regel} from the reactor's allowlist (if it is missing: no exceptions). */
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
            return; // entry belonging to a different rule
        }
        if (grund.isEmpty()) {
            fehler.add(datei.getFileName() + ":" + nr + " -> Ausnahme ohne Begruendung (nach '#' angeben): " + raw);
            return;
        }
        ziele.add(glob(teile[1].strip()));
    }

    /** {@code **} = any depth, {@code *} = anything within one segment; everything else literal. */
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

    /** Is {@code ziel} (relative path resp. {@code Klasse.feld}) excepted with a justification? */
    boolean erlaubt(String ziel) {
        String z = ziel.replace('\\', '/');
        return ziele.stream().anyMatch(p -> p.matcher(z).matches());
    }

    /** Format errors of the file (missing justification, incomplete line) — the test reports them as a violation. */
    List<String> fehler() {
        return fehler;
    }

    int anzahl() {
        return ziele.size();
    }
}
