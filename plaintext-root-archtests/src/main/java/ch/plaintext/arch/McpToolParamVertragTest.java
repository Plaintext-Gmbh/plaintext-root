/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 469: Die Beschreibung eines MCP-Parameters und sein JSON-Schema muessen dasselbe sagen.
 *
 * <p><b>Warum es diesen Test gibt.</b> {@code @McpToolParam} hat {@code required()} mit Default
 * {@code true}. Jeder Parameter ohne ausdrueckliches {@code required = false} ist damit ein
 * Pflichtfeld — auch dann, wenn seine Beschreibung dem Aufrufer verspricht, er duerfe ihn
 * weglassen. Wer dieser Beschreibung folgt, kann sie nicht befolgen: Weglassen laesst das Schema
 * nicht zu. Es bleiben nur schlechte Ersatzwege — Leerstring (loescht das Feld) oder die
 * Zeichenkette {@code "null"} (wird als Wert gespeichert). Kontakt 16 wurde am 02.08.2026 so zu
 * „null null" mit ueberschriebener E-Mail (Karte 467), Event 3812 bekam {@code "null"} als RRULE
 * (Karte 390). In app waren es 73 Parameter in 44 Werkzeugen, in guild 36 in 21.
 *
 * <p><b>Was der Test NICHT verlangt.</b> Er fordert nicht, dass Parameter optional werden. Wer
 * einen Verstoss sieht, hat immer zwei gueltige Antworten: das Feld ist weglassbar →
 * {@code required = false}; das Feld ist Pflicht → die Beschreibung berichtigen. Der lehrreichste
 * Fund stand in guilds {@code update_konto}: nicht das Schema war falsch, sondern der Satz daneben.
 *
 * <p><b>Karte 1298: eine Fassung statt drei — die staerkste.</b> Bis zum 22.09.2026 lag dieser Test
 * als Kopie in app (199 Zeilen), schuetu (193) und guild (372). Die Kopien waren
 * auseinandergelaufen, und zwar genau an der Stelle, die guild am 04.08.2026 teuer gelernt hatte:
 * <ul>
 *   <li><b>Zuschnitt.</b> app scannte nur die eigenen Module ({@code NurAppCode}), schuetu nur
 *       {@code ch.plaintext.schuetu}. guild scannte zuerst ebenso eng und war gruen, waehrend
 *       {@code create_contact} aus dem konsumierten plaintext-z-kontakte alle zwoelf Felder als
 *       Pflicht fuehrte — 45 solcher Parameter im ausgelieferten Katalog. Diese Fassung scannt wie
 *       guild den Bereich des {@code @ComponentScan}: alles unter {@code ch.plaintext}, auch
 *       konsumierte Jars. Wer Code ausliefert, verantwortet ihn; jede Meldung nennt das Artefakt,
 *       aus dem der Fund stammt, damit er im Herkunftsprojekt behoben wird.</li>
 *   <li><b>Altlasten.</b> Nur guild hatte eine benannte Ausnahmeliste und prueft, dass kein
 *       Eintrag ueberfluessig geworden ist. Sie steht jetzt je Consumer in
 *       {@code mcp-scope-vertrag.properties} (Schluessel {@code param.altlast.<werkzeug>.<parameter>}),
 *       neben den Ausnahmen des Scope-Vertrags.</li>
 *   <li><b>Untergrenze.</b> Gilt {@code mindestens.werkzeuge} aus derselben Datei — derselbe Scan
 *       wie {@link PlaintextMcpScopeVertragTest}, also dieselbe Zahl. guilds getrennte Schranke fuer
 *       eigene und konsumierte Werkzeuge ist optional ({@code param.mindestens.eigene},
 *       {@code param.mindestens.konsumierte}, {@code param.eigenes.paket}).</li>
 * </ul>
 *
 * <p>Gelesen wird per Klassenname und Reflexion: dieses Modul haengt nicht an spring-ai.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class McpToolParamVertragTest {

    static final String MCP_TOOL = "org.springframework.ai.mcp.annotation.McpTool";
    static final String MCP_TOOL_PARAM = "org.springframework.ai.mcp.annotation.McpToolParam";

    /** Vertragsdatei im Test-Classpath des Consumers — dieselbe wie fuer den Scope-Vertrag. */
    static final String VERTRAG = "mcp-scope-vertrag.properties";

    /**
     * Formulierungen, mit denen eine Beschreibung dem Aufrufer das Weglassen zusagt — an den
     * Wendungen entlang gebaut, die im Bestand tatsaechlich vorkamen ({@code "IMAP port, or null
     * for 993"}, {@code "New title; omit to keep"}, {@code "Description (optional)"},
     * {@code "Date yyyy-MM-dd (empty = planned)"}). <b>Zweisprachig</b>, seit die englische Fassung
     * elf deutschsprachige Zusagen uebersah; {@code optional\w*} wegen der Flexion („Optionale
     * Bemerkung"), {@code null\s*\/\s*leer} wegen des Schraegstrichs.
     */
    static final Pattern VERSPRICHT_WEGLASSBAR = Pattern.compile(
            // englisch
            "\\bor empty\\b|\\boptional\\b|\\bor null\\b|leave unchanged|\\bomit\\b|\\bif none\\b"
                    + "|\\bempty (?:to|for|if)\\b|\\bnull to\\b|\\bor blank\\b|\\bnull\\s*=|\\bempty\\s*="
                    + "|\\bdefaults? to\\b"
                    // deutsch — Flexion mitgedacht: "optionale", "optionaler", "optionales"
                    + "|\\boptional\\w*\\b|oder null|oder leer|null/leer|leer/null|leer lassen"
                    + "|weglassen|unver(?:ä|ae)ndert|kann leer|\\bleer\\s*=|\\bnull\\s*/\\s*leer"
                    + "|wenn nicht gesetzt|falls nicht gesetzt|standardm(?:ä|ae)ssig",
            Pattern.CASE_INSENSITIVE);

    /** Ein Fund: wo er steht, was er verspricht und aus welchem Artefakt er kommt. */
    record Verstoss(String werkzeug, String parameter, String beschreibung, String klasse, String herkunft) {
        @Override
        public String toString() {
            return "  %s.%s  (%s — %s)%n      verspricht: \"%s\"".formatted(
                    werkzeug, parameter, klasse, herkunft, beschreibung);
        }

        String schluessel() {
            return werkzeug + "." + parameter;
        }
    }

    @Test
    @DisplayName("Kein Parameter verspricht, weglassbar zu sein, und ist doch Pflicht")
    void beschreibungUndSchemaSagenDasselbe() {
        Vertrag vertrag = Vertrag.laden();
        List<Verstoss> verstoesse = verstoesse(alleWerkzeuge()).stream()
                .filter(v -> !vertrag.altlasten().containsKey(v.schluessel()))
                .toList();
        assertTrue(verstoesse.isEmpty(), () -> """
                %d MCP-Parameter versprechen dem Aufrufer, weglassbar zu sein, sind im Schema \
                aber PFLICHT (@McpToolParam ohne required = false).
                Der Aufrufer kann diese Beschreibung nicht befolgen. Er schickt stattdessen einen \
                Leerstring oder die Zeichenkette "null" — und genau so wurde am 02.08.2026 ein \
                Produktivkontakt ueberschrieben (Karte 467).
                Zwei gueltige Antworten je Fund:
                  a) Feld ist weglassbar  -> required = false setzen
                  b) Feld ist Pflicht     -> Beschreibung berichtigen
                Funde in einem konsumierten Modul werden im Herkunftsprojekt behoben und ueber die \
                Versions-Property nachgezogen; bis dahin als param.altlast.<werkzeug>.<parameter> \
                mit Begruendung in %s.
                %s""".formatted(verstoesse.size(), VERTRAG,
                verstoesse.stream().map(Verstoss::toString).collect(Collectors.joining("\n"))));
    }

    @Test
    @DisplayName("Selbstauskunft: der Scan sieht die festgehaltene Zahl Werkzeuge")
    void derScanFindetWerkzeuge() {
        Vertrag vertrag = Vertrag.laden();
        List<Method> werkzeuge = alleWerkzeuge();
        if (!vertrag.vorhanden()) {
            assertTrue(werkzeuge.isEmpty(), "Es sind " + werkzeuge.size() + " MCP-Werkzeuge am Klassenpfad, "
                    + "aber es gibt keine " + VERTRAG + " im Test-Classpath — ohne festgehaltene Zahl bliebe "
                    + "ein Totalausfall des Scans unsichtbar.");
            return;
        }
        assertTrue(werkzeuge.size() >= vertrag.mindestens(),
                "Der Klassenpfad-Scan fand nur " + werkzeuge.size() + " MCP-Werkzeuge, festgehalten sind "
                        + vertrag.mindestens() + " (" + VERTRAG + ", mindestens.werkzeuge). Damit misst der "
                        + "Vertragstest nichts mehr, und sein gruenes Ergebnis waere eine Scheinaussage.");
        long mitParametern = werkzeuge.stream()
                .filter(m -> Arrays.stream(m.getParameters()).anyMatch(p -> paramAnnotation(p) != null))
                .count();
        assertTrue(werkzeuge.isEmpty() || mitParametern > 0,
                "Kein einziges der " + werkzeuge.size() + " Werkzeuge hat @McpToolParam-Parameter — die "
                        + "Annotationen kommen offenbar nicht bis zur Laufzeit durch (RetentionPolicy?).");

        if (vertrag.eigenesPaket() != null) {
            long eigene = werkzeuge.stream()
                    .filter(m -> m.getDeclaringClass().getName().startsWith(vertrag.eigenesPaket())).count();
            long konsumierte = werkzeuge.size() - eigene;
            assertTrue(eigene >= vertrag.mindestensEigene(),
                    "Nur " + eigene + " EIGENE MCP-Werkzeuge (" + vertrag.eigenesPaket() + "…) gefunden, "
                            + "festgehalten sind " + vertrag.mindestensEigene() + ".");
            assertTrue(konsumierte >= vertrag.mindestensKonsumierte(),
                    "Nur " + konsumierte + " KONSUMIERTE MCP-Werkzeuge gefunden, festgehalten sind "
                            + vertrag.mindestensKonsumierte() + ". Genau dieser Zweig fehlte in guild bis "
                            + "04.08.2026, und der Test war trotzdem gruen. Haengen die konsumierten Module "
                            + "noch am webapp-Klassenpfad?");
        }
    }

    @Test
    @DisplayName("Kein Eintrag der Altlastenliste ist ueberfluessig")
    void keineUeberfluessigenAltlasten() {
        Vertrag vertrag = Vertrag.laden();
        if (vertrag.altlasten().isEmpty()) {
            return;
        }
        Set<String> vorhanden = verstoesse(alleWerkzeuge()).stream()
                .map(Verstoss::schluessel)
                .collect(Collectors.toSet());
        List<String> ueberfluessig = vertrag.altlasten().keySet().stream()
                .filter(k -> !vorhanden.contains(k))
                .sorted()
                .toList();
        assertTrue(ueberfluessig.isEmpty(), () -> """
                %d Altlast-Eintrag/Eintraege in %s sind ueberfluessig — der Verstoss existiert \
                nicht mehr:
                %s
                Bitte entfernen. Eine Ausnahme, die nichts mehr ausnimmt, verdeckt nur den naechsten \
                echten Fund an derselben Stelle.""".formatted(
                ueberfluessig.size(), VERTRAG, String.join("\n", ueberfluessig)));
    }

    /**
     * Positivkontrolle: ein Pflichtparameter, der „optional" verspricht, wird gemeldet; derselbe
     * Satz an einem {@code required = false}-Parameter und ein ehrlicher Pflichtparameter nicht.
     * Die Beispiele tragen Stellvertreter-Annotationen mit denselben Attributen; geprueft wird die
     * Logik, die oben mit den echten Namen laeuft.
     */
    @Test
    @DisplayName("Positivkontrolle: 'optional' an einem Pflichtparameter wird gemeldet")
    void positivkontrolle() throws NoSuchMethodException {
        Method m = Beispiel.class.getDeclaredMethod("werkzeug", String.class, String.class, String.class);
        List<Verstoss> v = verstoesse(List.of(m), Werkzeug.class, Param.class);
        assertEquals(1, v.size(), "Genau der Pflichtparameter mit Weglass-Zusage muss auffallen: " + v);
        assertEquals("beispiel_werkzeug.falsch", v.get(0).schluessel());
        assertTrue(VERSPRICHT_WEGLASSBAR.matcher("Optionale Bemerkung").find(), "Flexion muss greifen.");
        assertTrue(VERSPRICHT_WEGLASSBAR.matcher("null/leer = din5008").find(), "Schraegstrich muss greifen.");
    }

    // ------------------------------------------------------------------------------------------ Scan

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> annotation(String name) {
        try {
            return (Class<? extends Annotation>) Class.forName(name, false,
                    McpToolParamVertragTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null; // kein spring-ai am Klassenpfad -> keine Werkzeuge
        }
    }

    /** Alle Methoden unter {@code ch.plaintext} (ohne Testklassen), die als MCP-Werkzeug ausgeliefert werden. */
    static List<Method> alleWerkzeuge() {
        Class<? extends Annotation> tool = annotation(MCP_TOOL);
        if (tool == null) {
            return List.of();
        }
        JavaClasses klassen = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("ch.plaintext");
        List<Method> werkzeuge = new ArrayList<>();
        for (JavaClass jc : klassen) {
            if (jc.getMethods().stream().noneMatch(m -> m.isAnnotatedWith(MCP_TOOL))) {
                continue;
            }
            for (Method m : jc.reflect().getDeclaredMethods()) {
                if (m.isAnnotationPresent(tool)) {
                    werkzeuge.add(m);
                }
            }
        }
        return werkzeuge;
    }

    private static Annotation paramAnnotation(Parameter p) {
        Class<? extends Annotation> param = annotation(MCP_TOOL_PARAM);
        return param == null ? null : p.getAnnotation(param);
    }

    static List<Verstoss> verstoesse(List<Method> werkzeuge) {
        return verstoesse(werkzeuge, annotation(MCP_TOOL), annotation(MCP_TOOL_PARAM));
    }

    static List<Verstoss> verstoesse(List<Method> werkzeuge, Class<? extends Annotation> tool,
                                     Class<? extends Annotation> param) {
        List<Verstoss> verstoesse = new ArrayList<>();
        if (tool == null || param == null) {
            return verstoesse;
        }
        for (Method m : werkzeuge) {
            for (Parameter p : m.getParameters()) {
                Annotation a = p.getAnnotation(param);
                if (a == null || !Boolean.TRUE.equals(attribut(a, "required"))) {
                    continue;
                }
                String beschreibung = String.valueOf(attribut(a, "description"));
                if (VERSPRICHT_WEGLASSBAR.matcher(beschreibung).find()) {
                    verstoesse.add(new Verstoss(werkzeugName(m, tool), p.getName(), beschreibung,
                            m.getDeclaringClass().getSimpleName(), herkunft(m)));
                }
            }
        }
        return verstoesse;
    }

    /** Namensregel des Providers: ein gesetzter {@code name} gewinnt, sonst gilt der Methodenname. */
    private static String werkzeugName(Method m, Class<? extends Annotation> tool) {
        Object name = attribut(m.getAnnotation(tool), "name");
        return name != null && !String.valueOf(name).isBlank() ? String.valueOf(name) : m.getName();
    }

    private static Object attribut(Annotation a, String name) {
        try {
            return a.annotationType().getMethod(name).invoke(a);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Attribut " + name + " von @" + a.annotationType().getName()
                    + " nicht lesbar — hat spring-ai die Annotation umgebaut?", e);
        }
    }

    private static String herkunft(Method m) {
        CodeSource cs = m.getDeclaringClass().getProtectionDomain().getCodeSource();
        if (cs == null || cs.getLocation() == null) {
            return "Herkunft nicht ermittelbar";
        }
        String pfad = cs.getLocation().getPath();
        String datei = pfad.endsWith("/") ? pfad : pfad.substring(pfad.lastIndexOf('/') + 1);
        if (!datei.endsWith(".jar")) {
            return "dieses Repo";
        }
        return datei.replaceFirst("-\\d.*\\.jar$", "") + " (konsumiert — dort beheben, hier per "
                + "Versions-Property nachziehen)";
    }

    // ------------------------------------------------------------------------------------------ Vertrag

    record Vertrag(boolean vorhanden, int mindestens, Map<String, String> altlasten, String eigenesPaket,
                   int mindestensEigene, int mindestensKonsumierte) {

        static Vertrag laden() {
            Properties p = new Properties();
            try (InputStream in = McpToolParamVertragTest.class.getClassLoader().getResourceAsStream(VERTRAG)) {
                if (in == null) {
                    return new Vertrag(false, 0, Map.of(), null, 0, 0);
                }
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException(VERTRAG + " ist nicht lesbar", e);
            }
            Map<String, String> altlasten = new TreeMap<>();
            for (String k : p.stringPropertyNames()) {
                if (k.startsWith("param.altlast.")) {
                    altlasten.put(k.substring("param.altlast.".length()), p.getProperty(k));
                }
            }
            return new Vertrag(true,
                    Integer.parseInt(p.getProperty("mindestens.werkzeuge", "0").trim()),
                    altlasten,
                    p.getProperty("param.eigenes.paket"),
                    Integer.parseInt(p.getProperty("param.mindestens.eigene", "0").trim()),
                    Integer.parseInt(p.getProperty("param.mindestens.konsumierte", "0").trim()));
        }
    }

    // ------------------------------------------------------------------------------------------ Beispiele

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Werkzeug {
        String name() default "";
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @interface Param {
        String description() default "";

        boolean required() default true;
    }

    static class Beispiel {
        @Werkzeug(name = "beispiel_werkzeug")
        void werkzeug(@Param(description = "Titel, optional") String falsch,
                      @Param(description = "Titel, optional", required = false) String richtig,
                      @Param(description = "Kontakt-Id") String pflicht) {
            // nur Beispiel
        }
    }
}
