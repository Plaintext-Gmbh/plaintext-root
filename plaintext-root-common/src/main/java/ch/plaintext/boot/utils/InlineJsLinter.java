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
 * Reusable linter against inline JavaScript in facelets — the precondition for the
 * Content-Security-Policy to get by without {@code script-src 'unsafe-inline'}.
 *
 * <p><b>Why this is a rule and not a matter of taste.</b> As long as the policy carries
 * {@code 'unsafe-inline'}, every injected {@code <script>} runs — the CSP is then not XSS
 * protection but decoration. The browser does not distinguish between "ours" and "foreign";
 * it only distinguishes between "loaded from a file" and "stood in the document".
 * A single remaining inline block anywhere in the reactor is enough to make the switch
 * unflippable.
 *
 * <p><b>Rule 1 — {@link #RULE_INLINE_SCRIPT}:</b> a {@code <script>} WITH a body and WITHOUT
 * {@code src}, likewise an {@code <h:outputScript>} with a body (which renders exactly such an
 * inline block). Fix: move the body into a {@code .js} file below
 * {@code src/main/resources/META-INF/resources/<library>/js/} and include it via
 * {@code <h:outputScript library="…" name="js/….js"/>}. Server-side values then belong on an
 * element as a {@code data-} attribute, NOT as EL inside the script body.
 *
 * <p><b>Rule 2 — {@link #RULE_INLINE_HANDLER}:</b> an {@code on…} attribute on a
 * NON-PrimeFaces tag ({@code onclick} on {@code <a>}, {@code onchange} on {@code <select>} …).
 * Fix: {@code addEventListener} in the externalized file, wired up through an ID or a
 * {@code data-} attribute.
 *
 * <p><b>What MAY stay and is therefore not reported:</b> {@code on…} on a
 * {@code p:} component. Those are largely not HTML attributes at all but
 * PrimeFaces events of the Ajax lifecycle ({@code oncomplete}, {@code onstart},
 * {@code onerror} on {@code p:commandButton}); and where PrimeFaces really would produce an
 * HTML handler ({@code onclick} on {@code p:commandButton}), the CSP mode
 * ({@code joinfaces.primefaces.csp=true}) pulls it out of the markup itself and registers it
 * through {@code PrimeFaces.csp}. These cases therefore do not stand in the way of the strict
 * header.
 *
 * <p><b>What is blanked out before the evaluation</b> (set to spaces, so that line numbers
 * stay stable): XML comments — otherwise an example inside a comment counts as a violation,
 * and exactly such examples exist in the code base (e.g. {@code <img src=x onerror=…>} as an
 * illustration of a security hole) — as well as the bodies of {@code <script>} and
 * {@code <style>}, so that JavaScript properties such as {@code xhr.onreadystatechange = …} do
 * not pass as an attribute.
 *
 * <p><b>Exceptions:</b> {@code <!-- inline-js-ok -->} on the same line exempts exactly that hit
 * with a rationale (the same pattern as {@code jsf-view-ok} / {@code mobile-ok}). Whole files
 * are exempted by the shared test through the reactor's allowlist (see
 * {@code PlaintextInlineJsVertragTest} in plaintext-root-archtests).
 *
 * <p><b>Reuse across module boundaries:</b> like {@link JsfViewLinter} and
 * {@link MobileFormLinter} the class lives in {@code plaintext-root-common} (JDK only) and is
 * transitively on the test classpath of all dependent projects.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class InlineJsLinter {

    /** Rule identifier: {@code <script>}/{@code <h:outputScript>} with a body instead of an externalized file. */
    public static final String RULE_INLINE_SCRIPT = "inline-script-block";

    /** Rule identifier: {@code on…} attribute on a non-PrimeFaces tag. */
    public static final String RULE_INLINE_HANDLER = "inline-event-handler";

    /** Opt-out marker as an inline comment on the same line as the reported hit. */
    public static final String EXEMPT_COMMENT = "inline-js-ok";

    /** XML comment (also multi-line). */
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** {@code <script …>…</script>} resp. {@code <h:outputScript …>…</h:outputScript>} including the body. */
    private static final Pattern SCRIPT_BLOCK = Pattern.compile(
            "<(script|h:outputScript)\\b([^>]*)>(.*?)</\\1\\s*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** {@code <style …>…</style>} including the body — only for blanking out. */
    private static final Pattern STYLE_BLOCK = Pattern.compile(
            "<style\\b[^>]*>(.*?)</style\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** {@code src="…"} resp. {@code name="…"} in the attribute part of a script tag. */
    private static final Pattern SRC_ODER_NAME = Pattern.compile("\\b(src|name)\\s*=", Pattern.CASE_INSENSITIVE);

    /**
     * An opening tag: name, then the attribute part. Quotation marks are read as a unit, so that
     * a {@code >} INSIDE an attribute value does not end the tag prematurely (the code base
     * contains e.g. {@code style="…"} with a {@code >} in an EL expression).
     */
    private static final Pattern TAG = Pattern.compile(
            "<([A-Za-z][A-Za-z0-9:._-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*+)/?>", Pattern.DOTALL);

    /**
     * {@code on…=} in the attribute part of a tag. The preceding space is mandatory, otherwise the
     * rule also fires on attribute names that happen to end in {@code on…} —
     * {@code data-pt-only="…"} for instance contains {@code only=}.
     */
    private static final Pattern ON_ATTRIBUT = Pattern.compile("(?<=\\s)on([A-Za-z]+)\\s*=");

    private InlineJsLinter() {
    }

    /**
     * A single violation: file, line (1-based), rule identifier and a human-readable
     * description.
     *
     * @param file    affected XHTML file
     * @param line    1-based line number of the reported tag
     * @param rule    {@link #RULE_INLINE_SCRIPT} or {@link #RULE_INLINE_HANDLER}
     * @param message description including the fix
     */
    public record Violation(Path file, int line, String rule, String message) {
        @Override
        public String toString() {
            return file + ":" + line + " [" + rule + "] -> " + message;
        }
    }

    /**
     * Scans all {@code *.xhtml} below {@code resourcesRoot} recursively.
     *
     * @param resourcesRoot root directory (e.g. {@code <modul>/src/main/resources}); if it does
     *                      not exist, an empty list is returned
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
        String ohneKommentare = blankSpans(original, COMMENT);
        pruefeSkriptbloecke(file, original, ohneKommentare, violations);

        // For the attribute rule additionally blank out the BODIES of <script>/<style> (only the
        // bodies, not the opening tags — an on…= on the <script> tag itself would otherwise stay
        // undetected). Otherwise a JavaScript property such as xhr.onreadystatechange = …
        // counts as an attribute.
        String nurMarkup = blankGroup(blankGroup(ohneKommentare, SCRIPT_BLOCK, 3), STYLE_BLOCK, 1);
        pruefeHandlerAttribute(file, original, nurMarkup, violations);
    }

    /** Rule 1 — {@code <script>}/{@code <h:outputScript>} with a body and without {@code src}/{@code name}. */
    private static void pruefeSkriptbloecke(Path file, String original, String content,
                                            List<Violation> violations) {
        Matcher m = SCRIPT_BLOCK.matcher(content);
        while (m.find()) {
            if (SRC_ODER_NAME.matcher(m.group(2)).find() || m.group(3).isBlank()) {
                continue; // externalized file resp. empty body
            }
            int lineNo = lineNumberAt(original, m.start());
            if (lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
                continue;
            }
            violations.add(new Violation(file, lineNo, RULE_INLINE_SCRIPT,
                    "<" + m.group(1) + "> mit Rumpf: solange ein Inline-Block existiert, muss die "
                            + "Content-Security-Policy script-src 'unsafe-inline' fuehren — dann laeuft auch "
                            + "jedes eingeschleuste <script>. Fix: Rumpf nach "
                            + "src/main/resources/META-INF/resources/<library>/js/ auslagern und per "
                            + "<h:outputScript library=\"…\" name=\"js/….js\"/> einbinden; serverseitige Werte "
                            + "als data-Attribut an ein Element statt als EL im Skriptkoerper. Oder mit <!-- "
                            + EXEMPT_COMMENT + " --> in derselben Zeile begruendet ausnehmen."));
        }
    }

    /** Rule 2 — {@code on…} attribute on a non-{@code p:} tag. */
    private static void pruefeHandlerAttribute(Path file, String original, String content,
                                               List<Violation> violations) {
        Matcher tag = TAG.matcher(content);
        while (tag.find()) {
            String tagName = tag.group(1);
            if (tagName.startsWith("p:") || tagName.startsWith("P:")) {
                continue; // PrimeFaces attribute resp. pulled out by the CSP mode itself
            }
            Matcher attribut = ON_ATTRIBUT.matcher(tag.group(2));
            while (attribut.find()) {
                int lineNo = lineNumberAt(original, tag.start(2) + attribut.start());
                if (lineContent(original, lineNo).contains(EXEMPT_COMMENT)) {
                    continue;
                }
                violations.add(new Violation(file, lineNo, RULE_INLINE_HANDLER,
                        "on" + attribut.group(1) + "= an <" + tagName + ">: ein HTML-Ereignisattribut ist "
                                + "fuer den Browser dasselbe wie ein Inline-<script> und zwingt die "
                                + "Content-Security-Policy zu script-src 'unsafe-inline'. Fix: "
                                + "addEventListener in der ausgelagerten .js-Datei, angebunden ueber eine ID "
                                + "oder ein data-Attribut. Oder mit <!-- " + EXEMPT_COMMENT + " --> in "
                                + "derselben Zeile begruendet ausnehmen."));
            }
        }
    }

    /** Replaces every {@code pattern} occurrence with the same number of spaces; newlines are preserved. */
    private static String blankSpans(String content, Pattern pattern) {
        return blankGroup(content, pattern, 0);
    }

    /** Like {@link #blankSpans}, but only the given group of the match. */
    private static String blankGroup(String content, Pattern pattern, int gruppe) {
        Matcher m = pattern.matcher(content);
        StringBuilder out = new StringBuilder(content);
        while (m.find()) {
            for (int i = m.start(gruppe); i < m.end(gruppe); i++) {
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
