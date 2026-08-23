/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.service;

import ch.plaintext.MenuRegistry;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * Aufloesung von {@code modul:}-Eintraegen in <b>beiden</b> Modi, der Klartext-Grund fuer die
 * Diagnose und die Erkennung toter Eintraege.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Mandanten-Liste: Modul-Keys, Gruende, tote Eintraege")
class MandateMenuModulKeyAufloesungTest {

    private static final String MANDANT = "lauftage2026";
    private static final String WIKI_MODUL = "modul:wiki";
    private static final String WIKI_UNTER = "Wiki | Projekte";
    private static final List<String> WIKI_KEYS = List.of("wiki");

    @Mock
    private MandateMenuConfigRepository repository;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @Mock
    private MenuRegistry menuRegistry;

    private MandateMenuVisibilityService service;

    @BeforeEach
    void setUp() {
        service = new MandateMenuVisibilityService(repository, plaintextSecurity, menuRegistry);
    }

    private void mandantHat(boolean whitelist, String... eintraege) {
        MandateMenuConfig config = new MandateMenuConfig();
        config.setMandateName(MANDANT);
        config.setWhitelistMode(whitelist);
        config.setHiddenMenus(new HashSet<>(Set.of(eintraege)));
        lenient().when(repository.findByMandateName(MANDANT)).thenReturn(Optional.of(config));
    }

    @Nested
    @DisplayName("Blacklist-Modus")
    class Blacklist {

        @Test
        @DisplayName("Ein Modul-Eintrag blendet das ganze Modul aus")
        void modulEintragBlendetModulAus() {
            mandantHat(false, WIKI_MODUL);

            assertFalse(service.isMenuVisibleForMandate("Wiki", WIKI_KEYS, MANDANT));
            assertFalse(service.isMenuVisibleForMandate(WIKI_UNTER, WIKI_KEYS, MANDANT));
            assertTrue(service.isMenuVisibleForMandate("Kontakte", List.of("kontakte"), MANDANT));
        }

        @Test
        @DisplayName("Der Grund nennt Modus und Mandant")
        void grundText() {
            mandantHat(false, WIKI_MODUL);

            assertEquals("in Blacklist von " + MANDANT, service.mandateReason(WIKI_UNTER, WIKI_KEYS, MANDANT));
            assertEquals("", service.mandateReason("Kontakte", List.of("kontakte"), MANDANT));
        }
    }

    @Nested
    @DisplayName("Whitelist-Modus")
    class Whitelist {

        @Test
        @DisplayName("Ein Modul-Eintrag schaltet das ganze Modul frei")
        void modulEintragSchaltetModulFrei() {
            mandantHat(true, WIKI_MODUL);

            assertTrue(service.isMenuVisibleForMandate("Wiki", WIKI_KEYS, MANDANT));
            assertTrue(service.isMenuVisibleForMandate(WIKI_UNTER, WIKI_KEYS, MANDANT));
            assertFalse(service.isMenuVisibleForMandate("Kontakte", List.of("kontakte"), MANDANT));
        }

        @Test
        @DisplayName("Titel- und Modul-Eintrag ergaenzen sich")
        void beideFormen() {
            mandantHat(true, WIKI_MODUL, "Kontakte | Liste");

            assertTrue(service.isMenuVisibleForMandate(WIKI_UNTER, WIKI_KEYS, MANDANT));
            assertTrue(service.isMenuVisibleForMandate("Kontakte | Liste", List.of("kontakte"), MANDANT));
            assertFalse(service.isMenuVisibleForMandate("Kontakte | Import", List.of("kontakte"), MANDANT));
        }

        @Test
        @DisplayName("Der Grund nennt Modus und Mandant")
        void grundText() {
            mandantHat(true, WIKI_MODUL);

            assertEquals("nicht in Whitelist von " + MANDANT,
                    service.mandateReason("Kontakte", List.of("kontakte"), MANDANT));
            assertEquals("", service.mandateReason(WIKI_UNTER, WIKI_KEYS, MANDANT));
        }
    }

    @Nested
    @DisplayName("Rueckwaertskompatibilitaet")
    class Alt {

        @Test
        @DisplayName("Die alte Ein-Argument-Form verhaelt sich unveraendert")
        void alteFormUnveraendert() {
            mandantHat(false, "Wiki | Projekte");

            assertFalse(service.isMenuVisibleForMandate(WIKI_UNTER, MANDANT));
            assertTrue(service.isMenuVisibleForMandate("Wiki | Anderes", MANDANT));
        }

        @Test
        @DisplayName("Ohne Konfiguration ist alles sichtbar")
        void ohneKonfiguration() {
            lenient().when(repository.findByMandateName(MANDANT)).thenReturn(Optional.empty());

            assertTrue(service.isMenuVisibleForMandate(WIKI_UNTER, WIKI_KEYS, MANDANT));
            assertEquals("", service.mandateReason(WIKI_UNTER, WIKI_KEYS, MANDANT));
        }

        @Test
        @DisplayName("Ohne Mandant ist alles sichtbar")
        void ohneMandant() {
            assertTrue(service.isMenuVisibleForMandate(WIKI_UNTER, WIKI_KEYS, null));
            assertTrue(service.isMenuVisibleForMandate(WIKI_UNTER, WIKI_KEYS, ""));
        }
    }

    @Nested
    @DisplayName("Tote Eintraege")
    class ToteEintraege {

        private MandateMenuConfig config(String... eintraege) {
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName(MANDANT);
            config.setHiddenMenus(new HashSet<>(Set.of(eintraege)));
            return config;
        }

        @Test
        @DisplayName("Ein umbenannter Titel wird gemeldet")
        void umbenannterTitel() {
            Set<String> tot = MandateMenuVisibilityService.deadEntries(
                    config("Wiki | Projekte", "Wiki | Alt"),
                    List.of("Wiki | Projekte", "Kontakte"),
                    List.of("wiki", "kontakte"));

            assertEquals(Set.of("Wiki | Alt"), tot);
        }

        @Test
        @DisplayName("Ein Modul-Eintrag auf einen unbekannten Key wird gemeldet")
        void unbekannterModulKey() {
            Set<String> tot = MandateMenuVisibilityService.deadEntries(
                    config(WIKI_MODUL, "modul:gibtsnicht"),
                    List.of("Wiki"),
                    List.of("wiki"));

            assertEquals(Set.of("modul:gibtsnicht"), tot);
        }

        @Test
        @DisplayName("Alles bekannt: kein Befund")
        void keinBefund() {
            Set<String> tot = MandateMenuVisibilityService.deadEntries(
                    config(WIKI_MODUL, "Kontakte"),
                    List.of("Wiki", "Kontakte"),
                    List.of("wiki", "kontakte"));

            assertTrue(tot.isEmpty());
        }

        @Test
        @DisplayName("Robust gegen null und leere Eintraege")
        void robust() {
            assertTrue(MandateMenuVisibilityService.deadEntries(null, List.of(), List.of()).isEmpty());
            assertTrue(MandateMenuVisibilityService.deadEntries(config("  "), List.of(), List.of()).isEmpty());
        }
    }
}
