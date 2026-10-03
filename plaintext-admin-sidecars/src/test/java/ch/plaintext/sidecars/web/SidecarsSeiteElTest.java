/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Karte 1400, PROD-Fehler 02.10.2026: sidecars.xhtml las {@code #{z.ampel}} auf einem Record, der
 * EL-Resolver für Records kennt aber nur die Komponenten. Kein Unit-Test rendert die Seite, deshalb
 * prüft dieser Test die Ausdrücke gegen die Klassen: jede Property-Lesung auf {@code z} braucht
 * einen Getter, Record-Variablen ({@code f}, {@code t}) nur mit Methodensyntax.
 */
class SidecarsSeiteElTest {

    static String seite() throws Exception {
        try (InputStream in = SidecarsSeiteElTest.class.getResourceAsStream("/META-INF/resources/sidecars.xhtml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Jede Property auf z (Zeile) hat einen lesbaren Getter")
    void zeileHatGetter() throws Exception {
        Set<String> lesbar = Arrays.stream(Introspector.getBeanInfo(SidecarsBackingBean.Zeile.class).getPropertyDescriptors())
                .filter(p -> p.getReadMethod() != null).map(PropertyDescriptor::getName).collect(Collectors.toSet());
        Matcher m = Pattern.compile("\\bz\\.([a-zA-Z]+)\\b(?!\\s*\\()").matcher(seite());
        List<String> fehlend = new ArrayList<>();
        int gefunden = 0;
        while (m.find()) {
            gefunden++;
            if (!lesbar.contains(m.group(1))) {
                fehlend.add(m.group(1));
            }
        }
        assertThat(gefunden).as("Positivkontrolle: die Seite liest Properties von z").isGreaterThan(5);
        assertThat(fehlend).isEmpty();
        assertThat(SidecarsBackingBean.Zeile.class.isRecord()).as("Zeile darf kein Record sein").isFalse();
    }

    /** Alle EL-Ausdrücke {@code #{...}} der Seite. */
    static List<String> ausdruecke() throws Exception {
        Matcher m = Pattern.compile("#\\{[^}]*}").matcher(seite());
        List<String> alle = new ArrayList<>();
        while (m.find()) {
            alle.add(m.group());
        }
        return alle;
    }

    static Set<String> lesbar(Class<?> klasse) throws Exception {
        return Arrays.stream(Introspector.getBeanInfo(klasse).getPropertyDescriptors())
                .filter(p -> p.getReadMethod() != null).map(PropertyDescriptor::getName).collect(Collectors.toSet());
    }

    /** Property-Lesungen {@code <praefix>.name} (ohne Methodenaufruf) in allen EL-Ausdrücken. */
    static List<String> properties(String praefix) throws Exception {
        Pattern p = Pattern.compile("(?<![\\w.])" + Pattern.quote(praefix) + "\\.([a-zA-Z]+)\\b(?!\\s*\\()");
        List<String> gefunden = new ArrayList<>();
        for (String a : ausdruecke()) {
            Matcher m = p.matcher(a);
            while (m.find()) {
                gefunden.add(m.group(1));
            }
        }
        return gefunden;
    }

    @Test
    @DisplayName("Karte 1413: jede Property auf z.sidecar hat einen Getter an Sidecar")
    void sidecarHatGetter() throws Exception {
        List<String> gelesen = properties("z.sidecar");
        assertThat(gelesen).as("Positivkontrolle: die Tabelle liest Sidecar-Felder").contains("name", "version", "authZustand");
        assertThat(gelesen).isSubsetOf(lesbar(ch.plaintext.sidecars.entity.Sidecar.class));
    }

    @Test
    @DisplayName("Karte 1413: jede Property auf a (Ablage) hat einen Getter an SpeicherAblage")
    void ablageHatGetter() throws Exception {
        List<String> gelesen = properties("a");
        assertThat(gelesen).as("Positivkontrolle: die Ablagen-Tabelle liest Felder").contains("name", "ok", "letztePruefung");
        assertThat(gelesen).isSubsetOf(lesbar(ch.plaintext.sidecars.entity.SpeicherAblage.class));
    }

    @Test
    @DisplayName("Karte 1413: jede Property auf sidecarsBean hat einen Getter, jede Methode existiert")
    void beanHatGetterUndMethoden() throws Exception {
        List<String> gelesen = properties("sidecarsBean");
        assertThat(gelesen).as("Positivkontrolle: Kennzahlen und Dialogzustand")
                .contains("anzahlOk", "anzahlEingeschraenkt", "anzahlFehler", "ablageBestehend", "zeilen", "speicherAblagen");
        assertThat(gelesen).isSubsetOf(lesbar(SidecarsBackingBean.class));

        Set<String> methoden = Arrays.stream(SidecarsBackingBean.class.getMethods()).map(java.lang.reflect.Method::getName)
                .collect(Collectors.toSet());
        Matcher m = Pattern.compile("sidecarsBean\\.([a-zA-Z]+)\\s*\\(").matcher(String.join(" ", ausdruecke()));
        List<String> aufgerufen = new ArrayList<>();
        while (m.find()) {
            aufgerufen.add(m.group(1));
        }
        assertThat(aufgerufen).as("Positivkontrolle: Aktionen der Seite")
                .contains("ergaenzenVorbereiten", "registrieren", "ablageNeu", "ablageBearbeiten", "ablageSpeichern", "zeit");
        assertThat(aufgerufen).isSubsetOf(methoden);
    }

    @Test
    @DisplayName("Karte 1413: Reiter, Tabellen mit Aufklappen, Dialoge und CSRF sind da; Passwörter nie wieder angezeigt")
    void aufbau() throws Exception {
        String s = seite();
        assertThat(s).contains("<p:tabView id=\"reiter\">", "<p:dataTable id=\"sidecars\"", "<p:dataTable id=\"ablagen\"",
                "expandedRow=\"#{z.aufgeklappt}\"", "<p:dialog id=\"dlgErgaenzen\"", "<p:dialog id=\"dlgAblage\"",
                "<h:form id=\"fm\">", "name=\"_csrf\" value=\"#{_csrf.token}\"");
        assertThat(Pattern.compile("<p:rowToggler/>").matcher(s).results().count()).isEqualTo(2);
        assertThat(Pattern.compile("<h:form").matcher(s).results().count()).as("Dialoge sitzen in fm, keine zweite Form").isEqualTo(1);
        Matcher pw = Pattern.compile("<p:password[^>]*>").matcher(s);
        int passwoerter = 0;
        while (pw.find()) {
            passwoerter++;
            assertThat(pw.group()).contains("redisplay=\"false\"");
        }
        assertThat(passwoerter).isEqualTo(2);
        assertThat(s).as("Farben über Theme-Variablen").doesNotContain("#fff", "#e2e8f0", "#64748b", "#c62828");
    }

    @Test
    @DisplayName("Records (Faehigkeit f, Teil t) nur mit Methodensyntax")
    void recordsNurMitMethoden() throws Exception {
        Matcher m = Pattern.compile("#\\{[^}]*\\b([ft])\\.([a-zA-Z]+)\\b(?!\\s*\\()").matcher(seite());
        List<String> treffer = new ArrayList<>();
        while (m.find()) {
            treffer.add(m.group(1) + "." + m.group(2));
        }
        assertThat(treffer).isEmpty();
    }
}
