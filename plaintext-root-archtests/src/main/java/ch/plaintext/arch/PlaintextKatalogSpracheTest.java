/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 1438 (Daniel 08.10.2026): every text of the interface catalog is English — the Javadoc purpose
 * of interfaces and methods and the texts of {@code @ModulApiUmsetzung} (description, hints, examples),
 * in root and in every app. MCP and the module page show exactly these texts.
 *
 * <p>The check reads the catalogs the annotation processor writes into the jars
 * ({@code META-INF/plaintext-katalog/*.json}), i.e. the same source MCP reads. It runs through
 * Surefire {@code dependenciesToScan} in the webapp module of each app, whose test class path holds
 * the catalogs of root, the app and all its modules.
 *
 * <p>German is recognised heuristically: an umlaut or ß, or at least two common German words. Code,
 * links and quoted text are ignored first, so an English sentence may name a German UI label
 * ({@code "Aktuelle Woche"}) or a class ({@code {@link IMahlzeitenAbfrage}}).
 */
class PlaintextKatalogSpracheTest {

    static final String VERZEICHNIS = "META-INF/plaintext-katalog/";
    /** Catalog keys whose values are prose. */
    static final Set<String> TEXT_SCHLUESSEL = Set.of("zweck", "beschreibung", "hinweise", "beispiele");
    /** Elements that name what the following texts belong to. */
    static final Set<String> NAME_SCHLUESSEL = Set.of("name", "klasse");

    /**
     * Code, links, HTML code and quotes: may contain German names and labels. A word directly before
     * {@code (} or after {@code .} is a method or field ({@code fuer("bild.vorschau")}, {@code x.liste}).
     */
    static final Pattern AUSGENOMMEN = Pattern.compile(
            "\\{@\\w+[^}]*}|<code>.*?</code>|`[^`]*`|«[^»]*»|„[^“\"]*[“\"]|\"[^\"]*\"|\\w+\\(|\\.\\w+",
            Pattern.DOTALL);
    static final Pattern UMLAUT = Pattern.compile("[äöüÄÖÜß]");
    static final Pattern WORT = Pattern.compile("\\p{L}+");
    /**
     * Frequent German words that are not English words as well (left out: die, an, in, am, so, was, will,
     * also, man, war, bin, hat, all, pro).
     */
    static final Set<String> DEUTSCHE_WOERTER = Set.of("der", "das", "und", "nicht", "fuer", "ist", "wird",
            "werden", "mit", "ein", "eine", "einen", "einem", "einer", "eines", "dem", "den", "des", "auf", "oder",
            "bei", "nach", "ueber", "zum", "zur", "vom", "sind", "kein", "keine", "auch", "nur", "wenn", "aus",
            "wie", "sich", "zu", "im", "beim", "ohne", "dass", "liefert", "gibt", "noch", "schon", "von", "als",
            "aber", "alle", "allen", "alles", "andere", "anderen", "anderer", "bis", "damit", "dann", "diese",
            "dieser", "dieses", "durch", "es", "er", "sie", "um", "unter", "vor", "weil", "welche", "welcher",
            "wieder", "wo", "zwischen", "kann", "muss", "soll", "darf", "sein", "seine", "ihre", "jede", "jeder",
            "jedes", "immer", "nie", "mehr", "sowie", "bzw", "usw", "dabei", "davon", "dazu", "statt", "sonst",
            "gegen", "hier", "neu", "neue", "neuen", "eigene", "eigenen", "schreibt", "liest", "gelesen");
    /** Short texts (up to this many words) count as German with a single German word. */
    static final int KURZ = 5;

    /** A German text found in a catalog. */
    record Fund(String modul, String element, String schluessel, String grund, String text) {
        @Override
        public String toString() {
            String t = text.replaceAll("\\s+", " ").strip();
            return modul + " · " + element + " · " + schluessel + ": " + grund + " — «"
                    + (t.length() > 90 ? t.substring(0, 90) + "…" : t) + "»";
        }
    }

    @Test
    @DisplayName("Karte 1438: alle Katalogtexte sind englisch")
    void katalogtexteSindEnglisch() throws IOException {
        List<Fund> funde = new ArrayList<>();
        int kataloge = 0;
        for (URL u : kataloge()) {
            try (InputStream in = u.openStream()) {
                funde.addAll(pruefe(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                kataloge++;
            }
        }
        List<String> zeilen = funde.stream().map(Fund::toString).sorted().toList();
        assertTrue(zeilen.isEmpty(), zeilen.size() + " deutsche Katalogtexte in " + kataloge + " Katalogen (Karte 1438: "
                + "Javadoc-Zweck und @ModulApiUmsetzung englisch schreiben, MCP und Modulseite zeigen sie):\n  "
                + String.join("\n  ", zeilen));
    }

    /** All catalog files on the class path (one per module jar). */
    static List<URL> kataloge() throws IOException {
        ClassLoader cl = PlaintextKatalogSpracheTest.class.getClassLoader();
        List<URL> raus = new ArrayList<>();
        // A catalog is named after its module; the directory itself is listed per jar.
        for (URL verzeichnis : Collections.list(cl.getResources(VERZEICHNIS))) {
            raus.addAll(dateien(verzeichnis));
        }
        return raus;
    }

    private static List<URL> dateien(URL verzeichnis) throws IOException {
        List<URL> raus = new ArrayList<>();
        String v = verzeichnis.toString();
        if (v.startsWith("jar:")) {
            var verbindung = (java.net.JarURLConnection) verzeichnis.openConnection();
            verbindung.setUseCaches(false);
            try (var jar = verbindung.getJarFile()) {
                for (var e : Collections.list(jar.entries())) {
                    if (e.getName().startsWith(VERZEICHNIS) && e.getName().endsWith(".json")) {
                        raus.add(java.net.URI.create(v.substring(0, v.indexOf("!/") + 2) + e.getName()).toURL());
                    }
                }
            }
        } else if (v.startsWith("file:")) {
            java.io.File[] f = new java.io.File(java.net.URI.create(v)).listFiles((d, n) -> n.endsWith(".json"));
            for (java.io.File x : f == null ? new java.io.File[0] : f) {
                raus.add(x.toURI().toURL());
            }
        }
        return raus;
    }

    /** @return the German texts of one catalog */
    static List<Fund> pruefe(String json) {
        List<Fund> raus = new ArrayList<>();
        String modul = "?";
        String element = "?";
        String schluessel = null;
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c != '"') {
                i++;
                continue;
            }
            StringBuilder s = new StringBuilder();
            i = lies(json, i + 1, s);
            int j = i;
            while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
                j++;
            }
            if (j < json.length() && json.charAt(j) == ':') {
                schluessel = s.toString();
                i = j + 1;
            } else if ("modul".equals(schluessel)) {
                modul = s.toString();
            } else if (NAME_SCHLUESSEL.contains(schluessel)) {
                element = s.toString();
            } else if (TEXT_SCHLUESSEL.contains(schluessel)) {
                String grund = deutsch(s.toString());
                if (grund != null) {
                    raus.add(new Fund(modul, element, schluessel, grund, s.toString()));
                }
            }
        }
        return raus;
    }

    /** Reads a JSON string body from {@code i} (after the quote) into {@code s}; @return index after the closing quote */
    private static int lies(String json, int i, StringBuilder s) {
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                return i + 1;
            }
            if (c == '\\' && i + 1 < json.length()) {
                char e = json.charAt(i + 1);
                switch (e) {
                    case 'n' -> s.append('\n');
                    case 't' -> s.append('\t');
                    case 'r' -> s.append('\r');
                    case 'u' -> {
                        s.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                        i += 4;
                    }
                    default -> s.append(e);
                }
                i += 2;
                continue;
            }
            s.append(c);
            i++;
        }
        return i;
    }

    /** @return why the text reads as German, or {@code null} for English */
    static String deutsch(String text) {
        String t = AUSGENOMMEN.matcher(text == null ? "" : text).replaceAll(" ");
        Matcher u = UMLAUT.matcher(t);
        if (u.find()) {
            return "Umlaut «" + u.group() + "»";
        }
        List<String> woerter = new ArrayList<>();
        int alle = 0;
        Matcher w = WORT.matcher(t.toLowerCase(Locale.ROOT));
        while (w.find()) {
            alle++;
            if (DEUTSCHE_WOERTER.contains(w.group())) {
                woerter.add(w.group());
            }
        }
        return woerter.size() >= 2 || (!woerter.isEmpty() && alle <= KURZ) ? "deutsche Wörter " + woerter.stream().distinct().limit(4).toList() : null;
    }
}
