/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.webhooks.service;

import ch.plaintext.webhooks.PlaintextDomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Karte 1423: Der Webhook-Rumpf geht an fremde Empfaenger, sein Format ist ein Vertrag. Vertragsdatei vom
 * Stand mit Jackson 2.
 */
class WebhookPayloadVertragTest {

    /**
     * Vergleich gegen die Vertragsdatei. Fehlt sie, wird die heutige Ausgabe geschrieben und der Test
     * schlaegt fehl: die Datei muss einmal mit dem ALTEN Stand (Jackson 2) erzeugt und eingecheckt sein.
     */
    static void vergleiche(String datei, String ist) throws java.io.IOException {
        java.nio.file.Path p = java.nio.file.Path.of("src/test/resources/json-vertrag", datei);
        if (!java.nio.file.Files.exists(p)) {
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, ist);
            org.junit.jupiter.api.Assertions.fail("Vertragsdatei " + datei + " neu geschrieben - pruefen und einchecken");
        }
        org.junit.jupiter.api.Assertions.assertEquals(java.nio.file.Files.readString(p), ist,
                "Format von " + datei + " weicht vom Stand mit Jackson 2 ab");
    }

    @Test
    @DisplayName("Webhook-Rumpf: byte-gleich zum Stand mit Jackson 2")
    void rumpf() throws Exception {
        Map<String, Object> nutzlast = new LinkedHashMap<>();
        nutzlast.put("betrag", new java.math.BigDecimal("12.50"));
        nutzlast.put("anzahl", 3);
        nutzlast.put("bezahlt", true);
        nutzlast.put("text", "Rechnung \"PL-1\" äöü");
        nutzlast.put("leer", null);
        nutzlast.put("liste", List.of("a", "b"));
        nutzlast.put("datum", java.time.LocalDate.of(2026, 10, 6).toString());
        PlaintextDomainEvent e = new PlaintextDomainEvent("rechnung.bezahlt", "Rechnung", "17", "plaintext", nutzlast);
        vergleiche("webhook-rumpf.json", WebhookDispatchService.payloadJson(new ObjectMapper(), e));
    }
}
