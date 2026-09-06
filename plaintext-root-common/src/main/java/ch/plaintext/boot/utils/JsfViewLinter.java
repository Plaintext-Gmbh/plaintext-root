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

    /** Rule identifier: an Ajax search expression runs through the id of a non-NamingContainer. */
    public static final String RULE_SUCHAUSDRUCK_DURCH_NICHT_CONTAINER = "suchausdruck-durch-nicht-namingcontainer";

    /**
     * Rule identifier: {@code <h:form>} without an {@code id} attribute (A-06, security/architecture
     * analysis 05.09.2026, card 1104). Two root pages had this — {@code entityverwaltung.xhtml} and
     * {@code debug.xhtml} — relying on JSF's auto-generated, layout-dependent id (e.g. {@code j_idt42}),
     * which no Ajax search expression or CSS selector can reference reliably.
     */
    public static final String RULE_FORM_OHNE_ID = "h-form-ohne-id";

    /** Opt-out marker as an inline comment on the same line as the reported tag. */
    public static final String EXEMPT_COMMENT = "jsf-view-ok";

    /** XML comment (also multi-line) — blanked out so that examples in comments do not count. */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Pattern METADATA_OPEN = Pattern.compile("<f:metadata\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFINE_OPEN = Pattern.compile("<ui:define\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM_OPEN = Pattern.compile("<h:form\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM_CLOSE = Pattern.compile("</h:form\\s*>", Pattern.CASE_INSENSITIVE);

    /** Full opening {@code <h:form ...>} tag (also multi-line, self-closing or not), attributes captured. */
    private static final Pattern FORM_TAG = Pattern.compile("<h:form\\b([^>]*)>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HAT_ID_ATTRIBUT = Pattern.compile("\\bid\\s*=\\s*\"", Pattern.CASE_INSENSITIVE);

    /**
     * Opening {@code <p:xxx ...>} tag (also multi-line) that carries an {@code onchange} attribute
     * with {@code submit()} resp. {@code this.form.submit()}.
     */
    private static final Pattern P_ONCHANGE_SUBMIT = Pattern.compile(
            "<p:[A-Za-z]+\\b[^>]*\\bonchange\\s*=\\s*\"\\s*(?:this\\.form\\.)?submit\\(\\)\\s*;?\\s*\"",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * Tags, die KEIN NamingContainer sind und deshalb in der Mitte eines Suchausdrucks nichts zu
     * suchen haben. Bewusst als Positivliste der haeufigen Irrtuemer gefuehrt: {@code p:dialog} ist
     * der Klassiker (es sieht aus wie ein Container, ist aber keiner).
     */
    private static final List<String> NICHT_NAMING_CONTAINER = List.of(
            "dialog", "panel", "fieldset", "outputPanel", "panelGrid", "panelGroup", "accordionPanel",
            "toolbar", "card", "divider", "fragment", "overlayPanel", "confirmDialog");

    /** Oeffnendes Tag mit id-Attribut — beliebiges Praefix, damit p:, h: und ui: gleich behandelt werden. */
    private static final Pattern TAG_MIT_ID = Pattern.compile(
            "<(?:p|h|ui):(\\w+)\\b[^>]*?\\bid=\"([^\"]+)\"", Pattern.DOTALL);

    /** {@code update="..."} / {@code process="..."} samt Inhalt. */
    private static final Pattern AJAX_ZIEL = Pattern.compile(
            "\\b(update|process)\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

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
        pruefeSuchausdruecke(file, original, content, violations);
        pruefeFormOhneId(file, original, content, violations);
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

    /**
     * Rule 4 — an Ajax search expression runs through the id of a component that is not a NamingContainer.
     *
     * <p>Observed on 31.08.2026 in guild: {@code <p:ajax update=":fm:dlg:qEvent"/>} where {@code dlg}
     * is a {@code <p:dialog>}. JSF throws {@code IllegalArgumentException: dlg} while <em>rendering</em>
     * ({@code UIComponentBase.findComponent}) — only NamingContainers may appear in the middle of an
     * expression. What the user sees: the dialog opens as an empty shell (header, no content) and the
     * modal overlay stays put, leaving the page dead. That was exactly Daniel's report on
     * {@code listen.xhtml}.
     *
     * <p>Only components declared in the same file are considered; an expression pointing at an id
     * that does not occur here may come from elsewhere and is not judged.
     */
    private static void pruefeSuchausdruecke(Path file, String original, String content, List<Violation> violations) {
        // Collect the ids of this file's non-NamingContainer components.
        java.util.Map<String, String> nichtContainer = new java.util.HashMap<>();
        Matcher tag = TAG_MIT_ID.matcher(content);
        while (tag.find()) {
            if (NICHT_NAMING_CONTAINER.contains(tag.group(1))) {
                nichtContainer.put(tag.group(2), tag.group(1));
            }
        }
        if (nichtContainer.isEmpty()) {
            return;
        }
        Matcher ziel = AJAX_ZIEL.matcher(content);
        while (ziel.find()) {
            for (String einzel : ziel.group(2).trim().split("[\\s,]+")) {
                if (!einzel.contains(":")) {
                    continue;
                }
                String[] stuecke = einzel.split(":");
                // Everything but the last segment must be a NamingContainer.
                for (int i = 0; i < stuecke.length - 1; i++) {
                    String tagName = nichtContainer.get(stuecke[i]);
                    if (tagName == null) {
                        continue;
                    }
                    int lineNo = lineNumberAt(original, ziel.start());
                    if (lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
                        continue;
                    }
                    violations.add(new Violation(file, lineNo, RULE_SUCHAUSDRUCK_DURCH_NICHT_CONTAINER,
                            ziel.group(1) + "=\"" + einzel + "\" runs through '" + stuecke[i]
                                    + "', which is a <" + tagName + "> — not a NamingContainer. JSF throws "
                                    + "IllegalArgumentException: " + stuecke[i] + " while rendering; the dialog "
                                    + "stays empty and the overlay blocks the page. Fix: drop the intermediate "
                                    + "segment (\":fm:" + stuecke[stuecke.length - 1] + "\"), or exempt it with <!-- "
                                    + EXEMPT_COMMENT + " --> on the same line."));
                }
            }
        }
    }

    /**
     * Rule 5 — {@code <h:form>} without an {@code id} attribute (A-06). Only the presence of an id is
     * checked, not its value: which naming convention applies (plain {@code "fm"} on a page with a
     * single form, an {@code "fm"}-prefixed name per form on a page with several) is a house-style
     * question decided per page, not something the linter can verify structurally from a single file.
     */
    private static void pruefeFormOhneId(Path file, String original, String content, List<Violation> violations) {
        Matcher tag = FORM_TAG.matcher(content);
        while (tag.find()) {
            if (HAT_ID_ATTRIBUT.matcher(tag.group(1)).find()) {
                continue;
            }
            int lineNo = lineNumberAt(original, tag.start());
            if (lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
                continue;
            }
            violations.add(new Violation(file, lineNo, RULE_FORM_OHNE_ID,
                    "<h:form> ohne id-Attribut: JSF vergibt sonst eine instabile, layoutabhaengige Id "
                            + "(z. B. j_idt42), die kein Ajax-Suchausdruck und kein CSS-Selektor verlaesslich "
                            + "referenzieren kann. Fix: id setzen (id=\"fm\", wenn die Seite genau ein Formular "
                            + "hat; sonst je Formular ein sprechender, mit 'fm' beginnender Name), oder mit <!-- "
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
