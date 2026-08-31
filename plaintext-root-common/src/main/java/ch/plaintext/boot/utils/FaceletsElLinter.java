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
 * Reusable linter against a JSF/Facelets pitfall: a straight ASCII quotation mark
 * ({@code "}) inside an <em>inline</em> EL expression in body text
 * ({@code #{i18n.t('... " ...')}}) makes the Facelets text parser
 * ({@code com.sun.faces.facelets.el.ELText#findVarLength}) abort with
 * <em>"EL Expression Unbalanced"</em> — the whole page ends in a 500 /
 * whitelabel error, although the EL string looks syntactically harmless.
 *
 * <p>Concrete trigger (seen for real in {@code setup.xhtml}):
 * <pre>#{i18n.t('... Sichtbarkeit „Global – System" in der Mailbox ...')}</pre>
 * The straight {@code "} after "System" is read by the text parser as a string delimiter, finds no
 * counterpart and blows up the facelet compilation. Fix: use German typographic quotes
 * ({@code „ “}) — never a straight {@code "} in an inline {@code #{...}} in body text.
 *
 * <p><b>Only body text is affected.</b> In attribute values ({@code attr="#{i18n.t('x')}"}) the
 * EL expression is already enclosed in {@code "} and is parsed differently — that is perfectly fine
 * and common everywhere. The linter therefore first blanks out all tags and comments (the line
 * structure is preserved) and only checks the remaining body {@code #{...}} expressions.
 *
 * <p><b>Reuse across module boundaries:</b> like {@link MobileFormLinter} this class lives in
 * {@code plaintext-root-common} and is transitively on the test classpath of all dependent projects
 * (app, iot, fwtool, schuetu). A consumer test calls {@link #scan(Path)} on its
 * {@code META-INF/resources}. JDK only, no further dependencies.
 *
 * <p>Opt-out (practically never needed, since it is always a real bug): {@code <!-- el-quote-ok -->}
 * on the same line.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class FaceletsElLinter {

    /** XML/JSF tag (also multi-line): {@code <...>}. Blanked out for the body extraction. */
    private static final Pattern TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);

    /** XML comment (also multi-line): {@code <!-- ... -->}. */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** A single inline EL expression {@code #{...}} (without nested curly braces). */
    private static final Pattern INLINE_EL = Pattern.compile("#\\{[^{}]*}", Pattern.DOTALL);

    /** Opt-out marker as an inline comment on the same line. */
    private static final String EXEMPT_COMMENT = "el-quote-ok";

    private FaceletsElLinter() {
    }

    /**
     * A single violation: file, line (1-based) and a human-readable description.
     *
     * @param file    affected XHTML file
     * @param line    1-based line number of the EL expression
     * @param message description of the anti-pattern
     */
    public record Violation(Path file, int line, String message) {
        @Override
        public String toString() {
            return file + ":" + line + " -> " + message;
        }
    }

    /**
     * Scans all {@code *.xhtml} below {@code resourcesRoot} recursively and returns all violations
     * (a straight {@code "} inside an inline {@code #{...}} in body text).
     *
     * @param resourcesRoot root directory (e.g. {@code .../META-INF/resources}); if it does not
     *                      exist, an empty list is returned
     * @return list of violations (empty = clean)
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
        // Blank out comments and tags (non-newline -> space), so that only body text with inline EL
        // is left AND the line structure/offsets stay identical to the original.
        String body = blankSpans(blankSpans(original, COMMENT), TAG);

        Matcher el = INLINE_EL.matcher(body);
        while (el.find()) {
            String expr = el.group();
            if (expr.indexOf('"') < 0) {
                continue; // no straight ASCII quote -> fine
            }
            int lineNo = lineNumberAt(original, el.start());
            // Respect an opt-out on the same line (no further continue -> S135).
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

    /** Replaces every {@code pattern} occurrence with the same number of spaces; newlines are preserved. */
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

    /** Short form of the EL expression for the error message (max. 80 characters). */
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
