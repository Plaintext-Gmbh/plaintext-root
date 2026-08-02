/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registry und Link-Bau (Karte 345). */
class DeepLinkServiceImplTest {

    private record Ziel(String type, String view, String param) implements DeepLinkTarget {
        @Override
        public String getType() {
            return type;
        }

        @Override
        public String getView() {
            return view;
        }

        @Override
        public String getLabel() {
            return type;
        }

        @Override
        public String getParamName() {
            return param;
        }

        @Override
        public boolean isAccessible(String mandat, String id) {
            return true;
        }
    }

    private static DeepLinkService service(String baseUrl, DeepLinkTarget... ziele) {
        return new DeepLinkServiceImpl(List.of(ziele), baseUrl);
    }

    @Test
    @DisplayName("Absoluter Link nutzt die konfigurierte Basis-URL")
    void absoluterLink() {
        DeepLinkService s = service("https://example.com/", new Ziel("auszahlung", "auszahlungen.html", "id"));

        assertEquals("https://example.com/deeplink?type=auszahlung&mandat=alpha&id=42",
                s.buildAbsoluteLink("auszahlung", "ALPHA", "42"));
    }

    @Test
    @DisplayName("Mehrfache Schraegstriche am Ende der Basis-URL werden alle entfernt (Karte 458)")
    void mehrfacheEndSchraegstriche() {
        // Karte 458 (java:S5852): Das frühere replaceAll("/+$", "") wurde durch eine lineare
        // Schleife ersetzt. Dieser Test hält fest, dass sich das Verhalten dabei nicht ändert —
        // und deckt gerade den Fall ab, der den Regex ins Backtracking getrieben hätte.
        DeepLinkService s = service("https://example.com/////", new Ziel("auszahlung", "auszahlungen.html", "id"));

        assertEquals("https://example.com/deeplink?type=auszahlung&mandat=alpha&id=42",
                s.buildAbsoluteLink("auszahlung", "ALPHA", "42"));
    }

    @Test
    @DisplayName("Basis-URL ohne Schraegstrich bleibt unveraendert")
    void ohneEndSchraegstrich() {
        DeepLinkService s = service("https://example.com", new Ziel("auszahlung", "auszahlungen.html", "id"));

        assertEquals("https://example.com/deeplink?type=auszahlung&mandat=alpha&id=42",
                s.buildAbsoluteLink("auszahlung", "ALPHA", "42"));
    }

    @Test
    @DisplayName("Relativer Link bleibt context-relativ")
    void relativerLink() {
        DeepLinkService s = service("https://example.com", new Ziel("rechnung", "rechnungen.html", "id"));

        assertEquals("/deeplink?type=rechnung&mandat=beta&id=7",
                s.buildRelativeLink("rechnung", "beta", "7"));
    }

    @Test
    @DisplayName("Link auf einen nicht registrierten Typ laesst sich gar nicht erst bauen")
    void unbekannterTyp() {
        DeepLinkService s = service("", new Ziel("auszahlung", "auszahlungen.html", "id"));

        assertThrows(IllegalArgumentException.class, () -> s.buildAbsoluteLink("fremd", "alpha", "1"));
    }

    @Test
    @DisplayName("Unsaubere Id/Mandat werden beim Bauen abgelehnt — nicht erst beim Oeffnen")
    void unsaubereParameter() {
        DeepLinkService s = service("", new Ziel("auszahlung", "auszahlungen.html", "id"));

        assertThrows(IllegalArgumentException.class, () -> s.buildAbsoluteLink("auszahlung", "alpha", "1&x=2"));
        assertThrows(IllegalArgumentException.class, () -> s.buildAbsoluteLink("auszahlung", "//evil", "1"));
    }

    @Test
    @DisplayName("Ziel mit unsauberem Typ laesst die Anwendung beim Start scheitern")
    void ungueltigerTypFaelltBeimStartAuf() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service("", new Ziel("Auszahlung Neu!", "auszahlungen.html", "id")));
        assertTrue(e.getMessage().contains("ungueltigen type"));
    }

    @Test
    @DisplayName("Ziel mit absoluter oder traversierender View wird beim Start abgelehnt")
    void ungueltigeViewFaelltBeimStartAuf() {
        assertThrows(IllegalStateException.class,
                () -> service("", new Ziel("a", "/etc/passwd", "id")));
        assertThrows(IllegalStateException.class,
                () -> service("", new Ziel("a", "../geheim.html", "id")));
        assertThrows(IllegalStateException.class,
                () -> service("", new Ziel("a", "https://example.com/", "id")));
    }

    @Test
    @DisplayName("Doppelt registrierter Typ faellt beim Start auf")
    void doppelterTyp() {
        assertThrows(IllegalStateException.class, () -> service("",
                new Ziel("auszahlung", "a.html", "id"),
                new Ziel("auszahlung", "b.html", "id")));
    }
}
