/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Karte 940: die {@code <parent>}-Version und die Property {@code plaintext-root.version} in der
 * Wurzel-{@code pom.xml} eines Consumers muessen dieselbe root-Version nennen — und jede weitere
 * root-Versionsangabe ({@code plaintext-root-interfaces.version}, {@code plaintext.version}) darf
 * keine eigene Zahl sein.
 *
 * <p><b>Karte 1298: eine Fassung statt vier.</b> Bis zum 22.09.2026 stand dieser Test in app,
 * guild, schuetu und iot als Kopie — und die Kopien waren auseinandergelaufen: app und schuetu
 * pruefen nur Parent gegen Property (eine Testmethode), guild zusaetzlich den Interfaces-Pin, iot
 * zusaetzlich {@code plaintext.version}. In root und fwtool fehlte er ganz. Diese Fassung vereinigt
 * alle drei Pruefungen; sie laufen dort, wo die jeweilige Angabe in der Wurzel-pom steht:
 * <ul>
 *   <li><b>Parent == plaintext-root.version</b> — in jedem Consumer. Die <b>Property</b> bestimmt,
 *       welche root-Modul-Jars hereinkommen, und faellt sofort auf, wenn sie falsch ist. Die
 *       <b>Parent-Version</b> bestimmt, welches {@code dependencyManagement} der Consumer
 *       <i>erbt</i> — darin die zentralen Security-Pins ({@code httpclient5}, {@code httpcore5},
 *       {@code jackson-bom}). Ein zu alter Parent bricht keinen Compilelauf und keinen Test, er
 *       senkt nur still eine Version, die jemand aus Sicherheitsgruenden angehoben hatte. Karte
 *       928: app PR #663, guild PR #180/#182 — httpclient5 fiel auf die fuer CVE-2026-71290
 *       verwundbare 5.6.1 zurueck, guild lieferte zwei Releases so aus.</li>
 *   <li><b>{@code plaintext-root-interfaces.version}</b> (app, guild) — abgeleitet
 *       ({@code ${plaintext-root.version}}) oder exakt dieselbe Zahl. Karte 620: root-common
 *       1.534.0 suchte {@code ch.plaintext.store.StoreBacked}, das stehengebliebene interfaces
 *       1.524.0 hatte sie nicht; der Start brach ab. In guild lief der Pin sechsmal davon.</li>
 *   <li><b>{@code plaintext.version}</b> — nie eine ausgeschriebene Zahl, nur eine Ableitung. Welche,
 *       ist <b>fachlich verschieden</b> und wird hier nicht eingeebnet: in app und root meint sie die
 *       eigene Version ({@code ${project.version}}), in iot die von root verwalteten internen
 *       Module ({@code ${plaintext-root.version}}, sonst zeigten plaintext-root-* und plaintext-admin-*
 *       auf iots Version). Die gemeinsame Regel ist: keine zweite Zahl, die beim naechsten Bump von
 *       Hand nachgezogen werden muesste. iots strengere Bindung an genau
 *       {@code ${plaintext-root.version}} haelt {@code IotPlaintextVersionAbleitungTest} in iot fest.</li>
 * </ul>
 *
 * <p><b>In root selbst</b> ist die Wurzel-pom {@code plaintext-root-parent}; sie hat keinen
 * plaintext-root-parent als Parent. Dort ist die Parent-Pruefung gegenstandslos und wird
 * uebersprungen, die beiden anderen laufen.
 *
 * <p><b>Was dieser Test NICHT prueft:</b> ob die genannte root-Version die <i>richtige</i> ist —
 * nur, dass alle Angaben dieselbe nennen. {@code plaintext-app.version} (guilds Pin auf die
 * konsumierten app-Module) folgt einer eigenen Kadenz und bleibt aussen vor.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@DisplayName("Wurzel-pom: Parent, plaintext-root.version und abgeleitete Pins nennen dieselbe root-Version")
class WurzelVersionVertragTest {

    /** {@code <version>} im {@code <parent>}-Block der Wurzel-pom, nur wenn der Parent plaintext-root-parent ist. */
    private static final Pattern PARENT_VERSION = Pattern.compile(
            "<parent>.*?<artifactId>\\s*plaintext-root-parent\\s*</artifactId>.*?"
                    + "<version>\\s*([^<\\s]+)\\s*</version>.*?</parent>",
            Pattern.DOTALL);

    /** Die Property, die die root-Modul-Jars bestimmt. */
    private static final Pattern ROOT_PROPERTY = Pattern.compile(
            "<plaintext-root\\.version>\\s*([^<\\s]+)\\s*</plaintext-root\\.version>");

