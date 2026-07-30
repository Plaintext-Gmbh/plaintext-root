/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Wiederverwendbarer Linter gegen einen JSF/Facelets-Fallstrick: ein gerades ASCII-Anfuehrungszeichen
 * ({@code "}) innerhalb eines <em>inline</em>-EL-Ausdrucks im Body-Text
 * ({@code #{i18n.t('... " ...')}}) laesst den Facelets-Textparser
 * ({@code com.sun.faces.facelets.el.ELText#findVarLength}) mit
 * <em>"EL Expression Unbalanced"</em> abbrechen — die ganze Seite endet in einem 500 /
 * Whitelabel-Error, obwohl der EL-String syntaktisch harmlos aussieht.
 *
 * <p>Konkreter Ausloeser (real gesehen in {@code setup.xhtml}):
 * <pre>#{i18n.t('... Sichtbarkeit „Global – System" in der Mailbox ...')}</pre>
 * Das gerade {@code "} nach „System" wird vom Textparser als String-Delimiter gelesen, findet kein
 * Gegenstueck und sprengt die Facelet-Kompilierung. Fix: deutsche Typografie-Quotes
 * ({@code „ “}) verwenden — nie ein gerades {@code "} in einem inline-{@code #{...}} im Body-Text.
 *
 * <p><b>Nur Body-Text ist betroffen.</b> In Attributwerten ({@code attr="#{i18n.t('x')}"}) ist der
 * EL-Ausdruck bereits von {@code "} umschlossen und wird anders geparst — das ist voellig ok und
 * ueberall ueblich. Der Linter blendet daher zuerst alle Tags und Kommentare aus (Zeilenstruktur
 * bleibt erhalten) und prueft nur die verbleibenden Body-{@code #{...}}-Ausdruecke.
 *
 * <p><b>Wiederverwendung ueber Modulgrenzen:</b> wie {@link MobileFormLinter} liegt diese Klasse in
 * {@code plaintext-root-common} und ist transitiv auf dem Test-Classpath aller abhaengigen Projekte
 * (app, iot, fwtool, schuetu). Ein Consumer-Test ruft {@link #scan(Path)} auf sein
 * {@code META-INF/resources} auf. Nur-JDK, keine weiteren Abhaengigkeiten.
 *
 * <p>Opt-out (praktisch nie noetig, da immer ein echter Fehler): {@code <!-- el-quote-ok -->} in
 * derselben Zeile.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class FaceletsElLinter {

    /** XML-/JSF-Tag (auch mehrzeilig): {@code <...>}. Wird fuer die Body-Extraktion ausgeblendet. */
    private static final Pattern TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);

    /** XML-Kommentar (auch mehrzeilig): {@code <!-- ... -->}. */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** Ein einzelner inline-EL-Ausdruck {@code #{...}} (ohne verschachtelte geschweifte Klammern). */
    private static final Pattern INLINE_EL = Pattern.compile("#\\{[^{}]*}", Pattern.DOTALL);

    /** Opt-out-Marker als Inline-Kommentar in derselben Zeile. */
    private static final String EXEMPT_COMMENT = "el-quote-ok";

    private FaceletsElLinter() {
    }

    /**
     * Ein einzelner Verstoss: Datei, Zeile (1-basiert) und eine menschenlesbare Beschreibung.
     *
     * @param file    betroffene XHTML-Datei
     * @param line    1-basierte Zeilennummer des EL-Ausdrucks
     * @param message Beschreibung des Anti-Patterns
     */
    public record Violation(Path file, int line, String message) {
        @Override
        public String toString() {
            return file + ":" + line + " -> " + message;
        }
    }

    /**
     * Scannt rekursiv alle {@code *.xhtml} unter {@code resourcesRoot} und liefert alle Verstoesse
     * zurueck (gerades {@code "} in einem inline-{@code #{...}} im Body-Text).
     *
     * @param resourcesRoot Wurzelverzeichnis (z. B. {@code .../META-INF/resources}); existiert es
     *                      nicht, wird eine leere Liste zurueckgegeben
     * @return Liste der Verstoesse (leer = sauber)
     */
    public static List<Violation> scan(Path resourcesRoot) {
        List<Violation> violations = new ArrayList<>();
        if (resourcesRoot == null || !Files.isDirectory(resourcesRoot)) {
            return violations;
        }
        try (Stream<Path> files = Files.walk(resourcesRoot)) {
            files.filter(p -> p.toString().endsWith(".xhtml"))
                 .forEach(f -> scanFile(f, violations));
        } catch (IOException e) {
            throw new UncheckedIOException("Konnte XHTML-Ressourcen nicht scannen: " + resourcesRoot, e);
        }
        return violations;
    }

    private static void scanFile(Path file, List<Violation> violations) {
        String original;
        try {
            original = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Konnte XHTML nicht lesen: " + file, e);
        }
        // Kommentare und Tags ausblenden (Nicht-Newline -> Space), damit nur Body-Text mit inline-EL
        // uebrig bleibt UND die Zeilenstruktur/Offsets identisch zum Original bleiben.
        String body = blankSpans(blankSpans(original, COMMENT), TAG);

        Matcher el = INLINE_EL.matcher(body);
        while (el.find()) {
            String expr = el.group();
            if (expr.indexOf('"') < 0) {
                continue; // kein gerades ASCII-Quote -> ok
            }
            int lineNo = lineNumberAt(original, el.start());
            // Opt-out in derselben Zeile respektieren (kein weiteres continue -> S135).
            if (!lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
                violations.add(new Violation(file, lineNo,
                        "Gerades Anfuehrungszeichen (\") in einem inline-EL-Ausdruck im Body-Text: "
                                + snippet(expr) + " -> Facelets ELText.findVarLength bricht mit "
                                + "\"EL Expression Unbalanced\" ab (500/Whitelabel). Fix: deutsche "
                                + "Typografie-Quotes („ “) statt geradem \" verwenden, oder mit "
                                + "<!-- " + EXEMPT_COMMENT + " --> in derselben Zeile begruendet ausnehmen."));
            }
        }
    }

    /** Ersetzt jedes {@code pattern}-Vorkommen durch gleich lange Leerzeichen; Newlines bleiben erhalten. */
    private static String blankSpans(String content, Pattern pattern) {
        Matcher m = pattern.matcher(content);
        StringBuilder out = new StringBuilder(content);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (out.charAt(i) != '\n') {
                    out.setCharAt(i, ' ');
                }
            }
        }
        return out.toString();
    }

    /** Kurzform des EL-Ausdrucks fuer die Fehlermeldung (max. 80 Zeichen). */
    private static String snippet(String expr) {
        String s = expr.replace('\n', ' ').trim();
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }

    private static int lineNumberAt(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String lineContent(String content, int lineNo) {
        String[] lines = content.split("\n", -1);
        if (lineNo >= 1 && lineNo <= lines.length) {
            return lines[lineNo - 1];
        }
        return "";
    }
}
