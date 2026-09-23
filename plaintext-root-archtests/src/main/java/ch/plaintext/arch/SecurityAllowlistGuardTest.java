/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlProcessor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.AntPathMatcher;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sicherheits-Regressionstest fuer die beiden Allowlists der {@code application.yml}:
 * {@code plaintext.security.permit-all-patterns} (ohne Anmeldung erreichbar) und
 * {@code plaintext.security.csrf-ignore-patterns} (ohne CSRF-Token erreichbar).
 *
 * <p><b>Warum ein Test und nicht Sorgfalt.</b> plaintext-root wendet die permit-all-Liste VOR den
 * Rollenregeln an ({@code PlaintextSecurityConfig}: zuerst {@code requestMatchers(permitAll).permitAll()},
 * danach erst {@code /actuator/** -> ROLE_ADMIN}). Ein zu weit gefasster Eintrag — {@code /api/**},
 * {@code /**} — oeffnet alles, was nach ihm kommt, und faellt nicht auf: die Seiten funktionieren
 * weiter, nur eben auch ohne Anmeldung. Ein CSRF-Ausnahmeeintrag auf eine Formularseite nimmt ihr den
 * einzigen CSRF-Schutz — der JSF-ViewState ist keiner.
 *
 * <p><b>Karte 1328: eine Mechanik statt vier Kopien, die Daten je Repo.</b> Bis zum 23.09.2026 lag
 * dieser Test in app, guild, schuetu und iot als Kopie, in root und fwtool gar nicht, und jede Kopie
 * konnte etwas, das den anderen fehlte: nur app pruefte „kein Wildcard auf die Wurzel", nur guild
 * „csrf-ignore entbloesst keine Formularseite", nur schuetu „keine Camel-Case-Schluessel", nur
 * app/guild verlangten, dass die Profil-YAML-Suche {@code application-prod.yml} wirklich findet —
 * iot hatte keine CSRF-Pruefung und bewachte nur {@code /api/shelly/**}. Diese Klasse vereinigt die
 * staerkste Fassung jeder Pruefung und laeuft in allen sechs Repos. Die <b>Soll-Daten</b> (was
 * freigegeben ist und warum, welche Pfade geschuetzt bleiben) sind fachlich je Anwendung verschieden
 * und stehen in {@code plaintext-security-allowlist.txt} an der Repo-Wurzel:
 * <pre>
 *   permit-all        /mcp/**              # Begruendung (Pflicht)
 *   csrf-ignore       /mcp/**              # Begruendung (Pflicht)
 *   geschuetzt        /member.xhtml        # darf von keinem permit-all getroffen werden
 *   csrf-pflicht      /api/i18n/import     # darf von keinem csrf-ignore getroffen werden
 *   profil-yaml       application-prod.yml # genau diese application-*.yml liegen neben application.yml
 *   oeffentliche-view /status.xhtml        # View, die ein permit-all treffen DARF (Begruendung Pflicht)
 * </pre>
 * Reihenfolge der {@code permit-all}/{@code csrf-ignore}-Zeilen = Reihenfolge in der YAML.
 *
 * <p><b>Zwei Verschaerfungen gegenueber allen Kopien.</b> (1) Geprueft wird nicht nur eine
 * handgepflegte Stichprobe: <i>jede</i> ausgelieferte View am Klassenpfad (auch die aus den
 * root-Jars) darf weder von einem permit-all noch von einem csrf-ignore getroffen werden. (2) Profile
 * stehen nicht nur in {@code application-*.yml}: auch ein Dokument mit
 * {@code spring.config.activate.on-profile} in der {@code application.yml} selbst (root nutzt das)
 * darf die Listen nicht neu setzen — die Kopien lasen die Datei als Ganzes und haetten ein solches
 * Dokument nicht als Profil erkannt.
 *
 * <p><b>Die Profil-Untergrenze</b> ist die aus app/guild, aber je Repo: ein Waechter, der seine
 * Dateien nicht findet, ist gruen, ohne etwas zu pruefen. Deshalb nennt der Vertrag die
 * {@code application-*.yml}, die es geben muss, und die gefundene Menge muss genau diese sein — eine
 * verschwundene {@code application-prod.yml} wird rot, eine neue unbekannte ebenso.
 *
 * <p><b>Grenze, absichtlich benannt.</b> Geprueft wird die Konfiguration, nicht die laufende
 * Filterkette, und nur die YAML-Listen — die fest eingebauten Freigaben von root
 * ({@code PlaintextSecurityConfig}) haben eigene Tests in root.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class SecurityAllowlistGuardTest {

    static final String DATEINAME = "plaintext-security-allowlist.txt";

    static final String PERMIT_ALL = "plaintext.security.permit-all-patterns";
    static final String CSRF_IGNORE = "plaintext.security.csrf-ignore-patterns";
    /** Die alte Schreibweise (schuetu bis 29.08.2026). Per relaxed binding gleichwertig — also eine zweite Quelle. */
    static final List<String> ALLE_SCHLUESSEL = List.of(PERMIT_ALL, CSRF_IGNORE,
            "plaintext.security.permitAllPatterns", "plaintext.security.csrfIgnorePatterns");

    static final Set<String> ARTEN = Set.of("permit-all", "csrf-ignore", "geschuetzt", "csrf-pflicht",
            "profil-yaml", "oeffentliche-view");

    /** Muster, die die ganze Anwendung freigeben (app-Pruefung, jetzt fuer BEIDE Listen). */
    static final Set<String> WURZEL_WILDCARDS = Set.of("/**", "/*", "**", "/**/*", "/", "");

    /** Pfade, die immer CSRF-pflichtig sind, egal was der Vertrag sagt. */
    static final List<String> IMMER_CSRF_PFLICHT = List.of("/login.xhtml", "/login.html");

    /** Mindestzahl der am Klassenpfad gefundenen Views (root hat die wenigsten eigenen: 37, 23.09.2026). */
    static final int MINDESTENS_VIEWS = 10;

    /** Mindestzahl ausdruecklich geschuetzter Pfade im Vertrag — eine leere Stichprobe prueft nichts. */
    static final int MINDESTENS_GESCHUETZT = 3;

    // ---------------------------------------------------------------------------------- Tests

    @Test
    @DisplayName("Vertrag plaintext-security-allowlist.txt ist vorhanden, lesbar und begruendet")
    void vertragIstVollstaendig() {
        Vertrag v = Vertrag.laden();
        assertTrue(v.fehler.isEmpty(), () -> "Fehler im Vertrag:\n  " + String.join("\n  ", v.fehler));
        assertTrue(v.liste("geschuetzt").size() >= MINDESTENS_GESCHUETZT,
                "Der Vertrag nennt nur " + v.liste("geschuetzt").size() + " 'geschuetzt'-Pfade, mindestens "
                        + MINDESTENS_GESCHUETZT + " — eine leere Stichprobe prueft nichts.");
    }

    @Test
    @DisplayName("die Allowlists der application.yml entsprechen genau dem Vertrag")
    void allowlistsEntsprechenDemVertrag() {
        Vertrag v = Vertrag.laden();
        Yaml y = Yaml.laden();
        String b = abweichung(v, y);
        assertTrue(b == null, b);
    }

    @Test
    @DisplayName("kein permit-all trifft einen geschuetzten Pfad oder eine ausgelieferte View")
    void permitAllOeffnetNichtsGeschuetztes() {
        List<String> befunde = permitAllBefunde(Yaml.laden().basis(PERMIT_ALL), Vertrag.laden(), views());
        assertTrue(befunde.isEmpty(), () -> "permit-all oeffnet ohne Anmeldung:\n  " + String.join("\n  ", befunde));
    }

    @Test
    @DisplayName("kein Eintrag in permit-all oder csrf-ignore ist ein Wildcard auf die Wurzel")
    void keinWildcardAufDieWurzel() {
        Yaml y = Yaml.laden();
        List<String> befunde = new ArrayList<>(wurzelBefunde(PERMIT_ALL, y.basis(PERMIT_ALL)));
        befunde.addAll(wurzelBefunde(CSRF_IGNORE, y.basis(CSRF_IGNORE)));
        assertTrue(befunde.isEmpty(), () -> String.join("\n", befunde));
    }

    @Test
    @DisplayName("kein csrf-ignore trifft eine Formularseite (jede View, /login, csrf-pflicht)")
    void csrfIgnoreEntbloesstKeineFormularseite() {
        List<String> befunde = csrfBefunde(Yaml.laden().basis(CSRF_IGNORE), Vertrag.laden(), views());
        assertTrue(befunde.isEmpty(), () -> "csrf-ignore nimmt den CSRF-Schutz von:\n  " + String.join("\n  ", befunde));
    }

    @Test
    @DisplayName("genau die vertraglichen application-*.yml liegen neben application.yml")
    void profilDateienSindDieVertraglichen() {
        Set<String> gefunden = new TreeSet<>(profilYamls());
        Set<String> soll = new TreeSet<>(Vertrag.laden().liste("profil-yaml"));
        assertEquals(soll, gefunden, "Die Profil-YAMLs neben application.yml weichen vom Vertrag ab (Soll "
                + soll + ", gefunden " + gefunden + "). Fehlt eine, sucht der Waechter am falschen Ort oder "
                + "sie ist verloren gegangen — und prueft sie dann nicht. Ist eine neu, gehoert sie mit Grund "
                + "in den Vertrag, damit sie hier mitgeprueft wird.");
    }

    @Test
    @DisplayName("kein Profil (application-*.yml oder on-profile-Dokument) setzt die Listen neu")
    void profileUeberschreibenKeineListen() {
        List<String> befunde = new ArrayList<>();
        Yaml y = Yaml.laden();
        befunde.addAll(y.profilBefunde());
        for (String datei : profilYamls()) {
            for (Properties doc : Yaml.dokumente(new ClassPathResource(datei))) {
                for (String k : ALLE_SCHLUESSEL) {
                    if (!liste(doc, k).isEmpty()) {
                        befunde.add(datei + " setzt " + k + " = " + liste(doc, k));
                    }
                }
            }
        }
        assertTrue(befunde.isEmpty(), () -> "Ein Profil ueberschreibt die Security-Listen — dann gelten im "
                + "Container andere als die hier gepruefte:\n  " + String.join("\n  ", befunde));
    }

    @Test
    @DisplayName("keine Camel-Case-Schluessel (zweite Quelle fuer dieselbe Property)")
    void keineCamelCaseSchluessel() {
        List<String> befunde = new ArrayList<>();
        for (Properties doc : Yaml.laden().alle) {
            for (String k : ALLE_SCHLUESSEL.subList(2, 4)) {
                if (!liste(doc, k).isEmpty()) {
                    befunde.add(k);
                }
            }
        }
        assertTrue(befunde.isEmpty(), "Camel-Case-Schluessel in application.yml: " + befunde
                + " — Kebab-Case verwenden, sonst gibt es zwei Quellen und welche Spring nimmt, steht nirgends.");
    }

    /**
     * Positivkontrolle je Pruefung, mit erfundenen Daten: jede Regel muss an genau dem Fall anschlagen,
     * fuer den es sie gibt, und an einem sauberen Fall schweigen.
     */
    @Test
    @DisplayName("Positivkontrolle: Wurzel-Wildcard, CSRF auf Formularseite, geschuetzter Pfad, Profil-Override, Soll-Drift")
    void positivkontrolle() {
        Vertrag v = Vertrag.parse(List.of(
                "permit-all   /mcp/**         # Bearer",
                "csrf-ignore  /mcp/**         # Bearer",
                "geschuetzt   /member.xhtml",
                "geschuetzt   /actuator/env",
                "geschuetzt   /api/geheim/1",
                "csrf-pflicht /api/i18n/import",
                "profil-yaml  application-prod.yml"), "beispiel");
        assertTrue(v.fehler.isEmpty(), "Beispielvertrag fehlerhaft: " + v.fehler);
        List<String> views = List.of("/home.xhtml", "/member.xhtml");

        // sauber -> nichts
        assertTrue(permitAllBefunde(List.of("/mcp/**"), v, views).isEmpty(), "sauberes permit-all meldet etwas");
        assertTrue(csrfBefunde(List.of("/mcp/**"), v, views).isEmpty(), "sauberes csrf-ignore meldet etwas");
        assertTrue(wurzelBefunde(PERMIT_ALL, List.of("/mcp/**")).isEmpty(), "Wurzelpruefung meldet /mcp/**");

        // 1. Wurzel freigegeben
        assertFalse(wurzelBefunde(PERMIT_ALL, List.of("/mcp/**", "/**")).isEmpty(), "/** permitAll muss auffallen");
        assertFalse(wurzelBefunde(CSRF_IGNORE, List.of("/*")).isEmpty(), "/* csrf-ignore muss auffallen");
        // 2. geschuetzter Pfad / View per permit-all
        assertFalse(permitAllBefunde(List.of("/api/**"), v, views).isEmpty(), "/api/** oeffnet /api/geheim/1");
        assertFalse(permitAllBefunde(List.of("/home.*"), v, views).isEmpty(), "permit-all auf eine View");
        // 3. csrf-ignore auf eine Formularseite (View, /login, csrf-pflicht)
        assertFalse(csrfBefunde(List.of("/member.xhtml"), v, views).isEmpty(), "csrf-ignore auf View");
        assertFalse(csrfBefunde(List.of("/login.*"), v, List.of()).isEmpty(), "csrf-ignore auf /login");
        assertFalse(csrfBefunde(List.of("/api/i18n/**"), v, views).isEmpty(), "csrf-ignore auf csrf-pflicht");
        // 4. Soll-Drift
        Yaml drift = Yaml.aus("plaintext:\n  security:\n    permit-all-patterns:\n      - /mcp/**\n      - /api/**\n"
                + "    csrf-ignore-patterns:\n      - /mcp/**\n");
        assertNotNull(abweichung(v, drift), "ein zusaetzlicher permit-all-Eintrag muss auffallen");
        Yaml gleich = Yaml.aus("plaintext:\n  security:\n    permit-all-patterns:\n      - /mcp/**\n"
                + "    csrf-ignore-patterns:\n      - /mcp/**\n");
        assertEquals(null, abweichung(v, gleich), "die vertragsgemaesse YAML darf nicht auffallen");
        // 5. on-profile-Dokument setzt die Liste neu
        Yaml profil = Yaml.aus("plaintext:\n  security:\n    permit-all-patterns:\n      - /mcp/**\n---\n"
                + "spring:\n  config:\n    activate:\n      on-profile: prod\nplaintext:\n  security:\n"
                + "    permit-all-patterns:\n      - /**\n");
        assertFalse(profil.profilBefunde().isEmpty(), "ein on-profile-Dokument mit Security-Liste muss auffallen");
        assertEquals(List.of("/mcp/**"), profil.basis(PERMIT_ALL), "Basisliste falsch gelesen");
        // 6. Vertrag ohne Begruendung
        assertFalse(Vertrag.parse(List.of("permit-all /x/**"), "b").fehler.isEmpty(),
                "permit-all ohne Begruendung muss auffallen");
    }

    // ---------------------------------------------------------------------------------- Regeln

    static String abweichung(Vertrag v, Yaml y) {
        List<String> teile = new ArrayList<>();
        if (!v.liste("permit-all").equals(y.basis(PERMIT_ALL))) {
            teile.add(PERMIT_ALL + ": Vertrag " + v.liste("permit-all") + ", application.yml " + y.basis(PERMIT_ALL));
        }
        if (!v.liste("csrf-ignore").equals(y.basis(CSRF_IGNORE))) {
            teile.add(CSRF_IGNORE + ": Vertrag " + v.liste("csrf-ignore") + ", application.yml " + y.basis(CSRF_IGNORE));
        }
        return teile.isEmpty() ? null : "Allowlist weicht vom Vertrag " + DATEINAME + " ab — eine Aenderung an "
                + "der Liste ist ein Sicherheitsentscheid und gehoert dort mit Begruendung nachgezogen:\n  "
                + String.join("\n  ", teile);
    }

    static List<String> permitAllBefunde(List<String> muster, Vertrag v, List<String> views) {
        AntPathMatcher m = new AntPathMatcher();
        List<String> befunde = new ArrayList<>();
        List<String> ziele = new ArrayList<>(v.liste("geschuetzt"));
        Set<String> oeffentlich = new TreeSet<>(v.liste("oeffentliche-view"));
        for (String view : views) {
            if (!oeffentlich.contains(view)) {
                ziele.add(view);
                ziele.add(view.replaceFirst("\\.xhtml$", ".html"));
            }
        }
        for (String p : muster) {
            for (String z : ziele) {
                if (m.match(p, z)) {
                    befunde.add("'" + p + "' trifft " + z);
                }
            }
        }
        return befunde;
    }

    static List<String> csrfBefunde(List<String> muster, Vertrag v, List<String> views) {
        AntPathMatcher m = new AntPathMatcher();
        List<String> ziele = new ArrayList<>(IMMER_CSRF_PFLICHT);
        ziele.addAll(v.liste("csrf-pflicht"));
        for (String view : views) {
            ziele.add(view);
            ziele.add(view.replaceFirst("\\.xhtml$", ".html"));
        }
        List<String> befunde = new ArrayList<>();
        for (String p : muster) {
            for (String z : ziele) {
                if (m.match(p, z)) {
                    befunde.add("'" + p + "' trifft " + z);
                }
            }
        }
        return befunde;
    }

    static List<String> wurzelBefunde(String schluessel, List<String> muster) {
        List<String> befunde = new ArrayList<>();
        for (String p : muster) {
            if (WURZEL_WILDCARDS.contains(p.strip())) {
                befunde.add(schluessel + "-Eintrag '" + p + "' gibt die ganze Anwendung frei.");
            }
        }
        return befunde;
    }

    // ---------------------------------------------------------------------------------- Quellen

    /** Alle ausgelieferten Views am Klassenpfad ({@code /x.xhtml}), ohne Includes/Templates. */
    static List<String> views() {
        String wurzel = "META-INF/resources/";
        Set<String> gefunden = new TreeSet<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:" + wurzel + "**/*.xhtml")) {
                String url = r.getURL().toString();
                int i = url.lastIndexOf(wurzel);
                if (i < 0) {
                    continue;
                }
                String pfad = "/" + url.substring(i + wurzel.length());
                if (pfad.contains("/includes/") || pfad.contains("/templates/")) {
                    continue;
                }
                gefunden.add(pfad);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Klassenpfad nach Views nicht durchsuchbar", e);
        }
        assertTrue(gefunden.size() >= MINDESTENS_VIEWS, "Nur " + gefunden.size() + " Views am Klassenpfad gefunden, "
                + "mindestens " + MINDESTENS_VIEWS + " — dann prueft die View-Regel nichts.");
        return List.copyOf(gefunden);
    }

    /** Die application.yml DIESES Moduls (target/classes, nicht aus einem Jar — guilds Absicherung). */
    static ClassPathResource eigeneApplicationYml() {
        ClassPathResource r = new ClassPathResource("application.yml");
        try {
            assertEquals("file", r.getURL().getProtocol(),
                    "application.yml kommt aus einem Jar statt aus target/classes — falsches Modul: " + r.getURL());
        } catch (IOException e) {
            throw new UncheckedIOException("application.yml nicht am Klassenpfad", e);
        }
        return r;
    }

    static List<String> profilYamls() {
        try {
            File dir = eigeneApplicationYml().getFile().getParentFile();
            String[] namen = dir.list((d, n) -> n.startsWith("application-") && (n.endsWith(".yml") || n.endsWith(".yaml")));
            assertNotNull(namen, "Verzeichnis von application.yml nicht lesbar: " + dir);
            return Arrays.stream(namen).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> liste(Properties p, String prefix) {
        List<String> r = new ArrayList<>();
        for (int i = 0; p.getProperty(prefix + "[" + i + "]") != null; i++) {
            r.add(p.getProperty(prefix + "[" + i + "]"));
        }
        return r;
    }

    /** Die Dokumente der application.yml, getrennt nach Basis und Profil. */
    static final class Yaml {
        final List<Properties> alle;

        private Yaml(List<Properties> alle) {
            this.alle = alle;
        }

        static Yaml laden() {
            return new Yaml(dokumente(eigeneApplicationYml()));
        }

        static Yaml aus(String text) {
            return new Yaml(dokumente(new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8))));
        }

        static List<Properties> dokumente(Resource r) {
            return new Leser().lesen(r);
        }

        static boolean istProfil(Properties p) {
            return p.getProperty("spring.config.activate.on-profile") != null || p.getProperty("spring.profiles") != null;
        }

        /** Die Liste aus den Basisdokumenten (ohne on-profile); mehrere setzende Basisdokumente: der letzte gewinnt wie in Spring. */
        List<String> basis(String schluessel) {
            List<String> r = List.of();
            for (Properties p : alle) {
                if (!istProfil(p) && !liste(p, schluessel).isEmpty()) {
                    r = liste(p, schluessel);
                }
            }
            return r;
        }

        List<String> profilBefunde() {
            List<String> b = new ArrayList<>();
            for (Properties p : alle) {
                if (istProfil(p)) {
                    for (String k : ALLE_SCHLUESSEL) {
                        if (!liste(p, k).isEmpty()) {
                            b.add("application.yml, Profil '" + p.getProperty("spring.config.activate.on-profile",
                                    p.getProperty("spring.profiles")) + "' setzt " + k + " = " + liste(p, k));
                        }
                    }
                }
            }
            return b;
        }
    }

    /** Liest eine YAML-Datei Dokument fuer Dokument (ohne Profil-Filter, flache Schluessel wie Spring). */
    static final class Leser extends YamlProcessor {
        List<Properties> lesen(Resource r) {
            List<Properties> docs = new ArrayList<>();
            setResources(r);
            process((props, map) -> docs.add(props));
            return docs;
        }
    }

    /** {@code plaintext-security-allowlist.txt} an der Repo-Wurzel. */
    static final class Vertrag {
        final Map<String, List<String>> eintraege = new LinkedHashMap<>();
        final List<String> fehler = new ArrayList<>();

        List<String> liste(String art) {
            return eintraege.getOrDefault(art, List.of());
        }

        static Vertrag laden() {
            Path root = ReactorLayout.repoRoot();
            assertNotNull(root, "Kein Reactor gefunden ab " + ReactorLayout.start());
            Path datei = root.resolve(DATEINAME);
            assertTrue(Files.isRegularFile(datei), DATEINAME + " fehlt an der Repo-Wurzel " + root + ". Jede "
                    + "Anwendung haelt ihre Security-Allowlists dort mit Begruendung fest (Karte 1328).");
            try {
                return parse(Files.readAllLines(datei), DATEINAME);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        static Vertrag parse(List<String> zeilen, String name) {
            Vertrag v = new Vertrag();
            int nr = 0;
            for (String raw : zeilen) {
                nr++;
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int hash = line.indexOf(" #");
                String eintrag = hash < 0 ? line : line.substring(0, hash).strip();
                String grund = hash < 0 ? "" : line.substring(hash + 2).strip();
                String[] t = eintrag.split("\\s+");
                if (t.length != 2 || !ARTEN.contains(t[0])) {
                    v.fehler.add(name + ":" + nr + " -> erwartet '<art> <wert>  # <Grund>' mit art aus " + ARTEN + ": " + raw);
                    continue;
                }
                if (grund.isEmpty() && Set.of("permit-all", "csrf-ignore", "oeffentliche-view").contains(t[0])) {
                    v.fehler.add(name + ":" + nr + " -> '" + t[0] + "' ohne Begruendung (nach ' #'): " + raw);
                    continue;
                }
                v.eintraege.computeIfAbsent(t[0], k -> new ArrayList<>()).add(t[1]);
            }
            return v;
        }
    }
}
