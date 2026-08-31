/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import ch.plaintext.PlaintextSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Abuse cases of the deep-link mechanism (card 345). The tests are deliberately named after the
 * attack they are meant to prevent — not after the method they call.
 */
class DeepLinkResolverTest {

    private PlaintextSecurity security;
    private DeepLinkService service;
    private DeepLinkResolver resolver;
    private TestZiel ziel;

    /** Minimal target module: knows exactly one record, and only in the tenant "alpha". */
    private static class TestZiel implements DeepLinkTarget {
        boolean gefragt = false;
        String gefragtesMandat;
        boolean wirftFehler = false;

        @Override
        public String getType() {
            return "auszahlung";
        }

        @Override
        public String getView() {
            return "auszahlungen.html";
        }

        @Override
        public String getLabel() {
            return "Auszahlung";
        }

        @Override
        public boolean isAccessible(String mandat, String id) {
            gefragt = true;
            gefragtesMandat = mandat;
            if (wirftFehler) {
                throw new IllegalStateException("Datenbank weg");
            }
            return "alpha".equals(mandat) && "42".equals(id);
        }
    }

    @BeforeEach
    void setUp() {
        ziel = new TestZiel();
        service = new DeepLinkServiceImpl(List.of(ziel), "https://example.com");
        security = Mockito.mock(PlaintextSecurity.class);
        when(security.getUser()).thenReturn("benutzer@example.com");
        when(security.getMandat()).thenReturn("alpha");
        when(security.getAllowedMandate()).thenReturn(Set.of("alpha", "beta"));
        resolver = new DeepLinkResolver(service, security);
    }

    @Test
    @DisplayName("Gueltiger Link auf eigenen Datensatz fuehrt auf die Ziel-Seite mit View-Parameter")
    void gueltigerLinkFuehrtZumDatensatz() {
        DeepLinkResolution ergebnis = resolver.resolve("auszahlung", "alpha", "42");

        assertTrue(ergebnis.erlaubt());
        assertEquals("/auszahlungen.html?id=42", ergebnis.zielPfad());
    }

    @Nested
    @DisplayName("Mandantentrennung")
    class Mandantentrennung {

        @Test
        @DisplayName("Link auf ein Mandat ohne Berechtigung wird abgelehnt — ohne jeden Wechsel")
        void fremdesMandatWirdAbgelehnt() {
            DeepLinkResolution ergebnis = resolver.resolve("auszahlung", "fremd", "42");

            assertEquals(DeepLinkResolution.Ergebnis.MANDAT_VERWEIGERT, ergebnis.ergebnis());
            assertNull(ergebnis.zielPfad());
            // No switch, not even "briefly for display purposes".
            verify(security, never()).switchActiveMandat(anyString());
            // And the module is not even asked about the record.
            assertFalse(ziel.gefragt, "Bei fehlendem Mandat-Zugriff darf das Modul nicht befragt werden");
        }

        @Test
        @DisplayName("Erlaubter Mandat-Wechsel geht ueber dieselbe Logik wie die Topbar-Auswahl")
        void erlaubterMandatWechsel() {
            when(security.getMandat()).thenReturn("beta");
            ziel = new TestZiel();
            resolver = new DeepLinkResolver(new DeepLinkServiceImpl(List.of(ziel), ""), security);

            DeepLinkResolution ergebnis = resolver.resolve("auszahlung", "alpha", "42");

            assertTrue(ergebnis.erlaubt());
            verify(security).switchActiveMandat("alpha");
            assertEquals("alpha", ziel.gefragtesMandat, "Das Modul muss im Ziel-Mandat gefragt werden");
        }

        @Test
        @DisplayName("Scheitert die Datensatz-Pruefung, wird der vorherige Mandat wiederhergestellt")
        void mandatWirdBeiAblehnungZurueckgesetzt() {
            when(security.getMandat()).thenReturn("beta");

            DeepLinkResolution ergebnis = resolver.resolve("auszahlung", "alpha", "999");

            assertEquals(DeepLinkResolution.Ergebnis.DATENSATZ_VERWEIGERT, ergebnis.ergebnis());
            verify(security).switchActiveMandat("alpha");
            verify(security).switchActiveMandat("beta");
        }
    }

