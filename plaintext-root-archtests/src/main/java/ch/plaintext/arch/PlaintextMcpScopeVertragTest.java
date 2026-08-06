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
 * Karte 546 — <b>die Leitplanke: jedes MCP-Werkzeug muss eine nachweisbare Scope-Schranke haben.</b>
 *
 * <p><b>Wogegen sie schützt.</b> Bis zum 06.08.2026 hatte nur schuetu Scope-Prüfungen auf seinen
 * MCP-Werkzeugen; in app, guild und root durfte ein Token mit Scope {@code READ} über MCP
 * <em>schreiben</em> — der Scope war dort eine Beschriftung ohne Wirkung. Beim Nachrüsten von rund
 * 150 Werkzeugen ist die Gefahr nicht der Umfang, sondern das eine übersehene schreibende Werkzeug:
 * Es fällt nicht im Review auf, sondern im Betrieb, und dann als offene Schreibmöglichkeit.
 *
 * <p><b>Warum das Kriterium nicht „hat {@code @PreAuthorize}" lautet.</b> Diese Fassung urteilt
 * nachweislich falsch, wo eine Schranke <em>strenger</em> als eine Annotation ist:
 * {@code ApiTokenMcpTools} und {@code SecretsMcpTools} prüfen im Methodenrumpf und verlangen
 * {@code SCOPE_ADMIN} <b>und</b> die Rolle {@code ADMIN}/{@code ROOT}. Wer diesen „Verstoss behebt",
 * indem er auf {@code @PreAuthorize} umstellt, <b>verliert die Rollenprüfung</b>. Solche Stellen
 * gehören darum in die Ausnahmeliste — jede mit Begründung, und keine ohne.
 *
 * <p><b>Warum jedes Werkzeug annotiert wird, auch lesende.</b> Die Einstufung lesend/schreibend
 * über den Werkzeugnamen trägt nicht: {@code sync_mailbox}, {@code reprocess_email} und
 * {@code prepare_email} klingen neutral und schreiben, {@code vorschau_rechnungslauf} klingt
 * schreibend und liest. Trägt <em>jedes</em> Werkzeug eine Schranke (lesende mit
 * {@code SCOPE_READ}), muss niemand raten, und eine fehlende Annotation sagt etwas. So hat schuetu
 * es gemacht: 27 von 27, davon 7 mit {@code SCOPE_READ}.
 *
 * <p><b>Warum es eine Untergrenze gibt.</b> Ein Vertragstest der Form „alle gefundenen Werkzeuge
 * sind in Ordnung" ist auch dann grün, wenn gar keine mehr gefunden werden — und das ist kein
 * Gedankenspiel. Gemessen am proxied Bean von {@code ListenMcpTools} (Karte 546, guild#113):
 *
 * <pre>
 * proxy-bewusster Weg (der benutzte)  = 9 Werkzeuge
 * naiver Weg (Bibliotheks-Basisklasse) = 0 Werkzeuge — die CGLIB-Subklasse erbt @McpTool nicht
 * </pre>
 *
 * Ein zu niedriger Wert sieht aus wie Fortschritt. Dieselbe Falle hat der Analyst beim Zählen
 * erlebt (schuetu meldete 20 statt 27, weil sechs Werkzeuge kein {@code name}-Attribut haben —
 * ausgerechnet die schreibenden Live-Kommandos). Deshalb führt jeder Consumer seine Zahl in der
 * Vertragsdatei, und der Test wird rot, wenn sie unterschritten wird.
 *
 * <p><b>Warum hier und nicht je Repo.</b> {@code McpToolParamVertragTest} existiert bereits als
 * zwei nicht identische Kopien (guild und app). Eine dritte und vierte wären derselbe Weg, auf dem
 * die nächste Fundstelle unbemerkt bleibt — „die sechste Fundstelle wäre die, in der die Kopie
 * fehlt" (Karte 502). Dieser Test liegt darum wie die übrigen Regeln dieses Moduls in
 * {@code src/main/java} und läuft im Consumer über Surefire {@code <dependenciesToScan>} gegen
 * dessen Klassen — <b>einschliesslich der konsumierten Jars</b>. Das ist beabsichtigt: guild liefert
 * rund 38 Werkzeuge aus app-Modulen aus, und wer sie ausliefert, verantwortet sie.
 *
 * <p><b>Was dieser Test NICHT leisten kann.</b> Er sieht Code, nicht Laufzeit. Ob eine vorhandene
 * Annotation auch <em>wirkt</em>, hängt an {@code @EnableMethodSecurity} und daran, dass das Bean
 * proxied wird — beides ist Laufzeit. Dafür gibt es {@code MethodSecurityEnabledTest} (je
 * Anwendung) und {@code McpScopeGateWirktTest} (guild, misst am echten Werkzeug über den echten
 * Registrierungsweg). Ohne die beiden wäre ein grünes Ergebnis hier eine Aussage über
 * <em>Beschriftungen</em>, nicht über Schranken.
 *
 * <h2>Die Vertragsdatei</h2>
 *
 * Jeder Consumer legt {@code src/test/resources/mcp-scope-vertrag.properties} an:
 *
 * <pre>
 * mindestens.werkzeuge = 4
 * ausnahme.ch.plaintext.apitoken.ApiTokenMcpTools#createApiToken = Rumpfprüfung, strenger: \
 *     SCOPE_ADMIN UND Rolle ADMIN/ROOT
 * </pre>
 *
 * Fehlt die Datei und findet der Scan <b>keine</b> Werkzeuge, ist alles in Ordnung — die Anwendung
 * hat schlicht keine. Fehlt sie, obwohl Werkzeuge da sind, wird der Test rot: Dann ist die Zahl
 * nirgends festgehalten und ein späterer Ausfall bliebe unsichtbar.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextMcpScopeVertragTest {

    private static final String MCP_TOOL = "org.springaicommunity.mcp.annotation.McpTool";
    private static final String PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";

    /** Von der Vertragsdatei erwarteter Ort im Test-Classpath des Consumers. */
    private static final String VERTRAG = "mcp-scope-vertrag.properties";

    private static final String SCHLUESSEL_MINDESTENS = "mindestens.werkzeuge";
    private static final String PRAEFIX_AUSNAHME = "ausnahme.";

    /**
     * Nur eine Schranke, die einen Scope nennt, zählt. {@code @PreAuthorize("isAuthenticated()")}
     * wäre eine Schranke, aber keine <em>Scope</em>-Schranke — und genau darum geht es hier: Ein
     * gültiges Token ist immer authentifiziert (jedes bekommt {@code SCOPE_READ}), der Scope ist
     * das Einzige, was READ von WRITE unterscheidet.
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
     * Eine Ausnahmeliste, die niemand aufräumt, versteinert: Sie deckt irgendwann Stellen ab, die es
     * nicht mehr gibt, und verdeckt damit, dass sie kürzer sein könnte. Dasselbe Muster führt
     * {@code McpToolParamVertragTest} als {@code keineUeberfluessigenAltlasten}.
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
     * Die Untergrenze. Ein Scanner, der nichts findet, meldet sonst „alles in Ordnung" — der
     * schlechtestmögliche Zustand mit grünem Ergebnis.
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

    /** Ein am Klassenpfad gefundenes MCP-Werkzeug samt seiner Schranke (oder deren Fehlen). */
    private record Werkzeug(JavaMethod methode) {

        /** {@code paket.Klasse#methode} — ohne Parameter, das genügt als Kennung und liest sich. */
        String kennung() {
            return methode.getOwner().getName() + "#" + methode.getName();
        }

        /** Der nach aussen sichtbare Werkzeugname; ohne {@code name}-Attribut leitet MCP ihn ab. */
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

        /** Aus welchem Jar/Modul der Fund stammt — sonst sucht der Leser ihn im falschen Repo. */
        private String herkunft() {
            return methode.getOwner().getSource()
                    .map(s -> s.getUri().toString())
                    .map(u -> u.substring(u.lastIndexOf('/') + 1))
                    .map(u -> u.contains(".jar") ? u.substring(0, u.indexOf(".jar") + 4) : "")
                    .orElse("");
        }
    }

    // ------------------------------------------------------------------ Vertragsdatei

    /** Die Angaben, die der jeweilige Consumer über seine MCP-Werkzeuge festhält. */
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
