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
 * Reusable linter against three JSF/Facelets pitfalls that are documented in the status report
 * of 29.08.2026 for plaintext-root and produced silent malfunctions there — no stack trace, the
 * page renders, but a part of it does nothing.
 *
 * <ol>
 *   <li><b>{@code <f:metadata>} after the first {@code <ui:define>}</b> ({@link #RULE_METADATA_NACH_DEFINE}):
 *       the pages build on {@code /includes/template.xhtml}, and the template has no
 *       {@code ui:insert} for metadata. An {@code f:metadata} block inside a
 *       {@code ui:define} therefore ends up somewhere in the page body — {@code f:viewParam},
 *       {@code f:viewAction} and {@code preRenderView} listeners are ineffective there resp. run
 *       only by chance, depending on how Mojarra evaluates the metadata facet. In root 20 pages
 *       looked like that. Correct: the block belongs directly below the opening
 *       {@code <ui:composition ...>}, before the first {@code <ui:define>} (template:
 *       {@code menudiagnose.xhtml}).</li>
 *   <li><b>{@code <f:metadata>} inside {@code <h:form>}</b> ({@link #RULE_METADATA_IN_FORM}):
 *       a special case of (1), but reported separately, because here the listener additionally
 *       runs along with every Ajax postback of the form and reloads data that the user is just
 *       editing. 16 of the 20 root cases looked like this.</li>
 *   <li><b>{@code onchange="submit()"} on a PrimeFaces component</b> ({@link #RULE_ONCHANGE_SUBMIT}):
 *       PrimeFaces 15 no longer triggers a request through this inline handler attribute — the
 *       selection had no effect (documented on the type selection of the data management
 *       {@code rootentities.xhtml}: no table appeared). The correct way is a regular
 *       {@code <p:ajax listener=... update=.../>}.</li>
 * </ol>
 *
 * <p><b>Exceptions:</b> {@code <!-- jsf-view-ok -->} on the same line as the reported tag
 * exempts exactly that hit with a rationale (the same pattern as {@code mobile-ok} /
 * {@code el-quote-ok}). Whole files are exempted by the shared test through the reactor's
 * allowlist (see {@code PlaintextJsfViewLinterTest} in plaintext-root-archtests).
 *
 * <p><b>Reuse across module boundaries:</b> like {@link MobileFormLinter} and
 * {@link FaceletsElLinter} the class lives in {@code plaintext-root-common} (JDK only) and is
 * transitively on the test classpath of all dependent projects; the accompanying test ships as
 * a jar from plaintext-root-archtests.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class JsfViewLinter {

    /** Rule identifier: f:metadata sits after the first ui:define (resp. inside a ui:define). */
    public static final String RULE_METADATA_NACH_DEFINE = "metadata-nach-ui-define";
    /** Rule identifier: f:metadata sits inside an h:form. */
    public static final String RULE_METADATA_IN_FORM = "metadata-in-h-form";
    /** Rule identifier: onchange="submit()" / "this.form.submit()" on a p: component. */
    public static final String RULE_ONCHANGE_SUBMIT = "onchange-submit-an-p-komponente";

    /** Opt-out marker as an inline comment on the same line as the reported tag. */
    public static final String EXEMPT_COMMENT = "jsf-view-ok";

    /** XML comment (also multi-line) — blanked out so that examples in comments do not count. */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Pattern METADATA_OPEN = Pattern.compile("<f:metadata\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFINE_OPEN = Pattern.compile("<ui:define\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM_OPEN = Pattern.compile("<h:form\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM_CLOSE = Pattern.compile("</h:form\\s*>", Pattern.CASE_INSENSITIVE);

    /**
     * Opening {@code <p:xxx ...>} tag (also multi-line) that carries an {@code onchange} attribute
     * with {@code submit()} resp. {@code this.form.submit()}.
     */
    private static final Pattern P_ONCHANGE_SUBMIT = Pattern.compile(
            "<p:[A-Za-z]+\\b[^>]*\\bonchange\\s*=\\s*\"\\s*(?:this\\.form\\.)?submit\\(\\)\\s*;?\\s*\"",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private JsfViewLinter() {
    }

    /**
     * A single violation: file, line (1-based), rule identifier and a human-readable
     * description.
     *
     * @param file    affected XHTML file
     * @param line    1-based line number of the reported tag
     * @param rule    rule identifier ({@link #RULE_METADATA_NACH_DEFINE}, {@link #RULE_METADATA_IN_FORM},
     *                {@link #RULE_ONCHANGE_SUBMIT})
     * @param message description of the anti-pattern including the fix
     */
    public record Violation(Path file, int line, String rule, String message) {
        @Override
        public String toString() {
            return file + ":" + line + " [" + rule + "] -> " + message;
        }
    }

    /**
     * Scans all {@code *.xhtml} below {@code resourcesRoot} recursively and returns all violations
     * of the three rules.
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
        // Blank out comments (non-newline -> space): offsets/lines stay identical to the original.
        String content = blankSpans(original, COMMENT);

        pruefeMetadata(file, original, content, violations);
        pruefeOnchangeSubmit(file, original, content, violations);
    }

    /** Rules 1 and 2 — both hinge on the position of the first {@code <f:metadata}. */
    private static void pruefeMetadata(Path file, String original, String content, List<Violation> violations) {
        Matcher metadata = METADATA_OPEN.matcher(content);
        if (!metadata.find()) {
            return;
        }
        int pos = metadata.start();
        int lineNo = lineNumberAt(original, pos);
        if (lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
            return; // exempted with a rationale
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

    /** Rule 3 — onchange="submit()" on a PrimeFaces component. */
    private static void pruefeOnchangeSubmit(Path file, String original, String content, List<Violation> violations) {
        Matcher tag = P_ONCHANGE_SUBMIT.matcher(content);
        while (tag.find()) {
            int lineNo = lineNumberAt(original, tag.start());
            if (lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
                continue; // exempted with a rationale
            }
            violations.add(new Violation(file, lineNo, RULE_ONCHANGE_SUBMIT,
                    "onchange=\"submit()\" an einer p:-Komponente: PrimeFaces 15 loest darueber keinen Request "
                            + "aus, die Auswahl bleibt wirkungslos (belegt in rootentities.xhtml). Fix: "
                            + "<p:ajax listener=\"#{bean.xyz}\" update=\"...\"/> verwenden, oder mit <!-- "
                            + EXEMPT_COMMENT + " --> in derselben Zeile begruendet ausnehmen."));
        }
    }

    /** Number of open {@code <h:form>} before {@code offset}: openings minus closings. */
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