    @Nested
    @DisplayName("Datensatz-Zugriff")
    class Datensatzzugriff {

        @Test
        @DisplayName("Geratene fremde Id wird serverseitig abgelehnt")
        void geratenerDatensatzWirdAbgelehnt() {
            DeepLinkResolution ergebnis = resolver.resolve("auszahlung", "alpha", "999999");

            assertEquals(DeepLinkResolution.Ergebnis.DATENSATZ_VERWEIGERT, ergebnis.ergebnis());
            assertNull(ergebnis.zielPfad());
            assertTrue(ziel.gefragt, "Die Pruefung muss beim Modul serverseitig stattfinden");
        }

        @Test
        @DisplayName("Fehler in der Modul-Pruefung bedeutet 'nein' (fail-closed, kein fail-open)")
        void fehlerInDerPruefungIstEinNein() {
            ziel.wirftFehler = true;

            DeepLinkResolution ergebnis = resolver.resolve("auszahlung", "alpha", "42");

            assertEquals(DeepLinkResolution.Ergebnis.DATENSATZ_VERWEIGERT, ergebnis.ergebnis());
        }
    }

    @Nested
    @DisplayName("Manipulierte Parameter")
    class ManipulierteParameter {

        @Test
        @DisplayName("Nicht registrierter Typ wird abgelehnt (kein Durchreichen beliebiger Seiten)")
        void unbekannterTypWirdAbgelehnt() {
            DeepLinkResolution ergebnis = resolver.resolve("gibtsnicht", "alpha", "42");

            assertEquals(DeepLinkResolution.Ergebnis.UNBEKANNTER_TYP, ergebnis.ergebnis());
            verify(security, never()).switchActiveMandat(anyString());
        }

        @ParameterizedTest(name = "Id \"{0}\" wird abgelehnt")
        @ValueSource(strings = {
                "42&mandat=fremd",          // parameter smuggling
                "42#anker",                 // fragment
                "../../etc/passwd",         // path traversal
                "42%0d%0aSet-Cookie:x=y",   // header injection (raw)
                "42\r\nSet-Cookie: a=b",    // header injection
                "<script>alert(1)</script>",// reflection
                "",                         // empty
                "1 OR 1=1"                  // SQL-like
        })
        void manipulierteIdWirdAbgelehnt(String id) {
            DeepLinkResolution ergebnis = resolver.resolve("auszahlung", "alpha", id);

            assertEquals(DeepLinkResolution.Ergebnis.UNGUELTIGE_PARAMETER, ergebnis.ergebnis());
            assertNull(ergebnis.zielPfad());
        }

        @ParameterizedTest(name = "Mandat \"{0}\" wird abgelehnt")
        @ValueSource(strings = {"alpha/../beta", "//example.com", "alpha&id=1", "alpha beta", ""})
        void manipuliertesMandatWirdAbgelehnt(String mandat) {
            DeepLinkResolution ergebnis = resolver.resolve("auszahlung", mandat, "42");

            assertEquals(DeepLinkResolution.Ergebnis.UNGUELTIGE_PARAMETER, ergebnis.ergebnis());
        }

        @Test
        @DisplayName("Fehlende Parameter werden abgelehnt")
        void fehlendeParameterWerdenAbgelehnt() {
            assertEquals(DeepLinkResolution.Ergebnis.UNGUELTIGE_PARAMETER,
                    resolver.resolve(null, null, null).ergebnis());
        }

        @Test
        @DisplayName("Liefert getAllowedMandate() null, wird abgelehnt statt durchgelassen")
        void keineMandatlisteBedeutetAblehnung() {
            when(security.getAllowedMandate()).thenReturn(null);

            assertEquals(DeepLinkResolution.Ergebnis.MANDAT_VERWEIGERT,
                    resolver.resolve("auszahlung", "alpha", "42").ergebnis());
        }
    }

    @Test
    @DisplayName("Der Typ bestimmt die Ziel-View — nicht die URL")
    void zielViewKommtAusDerRegistry() {
        Optional<DeepLinkTarget> gefunden = service.findTarget("AUSZAHLUNG");

        assertTrue(gefunden.isPresent(), "Typ-Suche ist unabhaengig von Gross-/Kleinschreibung");
        assertEquals("auszahlungen.html", gefunden.get().getView());
    }
}
