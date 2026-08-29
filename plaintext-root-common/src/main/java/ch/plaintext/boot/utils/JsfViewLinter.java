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
 * Wiederverwendbarer Linter gegen drei JSF/Facelets-Fallstricke, die im Zustandsbericht vom
 * 29.08.2026 in plaintext-root belegt sind und dort stille Fehlfunktionen erzeugt haben — kein
 * Stacktrace, die Seite rendert, aber ein Teil davon tut nichts.
 *
 * <ol>
 *   <li><b>{@code <f:metadata>} nach dem ersten {@code <ui:define>}</b> ({@link #RULE_METADATA_NACH_DEFINE}):
 *       Die Seiten bauen auf {@code /includes/template.xhtml} auf, und das Template hat keinen
 *       {@code ui:insert} fuer Metadaten. Ein {@code f:metadata}-Block innerhalb eines
 *       {@code ui:define} landet damit irgendwo im Seitenkoerper — {@code f:viewParam},
 *       {@code f:viewAction} und {@code preRenderView}-Listener sind dort unwirksam bzw. laufen nur
 *       zufaellig, je nachdem wie Mojarra die Metadaten-Facette auswertet. In root standen 20 Seiten
 *       so da. Richtig: der Block gehoert direkt unter das oeffnende {@code <ui:composition ...>},
 *       vor das erste {@code <ui:define>} (Vorlage: {@code menudiagnose.xhtml}).</li>
 *   <li><b>{@code <f:metadata>} innerhalb {@code <h:form>}</b> ({@link #RULE_METADATA_IN_FORM}):
 *       Sonderfall von (1), aber gesondert gemeldet, weil hier zusaetzlich der Listener bei jedem
 *       Ajax-Postback des Formulars mitlaeuft und Daten neu laedt, die der Benutzer gerade
 *       bearbeitet. 16 der 20 root-Faelle sahen so aus.</li>
 *   <li><b>{@code onchange="submit()"} an einer PrimeFaces-Komponente</b> ({@link #RULE_ONCHANGE_SUBMIT}):
 *       PrimeFaces 15 loest ueber dieses Inline-Handler-Attribut keinen Request mehr aus — die
 *       Auswahl blieb wirkungslos (belegt an der Typ-Auswahl der Datenverwaltung
 *       {@code rootentities.xhtml}: keine Tabelle erschien). Richtig ist ein regulaeres
 *       {@code <p:ajax listener=... update=.../>}.</li>
 * </ol>
 *
 * <p><b>Ausnahmen:</b> {@code <!-- jsf-view-ok -->} in derselben Zeile wie das gemeldete Tag nimmt
 * genau diesen Treffer begruendet aus (Muster wie {@code mobile-ok} / {@code el-quote-ok}). Ganze
 * Dateien nimmt der geteilte Test ueber die Allowlist des Reactors aus (siehe
 * {@code PlaintextJsfViewLinterTest} in plaintext-root-archtests).
 *
 * <p><b>Wiederverwendung ueber Modulgrenzen:</b> wie {@link MobileFormLinter} und
 * {@link FaceletsElLinter} liegt die Klasse in {@code plaintext-root-common} (nur JDK) und ist
 * transitiv auf dem Test-Classpath aller abhaengigen Projekte; der zugehoerige Test kommt als Jar
 * aus plaintext-root-archtests mit.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class JsfViewLinter {

    /** Regel-Kennung: f:metadata steht nach dem ersten ui:define (bzw. in einem ui:define). */
    public static final String RULE_METADATA_NACH_DEFINE = "metadata-nach-ui-define";
    /** Regel-Kennung: f:metadata steht innerhalb eines h:form. */
    public static final String RULE_METADATA_IN_FORM = "metadata-in-h-form";
    /** Regel-Kennung: onchange="submit()" / "this.form.submit()" an einer p:-Komponente. */
    public static final String RULE_ONCHANGE_SUBMIT = "onchange-submit-an-p-komponente";

    /** Opt-out-Marker als Inline-Kommentar in derselben Zeile wie das gemeldete Tag. */
    public static final String EXEMPT_COMMENT = "jsf-view-ok";

    /** XML-Kommentar (auch mehrzeilig) — wird ausgeblendet, damit Beispiele in Kommentaren nicht zaehlen. */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Pattern METADATA_OPEN = Pattern.compile("<f:metadata\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFINE_OPEN = Pattern.compile("<ui:define\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM_OPEN = Pattern.compile("<h:form\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM_CLOSE = Pattern.compile("</h:form\\s*>", Pattern.CASE_INSENSITIVE);

    /**
     * Oeffnendes {@code <p:xxx ...>}-Tag (auch mehrzeilig), das ein {@code onchange}-Attribut mit
     * {@code submit()} bzw. {@code this.form.submit()} traegt.
     */
    private static final Pattern P_ONCHANGE_SUBMIT = Pattern.compile(
            "<p:[A-Za-z]+\\b[^>]*\\bonchange\\s*=\\s*\"\\s*(?:this\\.form\\.)?submit\\(\\)\\s*;?\\s*\"",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private JsfViewLinter() {
    }

    /**
     * Ein einzelner Verstoss: Datei, Zeile (1-basiert), Regel-Kennung und eine menschenlesbare
     * Beschreibung.
     *
     * @param file    betroffene XHTML-Datei
     * @param line    1-basierte Zeilennummer des gemeldeten Tags
     * @param rule    Regel-Kennung ({@link #RULE_METADATA_NACH_DEFINE}, {@link #RULE_METADATA_IN_FORM},
     *                {@link #RULE_ONCHANGE_SUBMIT})
     * @param message Beschreibung des Anti-Patterns samt Fix
     */
    public record Violation(Path file, int line, String rule, String message) {
        @Override
        public String toString() {
            return file + ":" + line + " [" + rule + "] -> " + message;
        }
    }

    /**
     * Scannt rekursiv alle {@code *.xhtml} unter {@code resourcesRoot} und liefert alle Verstoesse
     * gegen die drei Regeln zurueck.
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
        // Kommentare ausblenden (Nicht-Newline -> Space): Offsets/Zeilen bleiben identisch zum Original.
        String content = blankSpans(original, COMMENT);

        pruefeMetadata(file, original, content, violations);
        pruefeOnchangeSubmit(file, original, content, violations);
    }

    /** Regeln 1 und 2 — beide haengen an der Position des ersten {@code <f:metadata}. */
    private static void pruefeMetadata(Path file, String original, String content, List<Violation> violations) {
        Matcher metadata = METADATA_OPEN.matcher(content);
        if (!metadata.find()) {
            return;
        }
        int pos = metadata.start();
        int lineNo = lineNumberAt(original, pos);
        if (lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
            return; // begruendet ausgenommen
        }

        Matcher define = DEFINE_OPEN.matcher(content);
        if (define.find() && define.start() < pos) {
            violations.add(new Violation(file, lineNo, RULE_METADATA_NACH_DEFINE,
                    "<f:metadata> steht nach dem ersten <ui:define>: das Template hat keinen Metadaten-"
                            + "Insert, viewParam/viewAction/preRenderView sind dort unwirksam bzw. unzuverlaessig. "
                            + "Fix: Block direkt unter das oeffnende <ui:composition ...> verschieben, vor das erste "
                            + "<ui:define> (Vorlage menudiagnose.xhtml), oder mit <!-- " + EXEMPT_COMMENT
                            + " --> in derselben Zeile begruendet ausnehmen."));
        }

        if (formTiefeVor(content, pos) > 0) {
            violations.add(new Violation(file, lineNo, RULE_METADATA_IN_FORM,
                    "<f:metadata> steht innerhalb eines <h:form>: der Listener laeuft bei jedem Ajax-Postback "
                            + "des Formulars mit und laedt Daten neu, die gerade bearbeitet werden. Fix: Block "
                            + "direkt unter <ui:composition ...> verschieben, oder mit <!-- " + EXEMPT_COMMENT
                            + " --> in derselben Zeile begruendet ausnehmen."));
        }
    }

    /** Regel 3 — onchange="submit()" an einer PrimeFaces-Komponente. */
    private static void pruefeOnchangeSubmit(Path file, String original, String content, List<Violation> violations) {
        Matcher tag = P_ONCHANGE_SUBMIT.matcher(content);
        while (tag.find()) {
            int lineNo = lineNumberAt(original, tag.start());
            if (lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
                continue; // begruendet ausgenommen
            }
            violations.add(new Violation(file, lineNo, RULE_ONCHANGE_SUBMIT,
                    "onchange=\"submit()\" an einer p:-Komponente: PrimeFaces 15 loest darueber keinen Request "
                            + "aus, die Auswahl bleibt wirkungslos (belegt in rootentities.xhtml). Fix: "
                            + "<p:ajax listener=\"#{bean.xyz}\" update=\"...\"/> verwenden, oder mit <!-- "
                            + EXEMPT_COMMENT + " --> in derselben Zeile begruendet ausnehmen."));
        }
    }

    /** Anzahl offener {@code <h:form>} vor {@code offset}: Oeffnungen minus Schliessungen. */
    private static int formTiefeVor(String content, int offset) {
        int open = 0;
        Matcher m = FORM_OPEN.matcher(content);
        while (m.find() && m.start() < offset) {
            open++;
        }
        int close = 0;
        Matcher c = FORM_CLOSE.matcher(content);
        while (c.find() && c.start() < offset) {
            close++;
        }
        return open - close;
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
