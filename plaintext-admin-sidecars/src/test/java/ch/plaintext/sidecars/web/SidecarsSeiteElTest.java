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
