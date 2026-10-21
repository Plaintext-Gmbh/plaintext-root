/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Zaehlt je XHTML die drei Verstoesse gegen die UI-2-Grundlage (Karte 1548) — die Scan-Logik zu
 * {@link PlaintextUiGrundlageLinterTest}.
 *
 * <ul>
 *   <li>{@code style} — ein {@code style="…"}-Attribut. Farben, Abstaende und Groessen kommen aus
 *       den Tokens und Klassen in {@code plaintext-layout/css/layout.css}; eine Inline-Farbe
 *       kennt keinen Dunkelmodus.</li>
 *   <li>{@code growl} — ein eigenes {@code <p:growl>}. Das Template liefert genau eines
 *       ({@code globalGrowl}, Karte 1547), das sich bei jeder Ajax-Antwort selbst aktualisiert;
 *       ein zweites zeigt jede Meldung doppelt oder an einem anderen Ort.</li>
 *   <li>{@code csrf} — ein {@code <h:form>} ohne {@code _csrf}-Feld: der Absenden endet in
 *       HTTP 403 (root prueft das schon mit {@code CsrfFormInvariantTest}; hier steht es, damit
 *       die Anwendungen die Regel mit dem Jar bekommen).</li>
 * </ul>
 *
 * <p>Kommentare zaehlen nicht mit: ein {@code style="…"} als Beispiel in einem Kommentar ist kein
 * Verstoss. Die Zeilenumbrueche bleiben beim Ausblenden erhalten, damit die Zeilennummern stimmen.</p>
 */
final class UiGrundlageLinter {

    /** Das eine Growl des Templates — die Regel gilt fuer alle anderen Dateien. */
    static final String TEMPLATE_PFAD = "META-INF/resources/includes/template.xhtml";

    /** Ein Attribut, das genau {@code style} heisst — nicht {@code buttonStyle=}, nicht {@code styleClass=}. */
    private static final Pattern STYLE = Pattern.compile("\\sstyle\\s*=\\s*[\"']");
    private static final Pattern GROWL = Pattern.compile("<p:growl\\b");
    private static final Pattern FORM_START = Pattern.compile("<h:form\\b[^>]*>");
    private static final Pattern CSRF_INPUT = Pattern.compile("name\\s*=\\s*[\"']_csrf[\"']");
    private static final Pattern KOMMENTAR = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** Die drei Zaehler einer Datei. */
    record Zaehlung(int style, int growl, int csrf) {

        static final Zaehlung NULL = new Zaehlung(0, 0, 0);

        boolean leer() {
            return style == 0 && growl == 0 && csrf == 0;
        }

        /** Wo diese Zaehlung ueber {@code grenze} liegt, als lesbare Liste; leer = alles im Rahmen. */
        List<String> ueber(Zaehlung grenze) {
            List<String> ueber = new ArrayList<>();
            if (style > grenze.style) {
                ueber.add("style=\"…\" " + style + " statt hoechstens " + grenze.style);
            }
            if (growl > grenze.growl) {
                ueber.add("eigenes p:growl " + growl + " statt hoechstens " + grenze.growl);
            }
            if (csrf > grenze.csrf) {
                ueber.add("h:form ohne _csrf " + csrf + " statt hoechstens " + grenze.csrf);
            }
            return ueber;
        }

        boolean unter(Zaehlung grenze) {
            return style < grenze.style || growl < grenze.growl || csrf < grenze.csrf;
        }
    }

    private UiGrundlageLinter() {
    }

    /** Zaehlt alle XHTML unter {@code root}; Dateien ohne Befund fehlen im Ergebnis. Schluessel: absoluter Pfad. */
    static Map<Path, Zaehlung> scan(Path root) {
        Map<Path, Zaehlung> ergebnis = new TreeMap<>();
        if (root == null || !Files.isDirectory(root)) {
            return ergebnis;
        }
        try (Stream<Path> dateien = Files.walk(root)) {
            for (Path datei : dateien.filter(p -> p.toString().endsWith(".xhtml")).sorted().toList()) {
                Zaehlung z = zaehle(datei);
                if (!z.leer()) {
                    ergebnis.put(datei.toAbsolutePath().normalize(), z);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Nicht lesbar: " + root, e);
        }
        return ergebnis;
    }

    static Zaehlung zaehle(Path datei) {
        String inhalt;
        try {
            inhalt = Files.readString(datei, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Nicht lesbar: " + datei, e);
        }
        return zaehle(inhalt, datei.toString().replace('\\', '/').endsWith(TEMPLATE_PFAD));
    }

    static Zaehlung zaehle(String inhalt, boolean istTemplate) {
        String ohneKommentare = ohneKommentare(inhalt);
        int style = (int) STYLE.matcher(ohneKommentare).results().count();
        int growl = istTemplate ? 0 : (int) GROWL.matcher(ohneKommentare).results().count();
        int csrf = 0;
        Matcher form = FORM_START.matcher(ohneKommentare);
        while (form.find()) {
            int ende = ohneKommentare.indexOf("</h:form>", form.end());
            String rumpf = ohneKommentare.substring(form.end(), ende < 0 ? ohneKommentare.length() : ende);
            if (!CSRF_INPUT.matcher(rumpf).find()) {
                csrf++;
            }
        }
        return new Zaehlung(style, growl, csrf);
    }

    /** Kommentare durch Leerzeichen ersetzen, Zeilenumbrueche behalten. */
    static String ohneKommentare(String inhalt) {
        Matcher m = KOMMENTAR.matcher(inhalt);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replaceAll("[^\\n]", " ")));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
