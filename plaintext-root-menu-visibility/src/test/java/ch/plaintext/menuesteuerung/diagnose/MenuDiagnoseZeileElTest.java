/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.diagnose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Order from Daniel, 29.08.2026: {@code menudiagnose.html} stayed empty — {@code MenuDiagnoseZeile}
 * is a record, and the {@code RecordELResolver} resolves {@code #{z.modulKeysText}} exclusively
 * through a parameterless method <i>named</i> {@code modulKeysText()}; it does not know about
 * {@code getModulKeysText()}. This test reads every {@code #{z.…}} reference from the page and
 * demands exactly such a method on the record.
 */
@DisplayName("Menue-Diagnose: jede EL-Referenz der Seite hat eine Record-Methode")
class MenuDiagnoseZeileElTest {

    private static final Pattern REFERENZ = Pattern.compile("#\\{z\\.([A-Za-z0-9_]+)\\b");

    @Test
    void jedeReferenzIstAlsParameterloseMethodeVorhanden() throws IOException {
        String seite;
        try (InputStream in = getClass().getResourceAsStream("/META-INF/resources/menudiagnose.xhtml")) {
            assertTrue(in != null, "menudiagnose.xhtml nicht auf dem Test-Classpath");
            seite = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        TreeSet<String> referenzen = new TreeSet<>();
        Matcher m = REFERENZ.matcher(seite);
        while (m.find()) {
            referenzen.add(m.group(1));
        }
        assertFalse(referenzen.isEmpty(), "keine #{z.…}-Referenzen gefunden — Seite geaendert?");

        List<String> fehlend = new ArrayList<>();
        for (String name : referenzen) {
            try {
                Method methode = MenuDiagnoseZeile.class.getMethod(name);
                if (!Modifier.isPublic(methode.getModifiers()) || methode.getReturnType() == void.class) {
                    fehlend.add(name);
                }
            } catch (NoSuchMethodException e) {
                fehlend.add(name);
            }
        }
        assertTrue(fehlend.isEmpty(), "RecordELResolver findet keine Methode fuer: " + fehlend
                + " — am Record muss eine public, parameterlose Methode mit genau diesem Namen stehen"
                + " (kein get-Praefix).");
    }
}
