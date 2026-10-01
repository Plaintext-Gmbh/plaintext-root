/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import ch.plaintext.watch.mobil.MobilAntwort;
import ch.plaintext.watch.mobil.MobilSeite;
import ch.plaintext.watch.service.WatchStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card 1260: the gallery page became the overview with the page switches; card 1387 made it a
 * page of the mobile framework. Two properties matter and neither may be lost in a later rework,
 * and the switch must only ever flip a page the user may switch.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchTestPageTest {

    private WatchStateService zustand;
    private WatchTestPage seite;

    private static WatchPage stub(String id, boolean erlaubt, boolean imUmlauf) {
        return new WatchPage() {
            public String id() {
                return id;
            }

            public String title() {
                return "Titel " + id;
            }

            public String view() {
                return "/watch/" + id + ".xhtml";
            }

            public boolean available() {
                return erlaubt;
            }

            public boolean imUmlauf() {
                return imUmlauf;
            }
        };
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        zustand = mock(WatchStateService.class);
        when(zustand.seiteAktiv("zeit")).thenReturn(true);
        when(zustand.seiteAktiv("kalender")).thenReturn(false);
        ObjectProvider<WatchPageRegistry> provider = mock(ObjectProvider.class);
        seite = new WatchTestPage(provider, zustand, null);
        WatchPageRegistry registry = new WatchPageRegistry(List.of(
                stub("zeit", true, true), stub("kalender", true, true),
                stub("geheim", false, true), seite), zustand);
        when(provider.getIfAvailable()).thenReturn(registry);
    }

    @Test
    @DisplayName("Sie laeuft nicht im Umlauf mit — sonst zaehlt sie wieder in x/n")
    void nichtImUmlauf() {
        assertFalse(seite.imUmlauf(), "Die Uebersicht gehoert nicht in den taeglichen Umlauf (Karte 1260)");
    }

    @Test
    @DisplayName("Ohne Waechter verfuegbar; mit Waechter gelten die Rollen der Watch-Einstellungen")
    @SuppressWarnings("unchecked")
    void verfuegbar() {
        assertTrue(seite.available());
        ObjectProvider<ch.plaintext.boot.security.PageAccessGuardService> g = mock(ObjectProvider.class);
        ch.plaintext.boot.security.PageAccessGuardService guard = mock(ch.plaintext.boot.security.PageAccessGuardService.class);
        when(g.getIfAvailable()).thenReturn(guard);
        when(guard.hasAccessToView("/watch-einstellungen.xhtml")).thenReturn(false);
        assertFalse(new WatchTestPage(null, zustand, g).available());
        when(guard.hasAccessToView("/watch-einstellungen.xhtml")).thenReturn(true);
        assertTrue(new WatchTestPage(null, zustand, g).available());
    }

    @Test
    @DisplayName("Kennung und Platz bleiben, die Adresse ist die des Mobil-Frameworks")
    void kennung() {
        assertEquals("elemente", seite.id(), "die Kennung steht in watch_user_state.aktuelle_seite");
        assertEquals("/watch/m/elemente", seite.view());
        assertEquals("/watch/elemente.xhtml", seite.frueheresView(), "die alte Adresse wird umgeleitet");
        assertEquals(900, seite.order());
        assertEquals("Seiten", seite.title());
    }

    @Test
    @DisplayName("Die Schalter: jede erlaubte Seite im Umlauf mit ihrem Zustand, ohne gesperrte und ohne sich selbst")
    void schalter() {
        MobilSeite.Schalter s = seite.beschreibe().bausteine().stream()
                .filter(MobilSeite.Schalter.class::isInstance).map(MobilSeite.Schalter.class::cast)
                .findFirst().orElseThrow();

        // Reihenfolge der Registry: order, dann id (beide 100).
        assertEquals(List.of(
                        new MobilSeite.SchalterZeile("Titel kalender", WatchTestPage.SCHALTE, "kalender", false),
                        new MobilSeite.SchalterZeile("Titel zeit", WatchTestPage.SCHALTE, "zeit", true)),
                s.zeilen());
    }

    @Test
    @DisplayName("Ein Tipp schaltet die Seite um und schreibt sofort")
    void schaltetUm() {
        MobilAntwort a = seite.handle(WatchTestPage.SCHALTE, "zeit");

        assertTrue(a.ok());
        verify(zustand).setzeSeite("zeit", false);
    }

    @Test
    @DisplayName("Eine gesperrte, unbekannte oder die eigene Kennung wird nicht geschaltet")
    void nurErlaubteSeiten() {
        for (String fremd : new String[]{"geheim", "gibtsnicht", "elemente", null}) {
            assertFalse(seite.handle(WatchTestPage.SCHALTE, fremd).ok(), String.valueOf(fremd));
        }
        verify(zustand, never()).setzeSeite(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Die Galerie aendert nichts, sie sagt nur, was getippt wurde")
    void galerie() {
        assertEquals("Getippt: Büro", seite.handle(WatchTestPage.TIPPE, "Büro").meldung());
        assertEquals("Übernommen: 08:15–09:00",
                seite.handle(WatchTestPage.AENDERE, "1", Map.of("von", "08:15", "bis", "09:00")).meldung());
        assertFalse(seite.handle("unbekannt", null).ok());
        verify(zustand, never()).setzeSeite(anyString(), anyBoolean());
    }
}
