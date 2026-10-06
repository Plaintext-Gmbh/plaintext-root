/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Karte 1423: Der Modul-Export ({@code {module, version, tables}}) ist ein Vertrag (Datei, die wieder
 * importiert wird). Vertragsdatei vom Stand mit Jackson 2.
 */
class ModuleDataExportVertragTest {

    /** Eine Entity wie im Export: SuperModel-Felder plus die ueblichen Typen. */
    public static class Probe extends ch.plaintext.framework.SuperModel {
        public enum Art { EINFACH, DOPPELT }
        private String name = "Probe \"mit\" Zeichen äöü";
        private java.math.BigDecimal betrag = new java.math.BigDecimal("1234.50");
        private java.time.LocalDate tag = java.time.LocalDate.of(2026, 10, 6);
        private java.time.LocalDateTime zeit = java.time.LocalDateTime.of(2026, 10, 6, 7, 15, 30);
        private java.time.Instant moment = java.time.Instant.parse("2026-10-06T05:15:30Z");
        private Art art = Art.DOPPELT;
        private Boolean aktiv = Boolean.TRUE;
        private Integer leer = null;
        private java.util.Map<String, Integer> zahlen = new java.util.LinkedHashMap<>(java.util.Map.of("eins", 1));

        public Probe() {
            setId(42L);
            setMandat("plaintext");
            setCreatedBy("anna@example.ch");
            setCreatedDate(new java.util.Date(1791259200123L));
            setTags(new java.util.ArrayList<>(java.util.List.of("a", "b")));
        }
    }

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
    @DisplayName("Modul-Export: byte-gleich zum Stand mit Jackson 2")
    void export() throws Exception {
        Map<String, Object> huelle = new LinkedHashMap<>();
        huelle.put("module", "probe");
        huelle.put("version", "1.0");
        huelle.put("tables", Map.of("Probe", List.of(new Probe())));
        vergleiche("modul-export.json", ModuleDataService.exportMapper().writerWithDefaultPrettyPrinter().writeValueAsString(huelle));
    }
}
