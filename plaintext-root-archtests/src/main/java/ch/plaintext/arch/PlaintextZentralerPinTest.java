/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the central version pins of {@code plaintext-root} against local pins that quietly
 * override them (card 1296, finding 6 of the refactoring analysis 1274).
 *
 * <p><b>The mechanism this rule exists for.</b> {@code plaintext-root/pom.xml} pins
 * {@code jakarta.faces} centrally and with a reason: <em>"at least 4.1.13: there Mojarra closes
 * CVE-2026-46581 (path traversal/RCE via ui:include, CVSS 7.5). Do not turn back."</em> A module
 * POM that writes its own {@code <version>} next to it beats {@code dependencyManagement} and
 * every property — and Renovate, the root auto-bump and every central hardening walk past it
 * <em>without reporting an error</em>. That is not a thought experiment: fwtool sat on a
 * hard-wired 4.1.9 — <em>below</em> the CVE threshold — while the parent POM had long stood
 * higher, and nobody noticed for weeks. The same mechanism was described three times in the
 * repository itself ({@code plaintext-app-webapp/pom.xml}, {@code plaintext-guild-webapp/pom.xml},
 * {@code plaintext-root-webapp/pom.xml}) — described, but never prevented. A tidied POM holds
 * exactly as long as it takes for the next person to write a version next to the central one.
 *
 * <p><b>Two rules, both narrow on purpose.</b>
 * <ol>
 *   <li>{@link #keinModulPinAufZentralVerwalteterAbhaengigkeit()} — no POM of the reactor may
 *       carry a <em>literal</em> {@code <version>} on a dependency whose version
 *       {@code plaintext-root} manages. Literal, because {@code ${project.version}} and
 *       {@code ${plaintext-root.version}} on the in-house modules are the idiom of this codebase
 *       and cannot drift: they are derived, not written down. A literal number can.</li>
 *   <li>{@link #keinReaktorPinHinterDemZentralenPin()} — the reactor root may not redefine a
 *       version property of {@code plaintext-root} with a value that lies <em>behind</em> the
 *       central one. That is the direction in which a central hardening gets lost.</li>
 * </ol>
 *
 * <p><b>Why the equal-value duplicate is not red.</b> app and guild repeat about 29 of root's
 * properties one-to-one. Making every one of them a violation would mean a rule with 29
 * exceptions on the day it is installed — and {@code PlaintextLayeringTest} already records for
 * L3 why that is worthless: <em>"a rule with thirty exceptions checks nothing, it only
 * documents."</em> The duplicate is redundancy; only the value behind root is a risk. Redundant
 * duplicates are cleaned up by hand (card 1296 did that for {@code jakarta.faces.version}); what
 * this rule prevents is that the next one silently falls behind.
 *
 * <p><b>Plugin versions are deliberately out of scope.</b> Checked are only properties that
 * root's {@code <dependencyManagement>} actually references. {@code sonar-maven-plugin.version}
 * or {@code build-helper-maven-plugin.version} lagging behind in a consumer is untidy, but it
 * does not defeat a dependency pin — and a rule that fires on everything gets switched off.
 *
 * <p><b>Where root's pin list comes from.</b> {@code plaintext-root/pom.xml} travels along inside
 * this jar as {@value #WURZEL_POM} (see the {@code maven-resources-plugin} block in
 * {@code plaintext-root-archtests/pom.xml}). The copy is automatically the right one: the jar
 * carries the root version the consumer has pinned as its parent, so the comparison happens
 * against exactly the POM the consumer inherits from. If the resource is missing the test fails —
 * a guard that is green because it found nothing to read is the failure mode this whole card is
 * about.
 *
 * <p><b>Untergrenze der Scanmenge.</b> Rule 1 additionally asserts that the lookup found at least
 * {@value #MINDESTENS_POMS} POMs ({@link ReactorLayout#untergrenze}, the mechanism card 1274
 * introduced for the seven file linters): a POM linter whose lookup breaks finds no POMs and
 * reports "no forbidden pin" — green without having read anything. Rule 2 has the same bound in
 * {@link #zentraleListeIstLesbar()}: it fails if root's pin list is missing or implausibly small.
 *
 * <p><b>Exceptions:</b> the reactor's allowlist ({@code plaintext-arch-allowlist.txt}, rule
 * {@value #ALLOWLIST_REGEL}), target = {@code <pfad-relativ-zur-reaktorwurzel>#<groupId>:<artifactId>}
 * for rule 1 and {@code pom.xml#<property-name>} for rule 2, justification mandatory
 * ({@link ArchAllowlist}). root itself keeps no allowlist: the framework has to pass its own rule
 * without exception.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class PlaintextZentralerPinTest {

    static final String ALLOWLIST_REGEL = "zentral-pin";

    /** root's POM, copied into this jar at build time (see {@code pom.xml} of this module). */
    static final String WURZEL_POM = "plaintext-zentralpin/pom.xml";

    /** Directories below the reactor root that hold no buildable POM. */
    private static final List<String> SKIP_DIRS =
            List.of("target", "src", ".git", "node_modules", ".mvn", ".idea", "node");

    /** Maximum directory depth below the reactor root at which POMs are still looked for. */
    private static final int MAX_TIEFE = 5;

    /**
     * Lower bound for the scan set (measured, see {@link ReactorLayout#untergrenze}). Counted on
     * 22.09.2026: root 26 POMs, app 32, guild 9, schuetu 3, iot 3 — the bound is the smallest,
     * because the same jar runs in every reactor. fwtool was not measured (not checked out; its
     * root auto-bump has been switched off since 12.08.2026, so the jar does not reach it), and 3
     * is safe for any reactor: a reactor POM plus at least two modules.
     *
     * <p>Without this bound rule 1 would be green exactly when the lookup breaks — a POM linter
     * that finds no POMs reports "no forbidden pin found". That is the failure mode this whole
     * card is about, and it is the same one card 1274 records for the seven file linters.
     */
    private static final int MINDESTENS_POMS = 3;

    /** {@code ${...}} — a derived version, not a written-down one. */
    private static final Pattern AUSDRUCK = Pattern.compile("\\$\\{([^}]+)}");

    /** {@code 4.1.16}, {@code 33.7.1-jre}, {@code 5.0.9.Final} — number blocks plus optional qualifier. */
    private static final Pattern VERGLEICHBAR = Pattern.compile("^(\\d+(?:\\.\\d+)*)(?:[.\\-_]([A-Za-z].*))?$");

    // ------------------------------------------------------------------ Rule 1

    @Test
    @DisplayName("Kein Modul-POM setzt eine harte <version> auf eine von root verwaltete Abhaengigkeit")
    void keinModulPinAufZentralVerwalteterAbhaengigkeit() {
        Path wurzel = ReactorLayout.repoRoot();
        if (wurzel == null) {
            return; // no reactor at this point -> nothing to check
        }
        Zentralpins zentral = Zentralpins.laden();
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);

        List<String> verstoesse = new ArrayList<>(allowlist.fehler());
        List<Path> poms = pomsDesReaktors(wurzel);
        ReactorLayout.untergrenze(poms, MINDESTENS_POMS, "pom.xml");

        Path wurzelPom = wurzel.resolve("pom.xml");
        for (Path pom : poms) {
            if (pom.equals(wurzelPom)) {
                continue; // the reactor root is rule 2's business
            }
            String rel = ReactorLayout.relativ(pom);
            for (Map.Entry<String, String> pin : hartePins(lies(pom), zentral.verwaltet()).entrySet()) {
                if (!allowlist.erlaubt(rel + "#" + pin.getKey())) {
                    verstoesse.add("%s -> <version>%s</version> auf %s, die root zentral verwaltet (%s)"
                            .formatted(rel, pin.getValue(), pin.getKey(), zentral.verwaltet().get(pin.getKey())));
                }
            }
        }
        melde(verstoesse, """

                Ein Modul-<version> schlaegt dependencyManagement und jede Property. Renovate, der
                root-Auto-Bump und jede zentrale Haertung gehen daran vorbei, OHNE einen Fehler zu
                melden — genau so stand fwtool auf jakarta.faces 4.1.9, unter der CVE-Schwelle.
                Loesung: das <version>-Element ersatzlos streichen; der Wert kommt aus root.
                """);
    }

    // ------------------------------------------------------------------ Rule 2

    @Test
    @DisplayName("Keine Reaktor-Property liegt hinter dem zentralen root-Pin")
    void keinReaktorPinHinterDemZentralenPin() {
        Path wurzel = ReactorLayout.repoRoot();
        if (wurzel == null) {
            return;
        }
        Zentralpins zentral = Zentralpins.laden();
        ArchAllowlist allowlist = ArchAllowlist.fuer(ALLOWLIST_REGEL);

        Map<String, String> lokal = properties(lies(wurzel.resolve("pom.xml")));
        List<String> verstoesse = new ArrayList<>(allowlist.fehler());
        for (Map.Entry<String, String> e : zurueck(lokal, zentral.propertyWerte()).entrySet()) {
            if (!allowlist.erlaubt("pom.xml#" + e.getKey())) {
                verstoesse.add("pom.xml -> %s = %s liegt HINTER dem zentralen root-Pin %s"
                        .formatted(e.getKey(), e.getValue(), zentral.propertyWerte().get(e.getKey())));
            }
        }
        melde(verstoesse, """

                Eine eigene Property, die hinter root liegt, macht die zentrale Haertung
                wirkungslos — sie bleibt stehen, waehrend root weiterzieht, und niemand sieht es.
                Loesung: die Property ersatzlos streichen; der Wert kommt aus dem Eltern-POM.
                Ein Wert VOR root ist erlaubt (bewusstes lokales Vorziehen) und wird nicht gemeldet.
                """);
    }

    // ------------------------------------------------------------------ Positive control

    @Test
    @DisplayName("Positivkontrolle: Scanner und Versionsvergleich erkennen den Pin, den sie erkennen sollen")
    void positivkontrolle(@TempDir Path tmp) throws IOException {
        Map<String, String> verwaltet = Map.of(
                "org.glassfish:jakarta.faces", "${jakarta.faces.version}",
                "ch.plaintext:plaintext-root-common", "${plaintext-root.version}");

        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>fixture</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.glassfish</groupId><artifactId>jakarta.faces</artifactId>
                      <version>4.1.9</version>
                    </dependency>
                    <dependency>
                      <groupId>ch.plaintext</groupId><artifactId>plaintext-root-common</artifactId>
                      <version>${plaintext-root.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>org.jsoup</groupId><artifactId>jsoup</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        Map<String, String> pins = hartePins(lies(pom), verwaltet);
        assertEquals(Set.of("org.glassfish:jakarta.faces"), pins.keySet(),
                () -> "Nur der harte Pin auf eine zentral verwaltete Abhaengigkeit zaehlt: " + pins);
        assertEquals("4.1.9", pins.get("org.glassfish:jakarta.faces"));

        Map<String, String> zentral = Map.of("jakarta.faces.version", "4.1.16", "guava.version", "33.7.1-jre");
        assertEquals(Set.of("jakarta.faces.version"),
                zurueck(Map.of("jakarta.faces.version", "4.1.15", "guava.version", "33.7.1-jre"), zentral).keySet(),
                "4.1.15 liegt hinter 4.1.16, der wertgleiche guava-Eintrag nicht");
        assertTrue(zurueck(Map.of("jakarta.faces.version", "4.1.17"), zentral).isEmpty(),
                "Ein Wert VOR root ist kein Verstoss");
        assertTrue(zurueck(Map.of("jakarta.faces.version", "${anders}"), zentral).isEmpty(),
                "Ein Ausdruck ist nicht vergleichbar");
        assertTrue(zurueck(Map.of("unbekannt.version", "1.0"), zentral).isEmpty(),
                "Was root nicht verwaltet, geht die Regel nichts an");

        assertTrue(vergleiche("4.1.9", "4.1.16") < 0, "4.1.9 < 4.1.16 (nicht lexikografisch)");
        assertEquals(0, vergleiche("4.1", "4.1.0"), "fehlende Bloecke zaehlen als 0, wie in Maven");
        assertEquals(0, vergleiche("33.7.1-jre", "33.7.1-jre"));
        assertEquals(Integer.MIN_VALUE, vergleiche("1.0", "1.0-jre"), "verschiedene Qualifier: nicht vergleichbar");
        assertEquals(Integer.MIN_VALUE, vergleiche("RELEASE", "1.0"), "ohne Zahlenblock: nicht vergleichbar");
    }

    @Test
    @DisplayName("Die zentrale Pin-Liste aus dem Jar ist da und nicht leer")
    void zentraleListeIstLesbar() {
        Zentralpins zentral = Zentralpins.laden();
        assertTrue(zentral.verwaltet().size() > 20,
                () -> "root verwaltet mehr als 20 Abhaengigkeiten; gelesen wurden " + zentral.verwaltet().size()
                        + " — ohne diese Liste waere die Regel gruen, weil sie nichts weiss.");
        assertTrue(zentral.verwaltet().containsKey("org.glassfish:jakarta.faces"),
                "jakarta.faces ist der Anlassfall dieser Regel und muss in der Liste stehen");
        assertTrue(zentral.propertyWerte().containsKey("jakarta.faces.version"),
                "die von dependencyManagement referenzierten Properties muessen aufgeloest sein");
    }

    // ------------------------------------------------------------------ Mechanics

    /** root's central pins: {@code groupId:artifactId -> Versionsausdruck} plus the properties it uses. */
    record Zentralpins(Map<String, String> verwaltet, Map<String, String> propertyWerte) {

        static Zentralpins laden() {
            Document doc;
            try (InputStream in = PlaintextZentralerPinTest.class.getClassLoader().getResourceAsStream(WURZEL_POM)) {
                if (in == null) {
                    return fail("\n\n" + WURZEL_POM + " liegt nicht im Classpath.\n"
                            + "Diese Regel vergleicht gegen die zentralen Pins von plaintext-root; ohne die Liste\n"
                            + "waere sie gruen, ohne etwas geprueft zu haben. Das Jar plaintext-root-archtests\n"
                            + "bringt die Datei mit (maven-resources-plugin, Ausfuehrung 'zentralpin-wurzel-pom').\n");
                }
                doc = parser().parse(in);
            } catch (IOException e) {
                throw new UncheckedIOException(WURZEL_POM + " ist nicht lesbar", e);
            } catch (SAXException e) {
                throw new IllegalStateException(WURZEL_POM + " ist kein wohlgeformtes POM", e);
            }
            Map<String, String> props = properties(doc);
            Map<String, String> verwaltet = new TreeMap<>();
            Map<String, String> genutzt = new TreeMap<>();
            for (Element block : kinder(doc.getDocumentElement(), "dependencyManagement")) {
                for (Element deps : kinder(block, "dependencies")) {
                    for (Element dep : kinder(deps, "dependency")) {
                        String ga = ga(dep);
                        String version = text(dep, "version");
                        if (ga == null || version == null) {
                            continue;
                        }
                        verwaltet.put(ga, version);
                        Matcher m = AUSDRUCK.matcher(version);
                        while (m.find()) {
                            String name = m.group(1);
                            if (props.containsKey(name)) {
                                genutzt.put(name, props.get(name));
                            }
                        }
                    }
                }
            }
            return new Zentralpins(verwaltet, genutzt);
        }
    }

    /**
     * Literal {@code <version>} entries of {@code pom} on dependencies that root manages.
     *
     * @return {@code groupId:artifactId -> Version}, only for versions without {@code ${...}}
     */
    static Map<String, String> hartePins(Document pom, Map<String, String> verwaltet) {
        Map<String, String> treffer = new LinkedHashMap<>();
        for (Element deps : kinder(pom.getDocumentElement(), "dependencies")) {
            for (Element dep : kinder(deps, "dependency")) {
                String ga = ga(dep);
                String version = text(dep, "version");
                if (ga != null && version != null && !AUSDRUCK.matcher(version).find() && verwaltet.containsKey(ga)) {
                    treffer.put(ga, version);
                }
            }
        }
        return treffer;
    }

    /** Those {@code lokal} entries that root also pins and whose value lies behind root's. */
    static Map<String, String> zurueck(Map<String, String> lokal, Map<String, String> zentral) {
        Map<String, String> treffer = new TreeMap<>();
        for (Map.Entry<String, String> e : lokal.entrySet()) {
            String zentralWert = zentral.get(e.getKey());
            if (zentralWert == null) {
                continue;
            }
            int cmp = vergleiche(e.getValue(), zentralWert);
            if (cmp != Integer.MIN_VALUE && cmp < 0) {
                treffer.put(e.getKey(), e.getValue());
            }
        }
        return treffer;
    }

    /**
     * Compares two version strings by number blocks.
     *
     * @return negative/0/positive as usual, or {@link Integer#MIN_VALUE} if the two are not
     *         comparable (expression, no number block, or differing qualifiers) — in that case the
     *         rule keeps quiet instead of guessing.
     */
    static int vergleiche(String a, String b) {
        Matcher ma = VERGLEICHBAR.matcher(a.strip());
        Matcher mb = VERGLEICHBAR.matcher(b.strip());
        if (!ma.matches() || !mb.matches()) {
            return Integer.MIN_VALUE;
        }
        String qa = ma.group(2) == null ? "" : ma.group(2);
        String qb = mb.group(2) == null ? "" : mb.group(2);
        if (!qa.equalsIgnoreCase(qb)) {
            return Integer.MIN_VALUE;
        }
        String[] za = ma.group(1).split("\\.");
        String[] zb = mb.group(1).split("\\.");
        for (int i = 0; i < Math.max(za.length, zb.length); i++) {
            // Missing blocks count as 0, like Maven's ComparableVersion: 4.1 and 4.1.0 are the
            // same pin. Anything else would report a false positive on a shortened notation.
            long va = i < za.length ? Long.parseLong(za[i]) : 0;
            long vb = i < zb.length ? Long.parseLong(zb[i]) : 0;
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    /** {@code <properties>} of a POM as a map (nested elements are ignored — none occur here). */
    static Map<String, String> properties(Document pom) {
        Map<String, String> props = new LinkedHashMap<>();
        for (Element block : kinder(pom.getDocumentElement(), "properties")) {
            NodeList kinder = block.getChildNodes();
            for (int i = 0; i < kinder.getLength(); i++) {
                Node n = kinder.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) {
                    props.put(n.getLocalName() == null ? n.getNodeName() : n.getLocalName(),
                            n.getTextContent() == null ? "" : n.getTextContent().strip());
                }
            }
        }
        return props;
    }

    /** Every {@code pom.xml} below the reactor root (without {@link #SKIP_DIRS}), sorted. */
    static List<Path> pomsDesReaktors(Path wurzel) {
        Set<Path> gefunden = new TreeSet<>();
        sammle(wurzel, gefunden, 0);
        return new ArrayList<>(gefunden);
    }

    private static void sammle(Path dir, Set<Path> gefunden, int tiefe) {
        if (tiefe > MAX_TIEFE) {
            return;
        }
        Path pom = dir.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            gefunden.add(pom);
        }
        try (Stream<Path> kinder = Files.list(dir)) {
            for (Path kind : kinder.filter(Files::isDirectory).sorted().toList()) {
                String name = kind.getFileName().toString();
                if (!SKIP_DIRS.contains(name) && !name.startsWith(".")) {
                    sammle(kind, gefunden, tiefe + 1);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Verzeichnis nicht lesbar: " + dir, e);
        }
    }

    static Document lies(Path pom) {
        try (InputStream in = Files.newInputStream(pom)) {
            return parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("POM nicht lesbar: " + pom, e);
        } catch (SAXException e) {
            throw new IllegalStateException("POM ist nicht wohlgeformt: " + pom, e);
        }
    }

    private static DocumentBuilder parser() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setNamespaceAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML-Parser nicht konfigurierbar", e);
        }
    }

    private static List<Element> kinder(Element eltern, String name) {
        List<Element> treffer = new ArrayList<>();
        NodeList kinder = eltern.getChildNodes();
        for (int i = 0; i < kinder.getLength(); i++) {
            Node n = kinder.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                treffer.add((Element) n);
            }
        }
        return treffer;
    }

    private static String text(Element eltern, String name) {
        List<Element> k = kinder(eltern, name);
        return k.isEmpty() ? null : k.getFirst().getTextContent().strip();
    }

    private static String ga(Element dep) {
        String g = text(dep, "groupId");
        String a = text(dep, "artifactId");
        return (g == null || a == null) ? null : g + ":" + a;
    }

    private static void melde(List<String> verstoesse, String hinweis) {
        if (verstoesse.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder("\n\n=== ZENTRALER VERSIONS-PIN (Karte 1296, Befund 6 aus 1274) ===\n");
        verstoesse.stream().sorted().forEach(v -> msg.append("  ! ").append(v).append("\n"));
        msg.append(hinweis)
           .append("Begruendete Ausnahme: '").append(ALLOWLIST_REGEL).append(" <pfad>#<ziel>  # <Grund>' in ")
           .append(ArchAllowlist.DATEINAME).append(".\n");
        fail(msg.toString());
    }
}
