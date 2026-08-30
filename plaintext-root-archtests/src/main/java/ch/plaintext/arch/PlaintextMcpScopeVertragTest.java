/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Card 546 — <b>the guardrail: every MCP tool must have a demonstrable scope barrier.</b>
 *
 * <p><b>What it protects against.</b> Until 06.08.2026 only schuetu had scope checks on its
 * MCP tools; in app, guild and root a token with scope {@code READ} was allowed to
 * <em>write</em> via MCP — the scope was a label without effect there. When retrofitting some
 * 150 tools the danger is not the sheer number, but the one overlooked writing tool:
 * it does not show up in review but in production, and then as an open write capability.
 *
 * <p><b>Why the criterion is not "has {@code @PreAuthorize}".</b> That version demonstrably judges
 * wrongly wherever a barrier is <em>stricter</em> than an annotation:
 * {@code ApiTokenMcpTools} and {@code SecretsMcpTools} check inside the method body and require
 * {@code SCOPE_ADMIN} <b>and</b> the role {@code ADMIN}/{@code ROOT}. Whoever "fixes this violation"
 * by switching to {@code @PreAuthorize} <b>loses the role check</b>. Such places therefore
 * belong in the exception list — each one with a justification, and none without.
 *
 * <p><b>Why every tool is annotated, reading ones included.</b> Classifying read/write
 * by the tool name does not hold: {@code sync_mailbox}, {@code reprocess_email} and
 * {@code prepare_email} sound neutral and write, {@code vorschau_rechnungslauf} sounds
 * like writing and reads. If <em>every</em> tool carries a barrier (reading ones with
 * {@code SCOPE_READ}), nobody has to guess, and a missing annotation says something. That is how
 * schuetu did it: 27 out of 27, 7 of them with {@code SCOPE_READ}.
 *
 * <p><b>Why there is a lower bound.</b> A contract test of the form "all tools found
 * are in order" is green even when none are found any more — and that is no
 * thought experiment. Measured on the proxied bean of {@code ListenMcpTools} (card 546, guild#113):
 *
 * <pre>
 * proxy-aware way (the one used)       = 9 tools
 * naive way (library base class)       = 0 tools — the CGLIB subclass does not inherit @McpTool
 * </pre>
 *
 * A value that is too low looks like progress. The analyst ran into the same trap while counting
 * (schuetu reported 20 instead of 27, because six tools have no {@code name} attribute —
 * of all things the writing live commands). That is why every consumer keeps its number in the
 * contract file, and the test turns red when the number is undercut.
 *
 * <p><b>Why here and not per repository.</b> {@code McpToolParamVertragTest} already exists as
 * two non-identical copies (guild and app). A third and a fourth would be the same road on which
 * the next occurrence goes unnoticed — "the sixth occurrence would be the one where the copy
 * is missing" (card 502). This test therefore lives, like the other rules of this module, in
 * {@code src/main/java} and runs in the consumer via Surefire {@code <dependenciesToScan>} against
 * that consumer's classes — <b>including the consumed jars</b>. That is intentional: guild ships
 * some 38 tools from app modules, and whoever ships them is responsible for them.
 *
 * <p><b>What this test can NOT do.</b> It sees code, not runtime. Whether an existing
 * annotation actually <em>takes effect</em> depends on {@code @EnableMethodSecurity} and on the bean
 * being proxied — both are runtime. For that there are {@code MethodSecurityEnabledTest} (per
 * application) and {@code McpScopeGateWirktTest} (guild, measured on the real tool through the real
 * registration path). Without those two a green result here would be a statement about
 * <em>labels</em>, not about barriers.
 *
 * <h2>The contract file</h2>
 *
 * Every consumer creates {@code src/test/resources/mcp-scope-vertrag.properties}:
 *
 * <pre>
 * mindestens.werkzeuge = 4
 * ausnahme.ch.plaintext.apitoken.ApiTokenMcpTools#createApiToken = body check, stricter: \
 *     SCOPE_ADMIN AND role ADMIN/ROOT
 * </pre>
 *
 * If the file is missing and the scan finds <b>no</b> tools, everything is in order — the application
 * simply has none. If it is missing although tools are there, the test turns red: the number is then
 * recorded nowhere and a later drop-out would stay invisible.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextMcpScopeVertragTest {

    private static final String MCP_TOOL = "org.springaicommunity.mcp.annotation.McpTool";
    private static final String PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";

    /** Location expected of the contract file in the consumer's test classpath. */
    private static final String VERTRAG = "mcp-scope-vertrag.properties";

    private static final String SCHLUESSEL_MINDESTENS = "mindestens.werkzeuge";
    private static final String PRAEFIX_AUSNAHME = "ausnahme.";

    /**
     * Only a barrier that names a scope counts. {@code @PreAuthorize("isAuthenticated()")}
     * would be a barrier, but no <em>scope</em> barrier — and that is exactly the point here: a
     * valid token is always authenticated (every one gets {@code SCOPE_READ}), the scope is
     * the only thing that separates READ from WRITE.
     */
    private static final String SCOPE_MARKER = "SCOPE_";

    // ------------------------------------------------------------------ Tests

    @Test
    void jedesMcpWerkzeugHatEineNachweisbareScopeSchranke() {
        Vertrag vertrag = Vertrag.laden();
        List<Werkzeug> werkzeuge = werkzeugeAmClasspath();

        List<String> verstoesse = werkzeuge.stream()
                .filter(w -> !w.hatScopeSchranke())
                .filter(w -> !vertrag.istAusgenommen(w.kennung()))
                .map(Werkzeug::meldung)
                .sorted()
                .toList();

        if (!verstoesse.isEmpty()) {
            fail("MCP-Werkzeuge ohne Scope-Schranke: " + verstoesse.size() + " von " + werkzeuge.size()
                    + ".\nEin Token mit Scope READ kann sie aufrufen — bei schreibenden Werkzeugen ist das"
                    + " eine offene Schreibmoeglichkeit.\n\nZwei gueltige Antworten je Fund:\n"
                    + "  1. @PreAuthorize(\"hasAuthority('SCOPE_WRITE')\") bzw. 'SCOPE_READ' setzen.\n"
                    + "  2. Ist die Stelle bereits im Rumpf geprueft (und ggf. strenger), als Ausnahme in "
                    + VERTRAG + " eintragen — mit Begruendung.\n\n"
                    + String.join("\n", verstoesse));
        }
    }

    /**
     * An exception list that nobody tidies up petrifies: at some point it covers places that no
     * longer exist and thereby hides that it could be shorter. {@code McpToolParamVertragTest}
     * keeps the same pattern as {@code keineUeberfluessigenAltlasten}.
     */
    @Test
    void keineUeberfluessigenAusnahmen() {
        Vertrag vertrag = Vertrag.laden();
        List<Werkzeug> werkzeuge = werkzeugeAmClasspath();
        if (werkzeuge.isEmpty() && vertrag.ausnahmen.isEmpty()) {
            return;
        }

        List<String> ueberfluessig = vertrag.ausnahmen.keySet().stream()
                .filter(k -> werkzeuge.stream().noneMatch(w -> w.kennung().equals(k) && !w.hatScopeSchranke()))
                .sorted()
                .toList();

        if (!ueberfluessig.isEmpty()) {
            fail("Ausnahmen in " + VERTRAG + ", die keinen ungeschuetzten Werkzeug-Fund mehr abdecken"
                    + " — Eintrag streichen:\n  " + String.join("\n  ", ueberfluessig));
        }
    }

    /**
     * The lower bound. A scanner that finds nothing otherwise reports "everything in order" — the
     * worst possible state with a green result.
     */
    @Test
    void derScanFindetMindestensDieFestgehalteneZahlWerkzeuge() {
        List<Werkzeug> werkzeuge = werkzeugeAmClasspath();
        Vertrag vertrag = Vertrag.laden();

        if (!vertrag.vorhanden) {
            assertTrue(werkzeuge.isEmpty(),
                    "Es sind " + werkzeuge.size() + " MCP-Werkzeuge am Klassenpfad, aber es gibt keine "
                    + VERTRAG + " im Test-Classpath. Ohne festgehaltene Zahl bliebe es unsichtbar, wenn "
                    + "Werkzeuge kuenftig gar nicht mehr gefunden werden. Datei anlegen mit:\n"
                    + "  " + SCHLUESSEL_MINDESTENS + " = " + werkzeuge.size());
            return;
        }

        assertTrue(werkzeuge.size() >= vertrag.mindestens,
                "Der Scan findet nur " + werkzeuge.size() + " MCP-Werkzeuge, festgehalten sind "
                + vertrag.mindestens + ". Ein zu niedriger Wert sieht aus wie Fortschritt und ist "
                + "meist das Gegenteil: Werkzeuge, die der Scanner nicht mehr sieht, pruefte er auch "
                + "nicht mehr. Gefunden: "
                + werkzeuge.stream().map(Werkzeug::name).sorted().collect(Collectors.joining(", ")));
    }

    // ------------------------------------------------------------------ Scan

    private static List<Werkzeug> werkzeugeAmClasspath() {
        JavaClasses klassen = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("ch.plaintext");

        List<Werkzeug> gefunden = new ArrayList<>();
        for (JavaMethod methode : klassen.stream().flatMap(k -> k.getMethods().stream()).toList()) {
            if (methode.isAnnotatedWith(MCP_TOOL)) {
                gefunden.add(new Werkzeug(methode));
            }
        }
        return gefunden;
    }

    /** An MCP tool found on the classpath together with its barrier (or the absence of one). */
    private record Werkzeug(JavaMethod methode) {

        /** {@code paket.Klasse#methode} — without parameters, that is enough as an identifier and reads well. */
        String kennung() {
            return methode.getOwner().getName() + "#" + methode.getName();
        }

        /** The externally visible tool name; without a {@code name} attribute MCP derives it. */
        String name() {
            Object wert = methode.getAnnotationOfType(MCP_TOOL).get("name").orElse("");
            String benannt = String.valueOf(wert);
            return benannt.isBlank() ? methode.getName() : benannt;
        }

        boolean hatScopeSchranke() {
            if (!methode.isAnnotatedWith(PRE_AUTHORIZE)) {
                return false;
            }
            JavaAnnotation<?> pre = methode.getAnnotationOfType(PRE_AUTHORIZE);
            return String.valueOf(pre.get("value").orElse("")).contains(SCOPE_MARKER);
        }

        String meldung() {
            String quelle = herkunft();
            return "  " + name() + "  (" + kennung() + (quelle.isEmpty() ? "" : ", aus " + quelle) + ")";
        }

        /** Which jar/module the finding comes from — otherwise the reader looks for it in the wrong repository. */
        private String herkunft() {
            return methode.getOwner().getSource()
                    .map(s -> s.getUri().toString())
                    .map(u -> u.substring(u.lastIndexOf('/') + 1))
                    .map(u -> u.contains(".jar") ? u.substring(0, u.indexOf(".jar") + 4) : "")
                    .orElse("");
        }
    }

    // ------------------------------------------------------------------ Contract file

    /** The details that the respective consumer records about its MCP tools. */
    private record Vertrag(boolean vorhanden, int mindestens, TreeMap<String, String> ausnahmen) {

        static Vertrag laden() {
            Properties p = new Properties();
            try (InputStream in = PlaintextMcpScopeVertragTest.class.getClassLoader()
                    .getResourceAsStream(VERTRAG)) {
                if (in == null) {
                    return new Vertrag(false, 0, new TreeMap<>());
                }
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException(VERTRAG + " ist nicht lesbar", e);
            }

            TreeMap<String, String> ausnahmen = new TreeMap<>();
            for (String schluessel : p.stringPropertyNames()) {
                if (schluessel.startsWith(PRAEFIX_AUSNAHME)) {
                    String begruendung = p.getProperty(schluessel).trim();
                    if (begruendung.isEmpty()) {
                        fail("Ausnahme ohne Begruendung in " + VERTRAG + ": " + schluessel
                                + " — eine Ausnahme ohne Grund ist ein Loch mit Deckel.");
                    }
                    ausnahmen.put(schluessel.substring(PRAEFIX_AUSNAHME.length()).trim(), begruendung);
                }
            }
            return new Vertrag(true, Integer.parseInt(p.getProperty(SCHLUESSEL_MINDESTENS, "0").trim()),
                    ausnahmen);
        }

        boolean istAusgenommen(String kennung) {
            return ausnahmen.containsKey(kennung);
        }
    }
}
