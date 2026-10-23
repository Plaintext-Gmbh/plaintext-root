/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Karte 1548 (UI 2): neue XHTML ohne {@code style="…"}, ohne eigenes {@code p:growl}, mit
 * {@code _csrf}-Feld in jedem {@code h:form} — der Bestand ist eingefroren.
 *
 * <p><b>Warum eingefroren und nicht verboten.</b> Gemessen am 15.10.2026 hatte root allein 782
 * {@code style="} in 61 XHTML und 27 Dateien mit eigenem Growl; in den Anwendungen ist es mehr.
 * Ein Verbot waere am ersten Tag rot und wuerde abgeschaltet. Stattdessen haelt
 * {@value #BESTAND_DATEI} im Reactor-Wurzelverzeichnis je Datei fest, wie viele Verstoesse sie
 * heute hat. Eine Datei darf nie mehr bekommen, eine Datei, die dort nicht steht, hat null. Wird
 * eine Datei sauberer, meldet der Test die Zeile, die man senken kann — er verlangt es nicht, damit
 * parallele Zweige nicht gegenseitig rot werden.</p>
 *
 * <p><b>Melden oder durchsetzen — {@value #SCHALTER}</b> (dasselbe Muster wie
 * {@link PlaintextInlineJsVertragTest}):</p>
 * <ul>
 *   <li>{@code report} (Vorgabe): Befunde auf {@code System.err}, der Test ist gruen. So kommt
 *       die Regel mit dem root-Bump in app, guild, schuetu und iot an, ohne einen Build zu kippen.</li>
 *   <li>{@code enforce}: jeder Befund ueber dem Bestand macht den Lauf rot. So steht es in
 *       {@code plaintext-root-webapp/pom.xml}.</li>
 *   <li>{@code bestand-schreiben}: schreibt {@value #BESTAND_DATEI} aus dem aktuellen Stand und
 *       ist gruen. Einmal pro Anwendung beim Einschalten:
 *       {@code mvn test -Dtest=PlaintextUiGrundlageLinterTest -Dsurefire.failIfNoSpecifiedTests=false
 *       -Dplaintext.arch.ui-grundlage=bestand-schreiben}, Datei committen, dann {@code enforce}
 *       setzen.</li>
 * </ul>
 */
class PlaintextUiGrundlageLinterTest {

    static final String SCHALTER = "plaintext.arch.ui-grundlage";
    static final String SCHARF = "enforce";
    static final String SCHREIBEN = "bestand-schreiben";
    static final String BESTAND_DATEI = "plaintext-ui-bestand.txt";

    private static final String RESOURCES_SUFFIX = "src/main/resources";

    /** Wie {@link PlaintextInlineJsVertragTest}: die kleinste gemessene Zahl der sechs Reactors. */
    private static final int MINDESTENS_SCANWURZELN = 2;

    @Test
    void neueXhtmlHaltenDieUiGrundlageEin() {
        List<Path> roots = ReactorLayout.sourceRoots(RESOURCES_SUFFIX);
        ReactorLayout.untergrenze(roots, MINDESTENS_SCANWURZELN, RESOURCES_SUFFIX);

        Map<String, UiGrundlageLinter.Zaehlung> ist = new TreeMap<>();
        for (Path root : roots) {
            UiGrundlageLinter.scan(root).forEach((datei, z) -> ist.put(ReactorLayout.relativ(datei), z));
        }

        Path bestandDatei = ReactorLayout.repoRoot().resolve(BESTAND_DATEI);
        String modus = System.getProperty(SCHALTER, "report");
        if (SCHREIBEN.equalsIgnoreCase(modus)) {
            schreibeBestand(bestandDatei, ist);
            System.err.println("UI-Bestand geschrieben: " + bestandDatei + " (" + ist.size() + " Dateien)");
            return;
        }

        Map<String, UiGrundlageLinter.Zaehlung> bestand = liesBestand(bestandDatei);
        List<String> verstoesse = new ArrayList<>();
        List<String> senkbar = new ArrayList<>();
        ist.forEach((pfad, z) -> {
            UiGrundlageLinter.Zaehlung grenze = bestand.getOrDefault(pfad, UiGrundlageLinter.Zaehlung.NULL);
            List<String> ueber = z.ueber(grenze);
            if (!ueber.isEmpty()) {
                verstoesse.add(pfad + ": " + String.join(", ", ueber));
            }
        });
        bestand.forEach((pfad, grenze) -> {
            UiGrundlageLinter.Zaehlung z = ist.getOrDefault(pfad, UiGrundlageLinter.Zaehlung.NULL);
            if (z.unter(grenze)) {
                senkbar.add(zeile(pfad, z) + "   (bisher " + grenze.style() + " " + grenze.growl() + " " + grenze.csrf() + ")");
            }
        });

        if (!senkbar.isEmpty()) {
            System.err.println("UI-Bestand (Karte 1548): diese Dateien sind sauberer geworden, ihre Zeile in "
                    + BESTAND_DATEI + " kann gesenkt werden:\n  " + String.join("\n  ", senkbar));
        }
        if (verstoesse.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder("\n\n=== UI-GRUNDLAGE (Karte 1548) ===\n");
        verstoesse.forEach(v -> msg.append("  ! ").append(v).append('\n'));
        msg.append("""

                Neue Seiten: keine style-Attribute (Tokens und Klassen aus plaintext-layout/css/layout.css,
                Muster unter /styleguide.html), kein eigenes p:growl (Meldungen ueber FacesMessages an das
                eine Growl im Template), in jedem h:form <input type="hidden" name="_csrf" value="#{_csrf.token}"/>.
                Der Bestand in\s""").append(BESTAND_DATEI).append(" darf nicht wachsen.\n");

        if (SCHARF.equalsIgnoreCase(modus)) {
            fail(msg.toString());
        }
        System.err.println(msg + "\nNUR GEMELDET (" + SCHALTER + " != " + SCHARF + ").\n");
    }

    static String zeile(String pfad, UiGrundlageLinter.Zaehlung z) {
        return pfad + "  " + z.style() + " " + z.growl() + " " + z.csrf();
    }

    static Map<String, UiGrundlageLinter.Zaehlung> liesBestand(Path datei) {
        Map<String, UiGrundlageLinter.Zaehlung> bestand = new LinkedHashMap<>();
        if (!Files.isRegularFile(datei)) {
            return bestand;
        }
        try {
            int nr = 0;
            for (String roh : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                nr++;
                String z = roh.strip();
                if (z.isEmpty() || z.startsWith("#")) {
                    continue;
                }
                String[] teile = z.split("\\s+");
                if (teile.length != 4) {
                    throw new IllegalStateException(datei + ":" + nr + ": erwartet '<pfad> <style> <growl> <csrf>', war '" + roh + "'");
                }
                bestand.put(teile[0], new UiGrundlageLinter.Zaehlung(
                        Integer.parseInt(teile[1]), Integer.parseInt(teile[2]), Integer.parseInt(teile[3])));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bestand;
    }

    static void schreibeBestand(Path datei, Map<String, UiGrundlageLinter.Zaehlung> ist) {
        StringBuilder sb = new StringBuilder("""
                # Karte 1548 (UI 2): eingefrorener Bestand — PlaintextUiGrundlageLinterTest (plaintext-root-archtests).
                # Je Datei: style="…"-Attribute, eigene p:growl, h:form ohne _csrf. Eine Datei darf nie mehr
                # bekommen; eine Datei, die hier nicht steht, hat null. Nur senken, nie erhoehen.
                # Neu schreiben: mvn test -Dtest=PlaintextUiGrundlageLinterTest -Dsurefire.failIfNoSpecifiedTests=false \\
                #                -Dplaintext.arch.ui-grundlage=bestand-schreiben
                # pfad  style  growl  csrf
                """);
        ist.forEach((pfad, z) -> sb.append(zeile(pfad, z)).append('\n'));
        try {
            Files.writeString(datei, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void linterZaehltDieDreiRegelnUndUebergehtKommentare(@TempDir Path tmp) throws IOException {
        Path res = Files.createDirectories(tmp.resolve("META-INF/resources/includes"));
        Files.writeString(res.getParent().resolve("alt.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <!-- Beispiel im Kommentar zaehlt nicht: <div style="color:red"/> <p:growl/> -->
                    <h:form id="fm">
                        <p:growl id="g"/>
                        <div style="color:red">x</div>
                        <p:panel style='width:10px' styleClass="a" buttonStyle="b"/>
                    </h:form>
                    <h:form id="zwei">
                        <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
                    </h:form>
                </ui:composition>
                """);
        Files.writeString(res.getParent().resolve("neu.xhtml"), """
                <ui:composition template="/includes/template.xhtml">
                    <h:form id="fm">
                        <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
                        <pt:seitenkopf titel="Neu"/>
                    </h:form>
                </ui:composition>
                """);
        // Das eine Growl des Templates ist erlaubt, sein style-Attribut nicht.
        Files.writeString(res.resolve("template.xhtml"), """
                <html><p:growl id="globalGrowl"/><p:ajaxStatus style="width:32px"/></html>
                """);

        Map<Path, UiGrundlageLinter.Zaehlung> befund = UiGrundlageLinter.scan(tmp);

        assertEquals(new UiGrundlageLinter.Zaehlung(2, 1, 1), befund.get(res.getParent().resolve("alt.xhtml").toAbsolutePath().normalize()),
                "alt.xhtml: zwei style (doppelte und einfache Anfuehrung), ein Growl, ein Formular ohne _csrf: " + befund);
        assertEquals(new UiGrundlageLinter.Zaehlung(1, 0, 0), befund.get(res.resolve("template.xhtml").toAbsolutePath().normalize()),
                "template.xhtml: Growl erlaubt, style nicht: " + befund);
        assertEquals(2, befund.size(), "neu.xhtml ist sauber und darf nicht auftauchen: " + befund);
    }

    @Test
    void bestandHaeltFestUndErlaubtNurWenigerOderGleich(@TempDir Path tmp) {
        Path datei = tmp.resolve(BESTAND_DATEI);
        Map<String, UiGrundlageLinter.Zaehlung> ist = new TreeMap<>(Map.of(
                "m/src/main/resources/META-INF/resources/a.xhtml", new UiGrundlageLinter.Zaehlung(5, 1, 0)));
        schreibeBestand(datei, ist);

        Map<String, UiGrundlageLinter.Zaehlung> gelesen = liesBestand(datei);
        UiGrundlageLinter.Zaehlung grenze = gelesen.get("m/src/main/resources/META-INF/resources/a.xhtml");
        assertEquals(new UiGrundlageLinter.Zaehlung(5, 1, 0), grenze, "Rundreise schreiben/lesen: " + gelesen);

        assertTrue(new UiGrundlageLinter.Zaehlung(5, 1, 0).ueber(grenze).isEmpty(), "gleich viel ist erlaubt");
        assertTrue(new UiGrundlageLinter.Zaehlung(3, 0, 0).ueber(grenze).isEmpty(), "weniger ist erlaubt");
        assertTrue(new UiGrundlageLinter.Zaehlung(3, 0, 0).unter(grenze), "weniger wird als senkbar gemeldet");
        assertEquals(1, new UiGrundlageLinter.Zaehlung(6, 1, 0).ueber(grenze).size(), "ein style mehr ist ein Verstoss");
        assertEquals(1, new UiGrundlageLinter.Zaehlung(0, 0, 1).ueber(UiGrundlageLinter.Zaehlung.NULL).size(),
                "eine Datei ohne Eintrag hat null — ein Formular ohne _csrf ist ein Verstoss");
    }
}
