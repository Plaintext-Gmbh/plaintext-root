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
 * Wiederverwendbarer Linter gegen Mobile-Anti-Patterns in JSF/PrimeFaces-XHTML.
 *
 * <p>Kernbefund: {@code <p:dialog width="560">} (fixe px-Breite) laeuft auf dem Handy rechts
 * aus dem Viewport. Das zentrale {@code mobile-responsive.css} (aus plaintext-root-template)
 * deckelt zwar jeden Dialog auf {@code 96vw}, aber der Linter haelt neue fixe-Breite-Dialoge
 * sichtbar, damit Entwickler bewusst entscheiden (styleClass + CSS statt fixe px, oder
 * begruendetes Opt-out).
 *
 * <p><b>Wiederverwendung ueber Modulgrenzen:</b> Diese Klasse liegt in
 * {@code plaintext-root-common} und ist damit transitiv auf dem Test-Classpath aller
 * abhaengigen Projekte (app, iot, fwtool, schuetu). Ein Consumer-Test ruft einfach
 * {@link #scan(Path)} auf sein {@code META-INF/resources} auf und laesst den Build bei
 * Verstoessen fehlschlagen — kein neues Artefakt, kein Copy-Paste noetig. Falls ein Consumer
 * die root-Version noch nicht gebumpt hat, kann er diese Datei auch 1:1 in seinen eigenen
 * Testbaum kopieren; sie hat keine Abhaengigkeiten ausser JDK.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class MobileFormLinter {

    /**
     * Fasst das komplette {@code <p:dialog ...>}-Oeffnungstag ein (auch mehrzeilig).
     * {@code [^>]*} matcht ueber Zeilenumbrueche mit {@link Pattern#DOTALL}.
     */
    private static final Pattern DIALOG_OPEN_TAG =
            Pattern.compile("<p:dialog\\b[^>]*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** Fixe numerische px-Breite als Attribut: {@code width="560"} (nicht "100%", nicht "50vw"). */
    private static final Pattern FIXED_WIDTH_ATTR =
            Pattern.compile("\\bwidth\\s*=\\s*\"\\s*(\\d+)(px)?\\s*\"", Pattern.CASE_INSENSITIVE);

    /** Irgendein {@code width="..."}-Attribut (z. B. auch {@code "100%"} / {@code "50vw"}). */
    private static final Pattern ANY_WIDTH_ATTR =
            Pattern.compile("\\bwidth\\s*=\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    /** Opt-out-Marker im styleClass-Attribut. */
    /** Pflicht-Marker: Dialog ist mobil behandelt (zentrale Breiten-/Scroll-Regeln greifen). */
    private static final String SAFE_STYLECLASS = "mobile-safe";

    private static final String EXEMPT_STYLECLASS = "mobile-exempt";

    /** Opt-out-Marker als Inline-Kommentar in derselben Zeile wie das p:dialog-Tag. */
    private static final String EXEMPT_COMMENT = "mobile-ok";

    private MobileFormLinter() {
    }

    /**
     * Ein einzelner Verstoss: Datei, Zeile (1-basiert) und eine menschenlesbare Beschreibung.
     *
     * @param file    betroffene XHTML-Datei
     * @param line    1-basierte Zeilennummer des Tag-Beginns
     * @param message Beschreibung des Anti-Patterns
     */
    public record Violation(Path file, int line, String message) {
        @Override
        public String toString() {
            return file + ":" + line + " -> " + message;
        }
    }

    /**
     * Scannt rekursiv alle {@code *.xhtml} unter {@code resourcesRoot} und liefert alle
     * Mobile-Anti-Pattern-Verstoesse zurueck.
     *
     * <p>Geprueft wird derzeit: {@code p:dialog} mit fixer px-Breite ohne Opt-out-Marker.
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
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Konnte XHTML nicht lesen: " + file, e);
        }
        Matcher tags = DIALOG_OPEN_TAG.matcher(content);
        while (tags.find()) {
            int lineNo = lineNumberAt(content, tags.start());
            checkDialog(file, tags.group(), content, lineNo, violations);
        }
    }

    /**
     * Prueft ein einzelnes {@code <p:dialog>}-Tag gegen die Mobile-Regeln (als Methode statt Schleifen-
     * {@code continue}, um S135 zu vermeiden):
     * <ol>
     *   <li>Jeder Dialog muss <b>mobil-behandelt</b> sein — {@code styleClass="mobile-safe"} (Breite und
     *       das zentrale Flex-Scroll-Layout greifen dann, der Footer/die Buttons bleiben auf dem Handy
     *       erreichbar) — oder begruendet ausgenommen ({@code mobile-exempt} / {@code <!-- mobile-ok -->}).</li>
     *   <li>Ein {@code mobile-safe}-Dialog darf zusaetzlich keine fixe px-Breite tragen (mobile-safe regelt
     *       die Breite).</li>
     * </ol>
     */
    private static void checkDialog(Path file, String tag, String content, int lineNo, List<Violation> violations) {
        if (isExempt(tag, content, lineNo)) {
            return; // bewusst ausgenommen (mobile-exempt / mobile-ok)
        }
        boolean safe = tag.contains(SAFE_STYLECLASS);
        Matcher fixedPx = FIXED_WIDTH_ATTR.matcher(tag);
        if (fixedPx.find()) {
            // Fixe px-Breite laeuft auf dem Handy aus dem Viewport - auch mit mobile-safe (dieses regelt die Breite).
            violations.add(new Violation(file, lineNo,
                    "p:dialog mit fixer px-Breite width=\"" + fixedPx.group(1) + "\": fixe width entfernen und "
                            + (safe ? "die Breite ueber mobile-safe/CSS regeln." : "styleClass=\"" + SAFE_STYLECLASS
                            + "\" verwenden, oder mit styleClass=\"" + EXEMPT_STYLECLASS + "\" bzw. <!-- "
                            + EXEMPT_COMMENT + " --> begruendet ausnehmen.")));
            return;
        }
        if (safe) {
            return; // mobile-safe ohne fixe px-Breite -> ok (Breite + Footer-Scroll zentral geregelt)
        }
        // Weder mobile-safe noch fixe px-Breite: nur ok, wenn eine responsive Breite (z. B. "100%") gesetzt ist.
        if (!ANY_WIDTH_ATTR.matcher(tag).find()) {
            violations.add(new Violation(file, lineNo,
                    "p:dialog ohne Mobile-Behandlung: styleClass=\"" + SAFE_STYLECLASS + "\" ergaenzen (Breite und "
                            + "Footer-Scroll werden zentral geregelt, die Buttons bleiben auf dem Handy erreichbar) "
                            + "oder eine responsive width wie \"100%\" setzen, sonst kann die PrimeFaces-Standard"
                            + "breite ueberlaufen. Begruendete Ausnahme via \"" + EXEMPT_STYLECLASS + "\" / <!-- "
                            + EXEMPT_COMMENT + " -->."));
        }
    }

    /** Opt-out: styleClass enthaelt {@code mobile-exempt}, oder die Tag-Zeile enthaelt {@code <!-- mobile-ok -->}. */
    private static boolean isExempt(String tag, String content, int lineNo) {
        if (tag.contains(EXEMPT_STYLECLASS)) {
            return true;
        }
        String line = lineContent(content, lineNo);
        return line.contains(EXEMPT_COMMENT);
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