    /** Der separat gefuehrte Pin auf die API-Basis root-interfaces (app, guild). */
    private static final Pattern INTERFACES_PIN = Pattern.compile(
            "<plaintext-root-interfaces\\.version>\\s*([^<]+?)\\s*</plaintext-root-interfaces\\.version>");

    /** {@code plaintext.version} (root, app: eigene Version; iot: root-Version). */
    private static final Pattern PLAINTEXT_PROPERTY = Pattern.compile(
            "<plaintext\\.version>\\s*([^<]+?)\\s*</plaintext\\.version>");

    /** Der Platzhalter, der eine Angabe an die root-Version bindet (Karte 620). */
    private static final String ABLEITUNG = "${plaintext-root.version}";

    /** Die zulaessigen Werte fuer {@code plaintext.version}: nur Ableitungen, nie eine Zahl. */
    private static final Set<String> PLAINTEXT_VERSION_ERLAUBT = Set.of("${project.version}", ABLEITUNG);

    private static String wurzelPom;

    @BeforeAll
    static void liesWurzelPom() {
        Path root = ReactorLayout.repoRoot();
        assertNotNull(root, "Keine Wurzel-pom mit <modules> oberhalb von " + ReactorLayout.start()
                + " gefunden — der Test wuesste nicht, welche Datei er prueft.");
        // Kommentare entfernen: die Wurzel-poms erklaeren die Versionen ausfuehrlich und zitieren
        // dabei die Tags — ein Muster, das im Kommentar anschlaegt, prueft die falsche Stelle.
        wurzelPom = lies(root.resolve("pom.xml")).replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @DisplayName("Parent-Version == plaintext-root.version (sonst greifen die Security-Pins nicht)")
    void parentUndPropertyNennenDieselbeRootVersion() {
        String befund = parentBefund(wurzelPom);
        assertNull(befund, befund);
    }

    @Test
    @DisplayName("Interfaces-Pin bleibt an plaintext-root.version gebunden (Karte 620)")
    void interfacesPinFolgtDerRootVersion() {
        String befund = interfacesBefund(wurzelPom);
        assertNull(befund, befund);
    }

    @Test
    @DisplayName("plaintext.version ist abgeleitet, keine zweite Zahl")
    void plaintextVersionIstAbgeleitet() {
        String befund = plaintextVersionBefund(wurzelPom);
        assertNull(befund, befund);
    }

    /**
     * Positivkontrolle: jeder der drei Befunde schlaegt an einer kuenstlich verletzten pom an, und
     * eine saubere pom bleibt gruen. Ohne sie waere ein Waechter, dessen Muster nie mehr greifen,
     * von einem heilen nicht zu unterscheiden.
     */
    @Test
    @DisplayName("Positivkontrolle: Drift in Parent, Interfaces-Pin und plaintext.version wird ROT")
    void positivkontrolle() {
        String sauber = pom("1.700.0", "1.700.0", ABLEITUNG, "${project.version}");
        assertNull(parentBefund(sauber), "Saubere pom darf keinen Parent-Befund erzeugen.");
        assertNull(interfacesBefund(sauber), "Saubere pom darf keinen Interfaces-Befund erzeugen.");
        assertNull(plaintextVersionBefund(sauber), "Saubere pom darf keinen plaintext.version-Befund erzeugen.");

        String parentDrift = pom("1.591.0", "1.597.0", ABLEITUNG, "${project.version}");
        assertNotNull(parentBefund(parentDrift), "Parent 1.591.0 gegen Property 1.597.0 muss auffallen (Karte 928).");

        assertNotNull(interfacesBefund(pom("1.534.0", "1.534.0", "1.524.0", "${project.version}")),
                "Ein Interfaces-Pin 1.524.0 neben root 1.534.0 muss auffallen (Karte 620).");
        assertNull(interfacesBefund(pom("1.534.0", "1.534.0", "1.534.0", "${project.version}")),
                "Eine ausgeschriebene, aber gleiche Zahl wird geduldet.");

        assertNotNull(plaintextVersionBefund(pom("1.700.0", "1.700.0", ABLEITUNG, "1.699.0")),
                "plaintext.version als eigene Zahl muss auffallen.");
        assertNull(plaintextVersionBefund(pom("1.700.0", "1.700.0", ABLEITUNG, ABLEITUNG)),
                "plaintext.version = ${plaintext-root.version} (iot) ist zulaessig.");
    }

    // ------------------------------------------------------------------------------------------

    static String parentBefund(String pom) {
        Matcher parent = PARENT_VERSION.matcher(pom);
        if (!parent.find()) {
            // Wurzel-pom erbt nicht von plaintext-root-parent: das ist root selbst. Dass ein
            // Consumer seinen Parent verliert, faengt die Property-Pruefung nicht — dafuer muesste
            // er den Bau ueberleben, und ohne root-parent fehlt ihm das ganze Management.
            return null;
        }
        Matcher property = ROOT_PROPERTY.matcher(pom);
        if (!property.find()) {
            return "Die Wurzel-pom erbt von plaintext-root-parent " + parent.group(1) + ", fuehrt aber "
                    + "keine Property plaintext-root.version — wurde die Datei umstrukturiert? Dann "
                    + "diesen Test nachziehen.";
        }
        if (parent.group(1).equals(property.group(1))) {
            return null;
        }
        return """
                Die Wurzel-pom nennt zwei verschiedene root-Versionen:
                  <parent>-Version          = %s   -> liefert das geerbte dependencyManagement \
                (httpclient5, httpcore5, jackson-bom)
                  plaintext-root.version    = %s   -> liefert die root-Modul-Jars

                Damit greifen die Security-Pins aus root %s moeglicherweise NICHT — das Management \
                kommt von %s. Beide Angaben auf dieselbe Version setzen, am besten mit \
                plaintext-scripts/ci/root-autobump.sh apply statt von Hand.
                Praezedenzfall: Karte 928 (app PR #663, guild PR #180/#182) — httpclient5 fiel \
                dadurch auf die fuer CVE-2026-71290 verwundbare 5.6.1 zurueck.\
                """.formatted(parent.group(1), property.group(1), property.group(1), parent.group(1));
    }

    static String interfacesBefund(String pom) {
        Matcher pin = INTERFACES_PIN.matcher(pom);
        if (!pin.find() || ABLEITUNG.equals(pin.group(1))) {
            return null; // kein eigener Pin, oder abgeleitet — kann per Definition nicht davonlaufen
        }
        Matcher property = ROOT_PROPERTY.matcher(pom);
        String rootVersion = property.find() ? property.group(1) : "(keine Property plaintext-root.version)";
        if (rootVersion.equals(pin.group(1))) {
            return null;
        }
        return """
                plaintext-root-interfaces.version steht auf "%s", plaintext-root.version auf "%s".

                root-common und root-interfaces sind ein Paar. Laufen sie auseinander, sucht die eine \
                Seite Klassen, die die andere nicht hat — und das faellt erst beim START auf. Genau so \
                brach guild unter Karte 620 ab (root-common 1.534.0 suchte StoreBacked, interfaces \
                1.524.0 hatte sie nicht). Den Pin zurueck auf %s setzen.\
                """.formatted(pin.group(1), rootVersion, ABLEITUNG);
    }

    static String plaintextVersionBefund(String pom) {
        Matcher m = PLAINTEXT_PROPERTY.matcher(pom);
        if (!m.find() || PLAINTEXT_VERSION_ERLAUBT.contains(m.group(1))) {
            return null;
        }
        return """
                plaintext.version steht auf "%s" — eine ausgeschriebene Zahl.

                Diese Property ist in jedem Repo eine Ableitung: ${project.version} (eigene Version, \
                root/app) oder ${plaintext-root.version} (iot: die von root verwalteten internen \
                Module). Als eigene Zahl ist sie eine weitere Stelle, die beim naechsten Bump von \
                Hand nachgezogen werden muesste — und genau solche Zahlen bleiben stehen (Karte 620).\
                """.formatted(m.group(1));
    }

    private static String pom(String parent, String rootVersion, String interfacesPin, String plaintextVersion) {
        return """
                <project>
                  <parent>
                    <groupId>ch.plaintext</groupId>
                    <artifactId>plaintext-root-parent</artifactId>
                    <version>%s</version>
                  </parent>
                  <artifactId>beispiel-parent</artifactId>
                  <properties>
                    <plaintext-root.version>%s</plaintext-root.version>
                    <plaintext-root-interfaces.version>%s</plaintext-root-interfaces.version>
                    <plaintext.version>%s</plaintext.version>
                  </properties>
                  <modules><module>x</module></modules>
                </project>
                """.formatted(parent, rootVersion, interfacesPin, plaintextVersion);
    }

    private static String lies(Path pom) {
        try {
            return Files.readString(pom, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Wurzel-pom " + pom + " nicht lesbar", e);
        }
    }
}
