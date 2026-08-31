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
 * Shared reading logic for the i18n seed files ({@code classpath*:i18n/*.csv}) and for the scan
 * of the labels referenced in facelets ({@code #{i18n.t('…')}}).
 *
 * <p><b>Why here.</b> The seed importer ({@code I18nService} in plaintext-admin-i18n) and the
 * shared completeness test ({@code PlaintextI18nSeedTest} in plaintext-root-archtests) have to
 * read the same file in the same way — otherwise the test checks something other than what the
 * application imports at startup. Like {@link FaceletsElLinter} and {@link MobileFormLinter} the
 * class therefore lives in plaintext-root-common, is JDK only and thus on the classpath of every
 * consumer.
 *
 * <p><b>The key model.</b> There are no property keys: the key of a translation IS the German
 * default text, exactly as it stands in the XHTML in {@code i18n.t('Speichern')}. German
 * ({@code de}) is never translated ({@code I18nService.translate} returns the default text);
 * a seed line for {@code de} would have no effect.
 *
 * <p><b>Seed format</b> (identical to the export under {@code /api/i18n/export}):
 * <pre>
 * defaultLabel;languageCode;translatedText
 * # comment line
 * Speichern;en;Save
 * </pre>
 * The separator is the semicolon; the third column may contain further semicolons (split into
 * three parts). A value may be enclosed in straight quotation marks (inside, {@code ""} stands
 * for one {@code "}); the quotation marks do NOT protect against a semicolon in the label — a
 * label with a semicolon cannot be represented (export and import agree on that). A leading
 * apostrophe before {@code = + - @} — the export's formula protection against CSV injection —
 * is removed, so that an export can be checked in unchanged as a seed. The header line is only
 * skipped when it is the first line of the file and starts with {@code defaultLabel}.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class I18nSeedLinter {

    /** Separator of the seed/export CSV. */
    public static final String SEPARATOR = ";";

    /** Beginning of the (optional) header line. */
    public static final String HEADER_PREFIX = "defaultLabel";

    /** Characters in front of which the export places a protective apostrophe (formula triggers of spreadsheets). */
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    /**
     * An {@code i18n.t('…')} resp. {@code i18n.t("…")} call in a facelet. Group 1 = content of a
     * singly quoted, group 2 = content of a doubly quoted EL string; EL escapes
     * ({@code \'} resp. {@code \"}) stay in for the time being and are resolved in {@link #elString}.
     * The second parameter ({@code i18n.t('x', 'en')}) is irrelevant for the key.
     */
    private static final Pattern I18N_CALL = Pattern.compile(
            "i18n\\.t\\(\\s*(?:'((?:[^'\\\\]|\\\\.)*)'|\"((?:[^\"\\\\]|\\\\.)*)\")");

    /** Opt-out marker as a comment on the same line: the reference is not reported. */
    public static final String EXEMPT_COMMENT = "i18n-seed-ok";

    private I18nSeedLinter() {
    }

    /**
     * One line of a seed file.
     *
     * @param defaultLabel   German default text (the key)
     * @param languageCode   language code, e.g. {@code en}
     * @param translatedText translation
     * @param line           1-based line number in the file
     */
    public record SeedRow(String defaultLabel, String languageCode, String translatedText, int line) {
    }

    /**
     * Result of {@link #parse(BufferedReader)}: the usable lines and one text for every line
     * that cannot be imported (too few columns, an empty mandatory column).
     *
     * @param rows     importable lines in file order
     * @param problems descriptions of the skipped lines (with line number)
     */
    public record Seed(List<SeedRow> rows, List<String> problems) {
    }

    /**
     * Reads a seed CSV. Empty lines, {@code #} comments and the header line are skipped;
     * lines with fewer than three columns or with an empty column end up in {@link Seed#problems()}.
     *
     * @param reader reader on the UTF-8 content; it is not closed
     * @return lines and problems
     * @throws IOException when the reader fails
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
     * Reads a seed CSV from a file (UTF-8).
     *
     * @param file path of the CSV
     * @return lines and problems
     */
    public static Seed parse(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Seed-CSV nicht lesbar: " + file, e);
        }
    }

    /**
     * Undoes the CSV encoding of a value: enclosing straight quotation marks removed,
     * {@code ""} to {@code "}, and the export's protective apostrophe before a formula trigger
     * ({@code = + - @}) removed — that keeps export → import lossless.
     *
     * @param value raw column value (may be null)
     * @return decoded value, never null
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
     * A reference to a label in a facelet.
     *
     * @param file  XHTML file
     * @param line  1-based line of the call
     * @param label the key exactly as EL passes it to {@code i18n.t} at runtime
     *              (EL escapes and XML entities resolved)
     */
    public record LabelReference(Path file, int line, String label) {
    }

    /**
     * Scans all {@code *.xhtml} below {@code resourcesRoot} recursively for {@code i18n.t('…')}.
     * Facelets only: {@code i18n.t} is an EL call and exists in Java at most as a
     * Javadoc example. Lines with {@code i18n-seed-ok} are skipped.
     *
     * @param resourcesRoot root (e.g. {@code src/main/resources}); if it is missing, an empty list is returned
     * @return all references in file/line order (repeated mentions are preserved)
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
     * Turns the source text of an EL string literal into its runtime value: EL escapes ({@code \'},
     * {@code \"}, {@code \\}) and the XML entities that the Facelets parser resolves before the EL
     * evaluation — in an attribute value as well as in body text.
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
