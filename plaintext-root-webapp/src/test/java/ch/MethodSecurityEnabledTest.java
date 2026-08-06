/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch;

import org.junit.jupiter.api.Test;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Karte 546: hält fest, dass Spring-Method-Security in root eingeschaltet bleibt.
 *
 * <p>Ohne {@code @EnableMethodSecurity} wird jedes {@code @PreAuthorize} <b>still ignoriert</b> —
 * die Annotation steht im Code, sieht nach einer Schranke aus und lässt jeden durch. Dass das kein
 * theoretisches Risiko ist, belegt der Bestand selbst: Zwei Stellen haben genau deshalb im
 * Methodenrumpf geprüft statt zu annotieren, jeweils mit schriftlicher Begründung
 * ({@code PlaintextSecurityImpl:501} „wuerde also still wirkungslos annotiert",
 * {@code I18nExportController:49} „die Annotation waere eine stille Attrappe").
 *
 * <p>Mit Karte 546 rüsten app, guild und root ihre MCP-Werkzeuge auf {@code @PreAuthorize}-Gates
 * um, abgesichert durch {@link ch.plaintext.arch.PlaintextMcpScopeVertragTest}. Fällt dieser
 * Schalter wieder weg, sind sie alle auf einen Schlag wirkungslos — und der Vertragstest bliebe
 * grün, weil er die <em>Annotation</em> prüft und nicht ihre <em>Wirkung</em>. Genau diese Lücke
 * schliesst dieser Test. Vorbild ist schuetus gleichnamiger Test (Audit-Befund C-4); app und guild
 * haben ihn seit {@code plaintext-app#566} bzw. {@code plaintext-guild#112}.
 *
 * <p>Bootet keine DB.
 */
class MethodSecurityEnabledTest {

    @Test
    void mainKlasseAktiviertMethodSecurity() {
        assertNotNull(
                RootBootApplication.class.getAnnotation(EnableMethodSecurity.class),
                "@EnableMethodSecurity muss aktiv sein, sonst werden @PreAuthorize-Gates still ignoriert");
    }
}
