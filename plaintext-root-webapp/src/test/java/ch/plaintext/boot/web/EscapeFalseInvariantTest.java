/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Dauerhafte Leitplanke gegen Stored XSS in Facelets (SECURITY-Karte 304): In keiner View des
 * Repos darf {@code escape="false"} stehen.
 * <p>
 * Hintergrund: {@code escape="false"} schreibt den Wert roh in die Antwort. Steht dort ein
 * DB-/Benutzerwert (Benutzername, Mandant, i18n-Uebersetzung, LLM-Ausgabe), wird daraus Stored
 * XSS — und zwar bevorzugt auf Admin-Seiten wie {@code useradmin.xhtml}, also im hoechsten
 * Rechtekontext. Die CSP faengt das nicht ab, solange sie {@code 'unsafe-inline'} erlaubt.
 * <p>
 * Bei Karte 304 wurden alle 59 Vorkommen entfernt: keine einzige Stelle brauchte echtes HTML
 * (Cron-Hilfetexte, Labels, Benutzernamen, Feldnamen, i18n-Strings). Deshalb ist hier ein
 * <b>vollstaendiges Verbot</b> formuliert statt einer Heuristik auf EL-Muster wie {@code #{obj.}
 * — ein Verbot laesst sich nicht durch einen neuen Ausdrucksnamen ({@code #{row.},
 * #{field.} …}) umgehen.
 * <p>
 * <b>Opt-out</b> fuer den Fall, dass eine View wirklich HTML rendern muss: den Marker
 * {@code escape-false-ok} als Kommentar in dieselbe Zeile schreiben, zusammen mit der Begruendung,
 * woher das HTML kommt. Dann gilt: der Inhalt muss serverseitig sanitisiert sein (jsoup
 * {@code Safelist} ist als Dependency vorhanden) — Escaping ist die Default-Antwort,
 * Sanitizing die begruendete Ausnahme.
 * <p>
 * Aufbau analog {@link CsrfFormInvariantTest}: repo-weiter Scan aller Module mit
 * {@code src/main/resources/META-INF/resources}.
 */
class EscapeFalseInvariantTest {

    /** {@code escape="false"} in allen Schreibweisen (Whitespace, einfache/doppelte Quotes, Case). */
    private static final Pattern ESCAPE_FALSE =
            Pattern.compile("escape\\s*=\\s*[\"']\\s*false\\s*[\"']", Pattern.CASE_INSENSITIVE);

    /** XML-Kommentar (auch mehrzeilig) — wird vor dem Scan ausgeblendet, damit Doku-Text
     *  ueber das Verbot (und auskommentierter Alt-Code) keinen Fehlalarm erzeugt. */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** Opt-out-Marker als Kommentar in derselben Zeile. */
    private static final String EXEMPT_COMMENT = "escape-false-ok";

    /** Modul, an dem das Repo-Root erkannt wird. */
    private static final String ANKER_MODUL = "plaintext-root-webapp";

    private static final String RESOURCES_PFAD = "src/main/resources/META-INF/resources";

    @Test
    void keineViewRendertUnescaped() throws IOException {
        List<String> verstoesse = new ArrayList<>();
        List<Path> viewDateien = new ArrayList<>();

        Path repoRoot = findeRepoRoot();
        List<Path> resourceVerzeichnisse = findeResourceVerzeichnisse(repoRoot);
        assertTrue(!resourceVerzeichnisse.isEmpty(),
                "Keine META-INF/resources-Verzeichnisse gefunden — Pfadauflösung prüfen (Repo-Root: " + repoRoot + ")");

        for (Path root : resourceVerzeichnisse) {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> {
                    String name = p.toString();
                    return name.endsWith(".xhtml") || name.endsWith(".html");
                }).forEach(viewDateien::add);
            }
        }

        if (viewDateien.isEmpty()) {
            fail("Keine View-Dateien gefunden — Pfadauflösung prüfen (Repo-Root: " + repoRoot + ")");
        }

        for (Path view : viewDateien) {
            for (String treffer : findeTreffer(Files.readString(view))) {
                verstoesse.add("  " + repoRoot.relativize(view) + ":" + treffer);
            }
        }

        if (!verstoesse.isEmpty()) {
            fail("escape=\"false\" gefunden — Stored-XSS-Risiko, sobald dort ein DB-/Benutzerwert landet.\n"
                    + "Attribut entfernen (JSF escapt dann per Default). Braucht die Stelle wirklich HTML:\n"
                    + "serverseitig mit jsoup sanitisieren und den Marker '" + EXEMPT_COMMENT
                    + "' mit Begründung in dieselbe Zeile schreiben.\n"
                    + String.join("\n", verstoesse));
        }
    }

    /**
     * Selbsttest der Erkennung: verhindert, dass der Scan durch einen kaputten Regex still
     * zum No-Op wird und die Leitplanke unbemerkt wegfällt.
     */
    @Test
    void erkennungFunktioniert() {
        String inhalt = """
                <h:outputText value="#{obj.username}" escape="false"/>
                <h:outputText value="#{obj.mandat}" escape = 'FALSE'/>
                <h:outputText value="#{obj.startpage}" escape="false"/> <!-- escape-false-ok: sanitisiert -->
                <h:outputText value="#{obj.id}"/>
                <h:outputText value="#{obj.x}" escape="true"/>
                <!-- Doku: hier stand mal escape="false" -->
                <!-- mehrzeiliger Kommentar
                     mit escape="false" drin -->
                """;
        List<String> treffer = findeTreffer(inhalt);
        assertEquals(2, treffer.size(),
                "Erwartet: Zeile 1 und 2 als Verstoss; Zeile 3 per Opt-out befreit, 4+5 unauffällig, "
                        + "6-8 in Kommentaren. War: " + treffer);
        assertTrue(treffer.get(0).startsWith("1 "), "Erster Treffer muss Zeile 1 sein, war: " + treffer.get(0));
        assertTrue(treffer.get(1).startsWith("2 "), "Zweiter Treffer muss Zeile 2 sein, war: " + treffer.get(1));
    }

    /**
     * Findet alle nicht befreiten {@code escape="false"} und liefert "&lt;zeile&gt; — &lt;zeileninhalt&gt;".
     * <p>Erkannt wird auf dem kommentarfreien Text (Doku/auskommentierter Code zaehlt nicht),
     * der Opt-out-Marker dagegen auf der Originalzeile — er steht ja per Definition in einem
     * Kommentar.
     */
    private List<String> findeTreffer(String inhalt) {
        List<String> treffer = new ArrayList<>();
        String[] original = inhalt.split("\n", -1);
        String[] ohneKommentare = kommentareAusblenden(inhalt).split("\n", -1);
        for (int i = 0; i < ohneKommentare.length; i++) {
            Matcher matcher = ESCAPE_FALSE.matcher(ohneKommentare[i]);
            if (matcher.find() && !original[i].contains(EXEMPT_COMMENT)) {
                treffer.add((i + 1) + " — " + original[i].strip());
            }
        }
        return treffer;
    }

    /** Ersetzt den Inhalt von XML-Kommentaren durch Leerzeichen; Zeilenstruktur bleibt erhalten. */
    private String kommentareAusblenden(String inhalt) {
        StringBuilder sb = new StringBuilder(inhalt);
        Matcher matcher = COMMENT.matcher(inhalt);
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                if (sb.charAt(i) != '\n') {
                    sb.setCharAt(i, ' ');
                }
            }
        }
        return sb.toString();
    }

    /** Alle Modul-Verzeichnisse mit JSF-Resources (direkt unterhalb des Repo-Roots). */
    private List<Path> findeResourceVerzeichnisse(Path repoRoot) throws IOException {
        List<Path> ergebnis = new ArrayList<>();
        try (Stream<Path> module = Files.list(repoRoot)) {
            module.filter(Files::isDirectory)
                    .map(modul -> modul.resolve(RESOURCES_PFAD))
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(ergebnis::add);
        }
        return ergebnis;
    }

    /**
     * Findet das Repo-Root unabhängig vom user.dir-Kontext (Maven aus Modul oder Repo-Root, IDE):
     * läuft von der Test-Class-Location (target/test-classes) bzw. von user.dir aufwärts,
     * bis ein Verzeichnis das Anker-Modul enthält.
     */
    private Path findeRepoRoot() {
        List<Path> startpunkte = new ArrayList<>();
        try {
            startpunkte.add(Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException | RuntimeException _) {
            // z.B. exotischer ClassLoader — dann greift der user.dir-Fallback
        }
        startpunkte.add(Path.of(System.getProperty("user.dir")));

        for (Path start : startpunkte) {
            for (Path dir = start.toAbsolutePath(); dir != null; dir = dir.getParent()) {
                if (Files.isDirectory(dir.resolve(ANKER_MODUL))) {
                    return dir;
                }
            }
        }
        throw new IllegalStateException("Repo-Root nicht gefunden (Startpunkte: " + startpunkte
                + ") — Pfadauflösung prüfen");
    }
}
