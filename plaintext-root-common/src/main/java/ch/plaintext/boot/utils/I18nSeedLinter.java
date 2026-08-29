/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Gemeinsame Lese-Logik fuer die i18n-Seed-Dateien ({@code classpath*:i18n/*.csv}) und den Scan
 * der in Facelets referenzierten Labels ({@code #{i18n.t('…')}}).
 *
 * <p><b>Warum hier.</b> Der Seed-Importer ({@code I18nService} in plaintext-admin-i18n) und der
 * geteilte Vollstaendigkeits-Test ({@code PlaintextI18nSeedTest} in plaintext-root-archtests)
 * muessen dieselbe Datei gleich lesen — sonst prueft der Test etwas anderes, als die Anwendung
 * beim Start importiert. Wie {@link FaceletsElLinter} und {@link MobileFormLinter} liegt die Klasse
 * deshalb in plaintext-root-common, ist Nur-JDK und damit auf dem Classpath jedes Consumers.
 *
 * <p><b>Das Schluessel-Modell.</b> Es gibt keine Property-Keys: der Schluessel einer Uebersetzung
 * IST der deutsche Vorgabetext, so wie er im XHTML in {@code i18n.t('Speichern')} steht. Deutsch
 * ({@code de}) wird nie uebersetzt ({@code I18nService.translate} gibt den Vorgabetext zurueck);
 * eine Seed-Zeile fuer {@code de} waere wirkungslos.
 *
 * <p><b>Seed-Format</b> (identisch mit dem Export unter {@code /api/i18n/export}):
 * <pre>
 * defaultLabel;languageCode;translatedText
 * # Kommentarzeile
 * Speichern;en;Save
 * </pre>
 * Trennzeichen ist das Semikolon; die dritte Spalte darf weitere Semikola enthalten (Split auf
 * drei Teile). Ein Wert darf in gerade Anfuehrungszeichen eingeschlossen sein (innen {@code ""}
 * fuer ein {@code "}); die Anfuehrungszeichen schuetzen NICHT vor einem Semikolon im Label — ein
 * Label mit Semikolon ist nicht darstellbar (Export und Import sind sich darin einig). Ein
 * fuehrendes Apostroph vor {@code = + - @} — der Formel-Schutz des Exports gegen CSV-Injection —
 * wird entfernt, damit ein Export unveraendert als Seed eingecheckt werden kann. Die Kopfzeile
 * wird nur uebersprungen, wenn sie die erste Zeile der Datei ist und mit {@code defaultLabel}
 * beginnt.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class I18nSeedLinter {

    /** Trennzeichen der Seed-/Export-CSV. */
    public static final String SEPARATOR = ";";

    /** Beginn der (optionalen) Kopfzeile. */
    public static final String HEADER_PREFIX = "defaultLabel";

    /** Zeichen, vor denen der Export ein schuetzendes Apostroph setzt (Formel-Trigger von Tabellenkalkulationen). */
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    /**
     * Ein {@code i18n.t('…')}- bzw. {@code i18n.t("…")}-Aufruf in einem Facelet. Gruppe 1 = Inhalt
     * eines einfach, Gruppe 2 = Inhalt eines doppelt angefuehrten EL-Strings; EL-Escapes
     * ({@code \'} bzw. {@code \"}) bleiben erst einmal drin und werden in {@link #elString} aufgeloest.
     * Der zweite Parameter ({@code i18n.t('x', 'en')}) ist fuer den Schluessel unerheblich.
     */
    private static final Pattern I18N_CALL = Pattern.compile(
            "i18n\\.t\\(\\s*(?:'((?:[^'\\\\]|\\\\.)*)'|\"((?:[^\"\\\\]|\\\\.)*)\")");

    /** Opt-out-Marker als Kommentar in derselben Zeile: die Referenz wird nicht gemeldet. */
    public static final String EXEMPT_COMMENT = "i18n-seed-ok";

    private I18nSeedLinter() {
    }

    /**
     * Eine Zeile einer Seed-Datei.
     *
     * @param defaultLabel   deutscher Vorgabetext (Schluessel)
     * @param languageCode   Sprachcode, z. B. {@code en}
     * @param translatedText Uebersetzung
     * @param line           1-basierte Zeilennummer in der Datei
     */
    public record SeedRow(String defaultLabel, String languageCode, String translatedText, int line) {
    }

    /**
     * Ergebnis von {@link #parse(BufferedReader)}: die brauchbaren Zeilen und je ein Text pro Zeile,
     * die nicht importiert werden kann (zu wenig Spalten, leere Pflichtspalte).
     *
     * @param rows     importierbare Zeilen in Dateireihenfolge
     * @param problems Beschreibungen der uebersprungenen Zeilen (mit Zeilennummer)
     */
    public record Seed(List<SeedRow> rows, List<String> problems) {
    }

    /**
     * Liest eine Seed-CSV. Leerzeilen, {@code #}-Kommentare und die Kopfzeile werden ausgelassen;
     * Zeilen mit weniger als drei Spalten oder einer leeren Spalte landen in {@link Seed#problems()}.
     *
     * @param reader Leser auf den UTF-8-Inhalt; wird nicht geschlossen
     * @return Zeilen und Probleme
     * @throws IOException wenn der Leser versagt
     */
    public static Seed parse(BufferedReader reader) throws IOException {
        List<SeedRow> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (lineNumber == 1 && line.startsWith(HEADER_PREFIX)) {
                continue;
            }
            String[] parts = line.split(SEPARATOR, 3);
            if (parts.length < 3) {
                problems.add("Zeile " + lineNumber + ": " + parts.length + " Spalten statt 3");
                continue;
            }
            String defaultLabel = unescape(parts[0].trim());
            String languageCode = unescape(parts[1].trim());
            String translatedText = unescape(parts[2].trim());
            if (defaultLabel.isEmpty() || languageCode.isEmpty() || translatedText.isEmpty()) {
                problems.add("Zeile " + lineNumber + ": leere Spalte (defaultLabel, languageCode und translatedText sind Pflicht)");
                continue;
            }
            rows.add(new SeedRow(defaultLabel, languageCode, translatedText, lineNumber));
        }
        return new Seed(rows, problems);
    }

    /**
     * Liest eine Seed-CSV aus einer Datei (UTF-8).
     *
     * @param file Pfad der CSV
     * @return Zeilen und Probleme
     */
    public static Seed parse(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Seed-CSV nicht lesbar: " + file, e);
        }
    }

    /**
     * Hebt die CSV-Kodierung eines Werts auf: umschliessende gerade Anfuehrungszeichen weg,
     * {@code ""} zu {@code "}, und das schuetzende Apostroph des Exports vor einem Formel-Trigger
     * ({@code = + - @}) weg — so bleibt Export → Import verlustfrei.
     *
     * @param value roher Spaltenwert (darf null sein)
     * @return dekodierter Wert, nie null
     */
    public static String unescape(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length() - 1).replace("\"\"", "\"");
        }
        if (result.length() >= 2 && result.charAt(0) == '\'' && FORMULA_TRIGGERS.indexOf(result.charAt(1)) >= 0) {
            result = result.substring(1);
        }
        return result;
    }

    /**
     * Eine Referenz auf ein Label in einem Facelet.
     *
     * @param file  XHTML-Datei
     * @param line  1-basierte Zeile des Aufrufs
     * @param label der Schluessel, so wie ihn EL zur Laufzeit an {@code i18n.t} uebergibt
     *              (EL-Escapes und XML-Entitaeten aufgeloest)
     */
    public record LabelReference(Path file, int line, String label) {
    }

    /**
     * Scannt rekursiv alle {@code *.xhtml} unter {@code resourcesRoot} nach {@code i18n.t('…')}.
     * Nur Facelets: {@code i18n.t} ist ein EL-Aufruf und existiert in Java hoechstens als
     * Javadoc-Beispiel. Zeilen mit {@code i18n-seed-ok} werden ausgelassen.
     *
     * @param resourcesRoot Wurzel (z. B. {@code src/main/resources}); fehlt sie, kommt eine leere Liste
     * @return alle Referenzen in Datei-/Zeilenreihenfolge (Mehrfachnennungen bleiben erhalten)
     */
    public static List<LabelReference> scanReferences(Path resourcesRoot) {
        List<LabelReference> refs = new ArrayList<>();
        if (resourcesRoot == null || !Files.isDirectory(resourcesRoot)) {
            return refs;
        }
        try (Stream<Path> files = Files.walk(resourcesRoot)) {
            files.filter(p -> p.toString().endsWith(".xhtml"))
                 .sorted()
                 .forEach(f -> scanFile(f, refs));
        } catch (IOException e) {
            throw new UncheckedIOException("Konnte XHTML-Ressourcen nicht scannen: " + resourcesRoot, e);
        }
        return refs;
    }

    private static void scanFile(Path file, List<LabelReference> refs) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Konnte XHTML nicht lesen: " + file, e);
        }
        Matcher m = I18N_CALL.matcher(content);
        while (m.find()) {
            int lineNo = lineNumberAt(content, m.start());
            if (lineContent(content, lineNo).contains(EXEMPT_COMMENT)) {
                continue;
            }
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            refs.add(new LabelReference(file, lineNo, elString(raw)));
        }
    }

    /**
     * Macht aus dem Quelltext eines EL-String-Literals den Laufzeitwert: EL-Escapes ({@code \'},
     * {@code \"}, {@code \\}) und die XML-Entitaeten, die der Facelets-Parser vor der EL-Auswertung
     * aufloest — im Attributwert wie im Body-Text.
     */
    static String elString(String raw) {
        String s = raw.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\");
        return s.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
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
        int line = 1;
        int start = 0;
        for (int i = 0; i < content.length() && line < lineNo; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                start = i + 1;
            }
        }
        int end = content.indexOf('\n', start);
        return end < 0 ? content.substring(start) : content.substring(start, end);
    }
}
