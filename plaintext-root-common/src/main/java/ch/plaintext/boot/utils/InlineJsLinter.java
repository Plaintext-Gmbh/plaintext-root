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
 * Wiederverwendbarer Linter gegen Inline-JavaScript in Facelets — die Vorbedingung dafuer, dass
 * die Content-Security-Policy ohne {@code script-src 'unsafe-inline'} auskommt.
 *
 * <p><b>Warum das eine Regel und keine Geschmacksfrage ist.</b> Solange die Policy
 * {@code 'unsafe-inline'} fuehrt, laeuft jedes eingeschleuste {@code <script>} — die CSP ist dann
 * kein XSS-Schutz, sondern Dekoration. Der Browser unterscheidet nicht zwischen „unser“ und
 * „fremd“; er unterscheidet nur zwischen „aus einer Datei geladen“ und „im Dokument gestanden“.
 * Ein einziger verbliebener Inline-Block irgendwo im Reactor genuegt, um den Schalter nicht
 * umlegen zu koennen.
 *
 * <p><b>Regel 1 — {@link #RULE_INLINE_SCRIPT}:</b> ein {@code <script>} MIT Rumpf und OHNE
 * {@code src}, ebenso ein {@code <h:outputScript>} mit Rumpf (das rendert genauso einen
 * Inline-Block). Fix: Rumpf in eine {@code .js}-Datei unter
 * {@code src/main/resources/META-INF/resources/<library>/js/} auslagern und per
 * {@code <h:outputScript library="…" name="js/….js"/>} einbinden. Serverseitige Werte gehoeren
 * dann als {@code data-}-Attribut an ein Element, NICHT als EL in den Skriptkoerper.
 *
 * <p><b>Regel 2 — {@link #RULE_INLINE_HANDLER}:</b> ein {@code on…}-Attribut an einem
 * NICHT-PrimeFaces-Tag ({@code onclick} an {@code <a>}, {@code onchange} an {@code <select>} …).
 * Fix: {@code addEventListener} in der ausgelagerten Datei, angebunden ueber eine ID oder ein
 * {@code data-}-Attribut.
 *
 * <p><b>Was BLEIBEN darf und deshalb nicht gemeldet wird:</b> {@code on…} an einer
 * {@code p:}-Komponente. Das sind zum grossen Teil gar keine HTML-Attribute, sondern
 * PrimeFaces-Ereignisse des Ajax-Lebenszyklus ({@code oncomplete}, {@code onstart},
 * {@code onerror} an {@code p:commandButton}); und wo PrimeFaces tatsaechlich einen
 * HTML-Handler erzeugen wuerde ({@code onclick} an {@code p:commandButton}), zieht der
 * CSP-Modus ({@code joinfaces.primefaces.csp=true}) ihn selbst aus dem Markup heraus und
 * registriert ihn ueber {@code PrimeFaces.csp}. Diese Faelle stehen dem scharfen Header also
 * nicht im Weg.
 *
 * <p><b>Was vor der Auswertung ausgeblendet wird</b> (auf Leerzeichen gesetzt, Zeilennummern
 * bleiben dadurch stabil): XML-Kommentare — sonst zaehlt ein Beispiel im Kommentar als Verstoss,
 * und genau solche Beispiele stehen im Bestand (etwa {@code <img src=x onerror=…>} als
 * Erlaeuterung einer Sicherheitsluecke) — sowie die Ruempfe von {@code <script>} und
 * {@code <style>}, damit JavaScript-Eigenschaften wie {@code xhr.onreadystatechange = …} nicht
 * als Attribut durchgehen.
 *
 * <p><b>Ausnahmen:</b> {@code <!-- inline-js-ok -->} in derselben Zeile nimmt genau diesen Treffer
 * begruendet aus (Muster wie {@code jsf-view-ok} / {@code mobile-ok}). Ganze Dateien nimmt der
 * geteilte Test ueber die Allowlist des Reactors aus (siehe {@code PlaintextInlineJsVertragTest}
 * in plaintext-root-archtests).
 *
 * <p><b>Wiederverwendung ueber Modulgrenzen:</b> wie {@link JsfViewLinter} und
 * {@link MobileFormLinter} liegt die Klasse in {@code plaintext-root-common} (nur JDK) und ist
 * transitiv auf dem Test-Classpath aller abhaengigen Projekte.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class InlineJsLinter {

    /** Regel-Kennung: {@code <script>}/{@code <h:outputScript>} mit Rumpf statt ausgelagerter Datei. */
    public static final String RULE_INLINE_SCRIPT = "inline-script-block";

    /** Regel-Kennung: {@code on…}-Attribut an einem Nicht-PrimeFaces-Tag. */
    public static final String RULE_INLINE_HANDLER = "inline-event-handler";

    /** Opt-out-Marker als Inline-Kommentar in derselben Zeile wie der gemeldete Treffer. */
    public static final String EXEMPT_COMMENT = "inline-js-ok";

    /** XML-Kommentar (auch mehrzeilig). */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** {@code <script …>…</script>} bzw. {@code <h:outputScript …>…</h:outputScript>} samt Rumpf. */
    private static final Pattern SCRIPT_BLOCK = Pattern.compile(
            "<(script|h:outputScript)\\b([^>]*)>(.*?)</\\1\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** {@code <style …>…</style>} samt Rumpf — nur zum Ausblenden. */
    private static final Pattern STYLE_BLOCK = Pattern.compile(
            "<style\\b[^>]*>(.*?)</style\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** {@code src="…"} bzw. {@code name="…"} im Attributteil eines Skript-Tags. */
    private static final Pattern SRC_ODER_NAME = Pattern.compile("\\b(src|name)\\s*=", Pattern.CASE_INSENSITIVE);

    /**
     * Ein oeffnendes Tag: Name, danach der Attributteil. Anfuehrungszeichen werden als Einheit
     * gelesen, damit ein {@code >} INNERHALB eines Attributwerts das Tag nicht vorzeitig beendet
     * (im Bestand steht etwa {@code style="…"} mit {@code >} in einem EL-Ausdruck).
     */
    private static final Pattern TAG = Pattern.compile(
            "<([A-Za-z][A-Za-z0-9:._-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*+)/?>", Pattern.DOTALL);

    /**
     * {@code on…=} im Attributteil eines Tags. Das vorausgehende Leerzeichen ist Pflicht, sonst
     * schlaegt die Regel auch bei Attributnamen an, die zufaellig auf {@code on…} enden —
     * {@code data-pt-only="…"} etwa enthaelt {@code only=}.
     */
    private static final Pattern ON_ATTRIBUT = Pattern.compile("(?<=\\s)on([A-Za-z]+)\\s*=");

    private InlineJsLinter() {
    }

    /**
     * Ein einzelner Verstoss: Datei, Zeile (1-basiert), Regel-Kennung und eine menschenlesbare
     * Beschreibung.
     *
     * @param file    betroffene XHTML-Datei
     * @param line    1-basierte Zeilennummer des gemeldeten Tags
     * @param rule    {@link #RULE_INLINE_SCRIPT} oder {@link #RULE_INLINE_HANDLER}
     * @param message Beschreibung samt Fix
     */
    public record Violation(Path file, int line, String rule, String message) {
        @Override
        public String toString() {
            return file + ":" + line + " [" + rule + "] -> " + message;
        }
    }

    /**
     * Scannt rekursiv alle {@code *.xhtml} unter {@code resourcesRoot}.
     *
     * @param resourcesRoot Wurzelverzeichnis (z. B. {@code <modul>/src/main/resources}); existiert
     *                      es nicht, wird eine leere Liste zurueckgegeben
     * @return Liste der Verstoesse (leer = sauber)
     */
    public static List<Violation> scan(Path resourcesRoot) {
        List<Violation> violations = new ArrayList<>();
        if (resourcesRoot == null || !Files.isDirectory(resourcesRoot)) {
            return violations;
        }
        try (Stream<Path> files = Files.walk(resourcesRoot)) {
            files.filter(p -> p.toString().endsWith(".xhtml"))
                 .sorted()
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
        String ohneKommentare = blankSpans(original, COMMENT);
        pruefeSkriptbloecke(file, original, ohneKommentare, violations);

        // Fuer die Attribut-Regel zusaetzlich die RUEMPFE von <script>/<style> ausblenden (nur die
        // Ruempfe, nicht die oeffnenden Tags — ein on…= am <script>-Tag selbst bliebe sonst
        // unentdeckt). Sonst zaehlt eine JavaScript-Eigenschaft wie xhr.onreadystatechange = …
        // als Attribut.
        String nurMarkup = blankGroup(blankGroup(ohneKommentare, SCRIPT_BLOCK, 3), STYLE_BLOCK, 1);
        pruefeHandlerAttribute(file, original, nurMarkup, violations);
    }

    /** Regel 1 — {@code <script>}/{@code <h:outputScript>} mit Rumpf und ohne {@code src}/{@code name}. */
    private static void pruefeSkriptbloecke(Path file, String original, String content,
                                            List<Violation> violations) {
        Matcher m = SCRIPT_BLOCK.matcher(content);
        while (m.find()) {
            if (SRC_ODER_NAME.matcher(m.group(2)).find() || m.group(3).isBlank()) {
                continue; // ausgelagerte Datei bzw. leerer Rumpf
            }
            int lineNo = lineNumberAt(original, m.start());
            if (lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
                continue;
            }
            violations.add(new Violation(file, lineNo, RULE_INLINE_SCRIPT,
                    "<" + m.group(1) + "> mit Rumpf: solange ein Inline-Block existiert, muss die "
                            + "Content-Security-Policy script-src 'unsafe-inline' fuehren — dann laeuft auch "
                            + "jedes eingeschleuste <script>. Fix: Rumpf nach "
                            + "src/main/resources/META-INF/resources/<library>/js/ auslagern und per "
                            + "<h:outputScript library=\"…\" name=\"js/….js\"/> einbinden; serverseitige Werte "
                            + "als data-Attribut an ein Element statt als EL im Skriptkoerper. Oder mit <!-- "
                            + EXEMPT_COMMENT + " --> in derselben Zeile begruendet ausnehmen."));
        }
    }

    /** Regel 2 — {@code on…}-Attribut an einem Nicht-{@code p:}-Tag. */
    private static void pruefeHandlerAttribute(Path file, String original, String content,
                                               List<Violation> violations) {
        Matcher tag = TAG.matcher(content);
        while (tag.find()) {
            String tagName = tag.group(1);
            if (tagName.startsWith("p:") || tagName.startsWith("P:")) {
                continue; // PrimeFaces-Attribut bzw. vom CSP-Modus selbst herausgezogen
            }
            Matcher attribut = ON_ATTRIBUT.matcher(tag.group(2));
            while (attribut.find()) {
                int lineNo = lineNumberAt(original, tag.start(2) + attribut.start());
                if (lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
                    continue;
                }
                violations.add(new Violation(file, lineNo, RULE_INLINE_HANDLER,
                        "on" + attribut.group(1) + "= an <" + tagName + ">: ein HTML-Ereignisattribut ist "
                                + "fuer den Browser dasselbe wie ein Inline-<script> und zwingt die "
                                + "Content-Security-Policy zu script-src 'unsafe-inline'. Fix: "
                                + "addEventListener in der ausgelagerten .js-Datei, angebunden ueber eine ID "
                                + "oder ein data-Attribut. Oder mit <!-- " + EXEMPT_COMMENT + " --> in "
                                + "derselben Zeile begruendet ausnehmen."));
            }
        }
    }

    /** Ersetzt jedes {@code pattern}-Vorkommen durch gleich lange Leerzeichen; Newlines bleiben erhalten. */
    private static String blankSpans(String content, Pattern pattern) {
        return blankGroup(content, pattern, 0);
    }

    /** Wie {@link #blankSpans}, aber nur die angegebene Gruppe des Treffers. */
    private static String blankGroup(String content, Pattern pattern, int gruppe) {
        Matcher m = pattern.matcher(content);
        StringBuilder out = new StringBuilder(content);
        while (m.find()) {
            for (int i = m.start(gruppe); i < m.end(gruppe); i++) {
                if (out.charAt(i) != '\n') {
                    out.setCharAt(i, ' ');
                }
            }
        }
        return out.toString();
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
