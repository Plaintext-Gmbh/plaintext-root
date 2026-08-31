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
 * Reusable linter against mobile anti-patterns in JSF/PrimeFaces XHTML.
 *
 * <p>Core finding: {@code <p:dialog width="560">} (a fixed px width) runs off the viewport to
 * the right on a phone. The central {@code mobile-responsive.css} (from
 * plaintext-root-template) does cap every dialog at {@code 96vw}, but the linter keeps new
 * fixed-width dialogs visible so that developers decide deliberately (styleClass + CSS instead
 * of fixed px, or a justified opt-out).
 *
 * <p><b>Reuse across module boundaries:</b> this class lives in
 * {@code plaintext-root-common} and is therefore transitively on the test classpath of all
 * dependent projects (app, iot, fwtool, schuetu). A consumer test simply calls
 * {@link #scan(Path)} on its {@code META-INF/resources} and lets the build fail on
 * violations — no new artifact, no copy-paste needed. If a consumer has not bumped the root
 * version yet, it can also copy this file 1:1 into its own test tree; it has no dependencies
 * beyond the JDK.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class MobileFormLinter {

    /**
     * Captures the complete {@code <p:dialog ...>} opening tag (also multi-line).
     * {@code [^>]*} matches across line breaks thanks to {@link Pattern#DOTALL}.
     */
    private static final Pattern DIALOG_OPEN_TAG =
            Pattern.compile("<p:dialog\\b[^>]*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** Fixed numeric px width as an attribute: {@code width="560"} (not "100%", not "50vw"). */
    private static final Pattern FIXED_WIDTH_ATTR =
            Pattern.compile("\\bwidth\\s*=\\s*\"\\s*(\\d+)(px)?\\s*\"", Pattern.CASE_INSENSITIVE);

    /** Any {@code width="..."} attribute (e.g. also {@code "100%"} / {@code "50vw"}). */
    private static final Pattern ANY_WIDTH_ATTR =
            Pattern.compile("\\bwidth\\s*=\\s*\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    /** Opt-out marker in the styleClass attribute. */
    /** Mandatory marker: the dialog is handled for mobile (the central width/scroll rules apply). */
    private static final String SAFE_STYLECLASS = "mobile-safe";

    private static final String EXEMPT_STYLECLASS = "mobile-exempt";

    /** Opt-out marker as an inline comment on the same line as the p:dialog tag. */
    private static final String EXEMPT_COMMENT = "mobile-ok";

    private MobileFormLinter() {
    }

    /**
     * A single violation: file, line (1-based) and a human-readable description.
     *
     * @param file    affected XHTML file
     * @param line    1-based line number of the tag start
     * @param message description of the anti-pattern
     */
    public record Violation(Path file, int line, String message) {
        @Override
        public String toString() {
            return file + ":" + line + " -> " + message;
        }
    }

    /**
     * Scans all {@code *.xhtml} below {@code resourcesRoot} recursively and returns all
     * mobile anti-pattern violations.
     *
     * <p>Currently checked: {@code p:dialog} with a fixed px width and without an opt-out marker.
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
     * Checks a single {@code <p:dialog>} tag against the mobile rules (as a method instead of a loop
     * {@code continue}, to avoid S135):
     * <ol>
     *   <li>Every dialog must be <b>handled for mobile</b> — {@code styleClass="mobile-safe"} (the width and
     *       the central flex scroll layout then apply, the footer/the buttons stay reachable on the
     *       phone) — or exempted with a rationale ({@code mobile-exempt} / {@code <!-- mobile-ok -->}).</li>
     *   <li>A {@code mobile-safe} dialog must additionally not carry a fixed px width (mobile-safe governs
     *       the width).</li>
     * </ol>
     */
    private static void checkDialog(Path file, String tag, String content, int lineNo, List<Violation> violations) {
        if (isExempt(tag, content, lineNo)) {
            return; // deliberately exempted (mobile-exempt / mobile-ok)
        }
        boolean safe = tag.contains(SAFE_STYLECLASS);
        Matcher fixedPx = FIXED_WIDTH_ATTR.matcher(tag);
        if (fixedPx.find()) {
            // A fixed px width runs off the viewport on the phone - even with mobile-safe (which governs the width).
            violations.add(new Violation(file, lineNo,
                    "p:dialog mit fixer px-Breite width=\"" + fixedPx.group(1) + "\": fixe width entfernen und "
                            + (safe ? "die Breite ueber mobile-safe/CSS regeln." : "styleClass=\"" + SAFE_STYLECLASS
                            + "\" verwenden, oder mit styleClass=\"" + EXEMPT_STYLECLASS + "\" bzw. <!-- "
                            + EXEMPT_COMMENT + " --> begruendet ausnehmen.")));
            return;
        }
        if (safe) {
            return; // mobile-safe without a fixed px width -> ok (width + footer scroll governed centrally)
        }
        // Neither mobile-safe nor a fixed px width: only ok when a responsive width (e.g. "100%") is set.
        if (!ANY_WIDTH_ATTR.matcher(tag).find()) {
            violations.add(new Violation(file, lineNo,
                    "p:dialog ohne Mobile-Behandlung: styleClass=\"" + SAFE_STYLECLASS + "\" ergaenzen (Breite und "
                            + "Footer-Scroll werden zentral geregelt, die Buttons bleiben auf dem Handy erreichbar) "
                            + "oder eine responsive width wie \"100%\" setzen, sonst kann die PrimeFaces-Standard"
                            + "breite ueberlaufen. Begruendete Ausnahme via \"" + EXEMPT_STYLECLASS + "\" / <!-- "
                            + EXEMPT_COMMENT + " -->."));
        }
    }

    /** Opt-out: styleClass contains {@code mobile-exempt}, or the tag line contains {@code <!-- mobile-ok -->}. */
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
