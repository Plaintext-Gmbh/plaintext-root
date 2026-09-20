/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.service.WatchHandyLinkService;
import ch.plaintext.watch.service.WatchStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outside a FacesContext the bean must still read and write the setting — everything that needs
 * the context degrades to a relative address instead of throwing.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchSettingsBeanTest {

    private WatchStateService zustand;
    private WatchHandyLinkService handyLink;
    private WatchSettingsBean bean;

    private static WatchPage seite(String id, int order) {
        return seite(id, order, true, true);
    }

    private static WatchPage seite(String id, int order, boolean erlaubt, boolean imUmlauf) {
        return new WatchPage() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String title() {
                return "Titel " + id;
            }

            @Override
            public String view() {
                return "/watch/" + id + ".xhtml";
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public boolean available() {
                return erlaubt;
            }

            @Override
            public boolean imUmlauf() {
                return imUmlauf;
            }
        };
    }

    @BeforeEach
    void setUp() {
        zustand = mock(WatchStateService.class);
        handyLink = mock(WatchHandyLinkService.class);
        bean = new WatchSettingsBean();
        ReflectionTestUtils.setField(bean, "zustand", zustand);
        ReflectionTestUtils.setField(bean, "handyLink", handyLink);
        ReflectionTestUtils.setField(bean, "registry",
                new WatchPageRegistry(List.of(seite("home", 0), seite("zeit", 10)), null));
        when(zustand.seiteAktiv("home")).thenReturn(true);
        when(zustand.seiteAktiv("zeit")).thenReturn(true);
    }

    @Test
    @DisplayName("Der Seitenaufruf liest die gespeicherte Auswahl je Seite")
    void seitenaufrufLiestAuswahl() {
        when(zustand.seiteAktiv("zeit")).thenReturn(false);

        bean.seitenaufruf();

        assertEquals(List.of("home", "zeit"),
                bean.getSchalter().stream().map(WatchSettingsBean.SeitenSchalter::getId).toList());
        assertTrue(bean.getSchalter().get(0).isAktiv());
        assertFalse(bean.getSchalter().get(1).isAktiv(), "zeit ist abgeschaltet");
    }

    @Test
    @DisplayName("Speichern reicht jeden Schalter einzeln an den Dienst weiter")
    void speichernReichtWeiter() {
        bean.seitenaufruf();
        bean.getSchalter().get(1).setAktiv(false);

        bean.speichern();

        verify(zustand).setzeSeite("home", true);
        verify(zustand).setzeSeite("zeit", false);
    }

    @Test
    @DisplayName("Eine rollenmaessig gesperrte Seite bekommt gar keinen Schalter")
    void gesperrteSeiteOhneSchalter() {
        ReflectionTestUtils.setField(bean, "registry", new WatchPageRegistry(
                List.of(seite("home", 0), seite("geheim", 5, false, true)), null));

        bean.seitenaufruf();

        assertEquals(List.of("home"),
                bean.getSchalter().stream().map(WatchSettingsBean.SeitenSchalter::getId).toList(),
                "ein Haken, der an der Rollenpruefung nichts aendert, waere eine Luege");
    }

    @Test
    @DisplayName("Die Uebersicht selbst steht nicht in der Schalterliste")
    void uebersichtNichtAbschaltbar() {
        ReflectionTestUtils.setField(bean, "registry", new WatchPageRegistry(
                List.of(seite("home", 0), seite("elemente", 900, true, false)), null));

        bean.seitenaufruf();

        assertEquals(List.of("home"),
                bean.getSchalter().stream().map(WatchSettingsBean.SeitenSchalter::getId).toList());
    }

    @Test
    @DisplayName("Ohne FacesContext liefert die Adresse den relativen Pfad statt zu werfen")
    void adresseOhneKontext() {
        assertEquals(WatchStartController.PFAD, bean.getWatchAdresse());
    }

    @Test
    @DisplayName("Die Adresse fuer den Home-Bildschirm nennt keine feste Seite (Karte 1289)")
    void adresseNenntKeineFesteSeite() {
        // Ein Symbol auf dem Home-Bildschirm wird EINMAL gespeichert und danach jahrelang
        // getippt. Stuende hier eine konkrete Seite, oeffnete es fuer immer diese eine — das
        // Gemerkte wuerde bei jedem Wechsel geschrieben und nie gelesen.
        assertFalse(bean.getWatchAdresse().endsWith(".html"),
                "Die Startadresse zeigt wieder auf eine konkrete Seite: " + bean.getWatchAdresse());
    }

    // ---- Handy-Link ---------------------------------------------------------------------------

    @Test
    @DisplayName("Der erzeugte Link wird einmal gehalten und nicht gespeichert")
    void linkWirdEinmalGezeigt() {
        when(handyLink.erzeuge()).thenReturn("https://app.example/nosec/watch?t=abc");

        assertNull(bean.getNeuerLink(), "vor dem Erzeugen gibt es nichts zu zeigen");
        bean.erzeugeHandyLink();
        assertEquals("https://app.example/nosec/watch?t=abc", bean.getNeuerLink());
    }

    @Test
    @DisplayName("Scheitert das Erzeugen, bleibt kein halber Link stehen")
    void fehlerLaesstKeinenLinkStehen() {
        when(handyLink.erzeuge()).thenThrow(new IllegalStateException("kein Token-Dienst"));

        bean.erzeugeHandyLink();

        assertNull(bean.getNeuerLink());
    }

    @Test
    @DisplayName("Abschalten loescht den angezeigten Link mit")
    void abschaltenLoeschtAnzeige() {
        when(handyLink.erzeuge()).thenReturn("https://app.example/nosec/watch?t=abc");
        bean.erzeugeHandyLink();

        bean.schalteHandyLinkAb();

        assertNull(bean.getNeuerLink(), "ein abgeschalteter Link darf nicht weiter auf dem Schirm stehen");
        verify(handyLink).widerrufe();
    }

    @Test
    @DisplayName("Ohne Token-Modul wird nichts erzeugt und die Seite sagt es")
    void ohneTokenmodul() {
        when(handyLink.verfuegbar()).thenReturn(false);

        assertFalse(bean.isHandyLinkMoeglich());
        verify(handyLink, never()).erzeuge();
    }

    @Test
    @DisplayName("Der Erstellzeitpunkt wird lesbar formatiert, fehlt er, bleibt es leer")
    void erstellzeitpunkt() {
        when(zustand.handyLinkErstellt()).thenReturn(Optional.of(LocalDateTime.of(2026, 9, 19, 14, 5)));
        assertEquals("19.09.2026 14:05", bean.getHandyLinkErstellt());

        when(zustand.handyLinkErstellt()).thenReturn(Optional.empty());
        assertEquals("", bean.getHandyLinkErstellt());
    }
}
