/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import ch.plaintext.watch.service.WatchStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Card 1257: the per-user page selection and the role check are two separate questions, and the
 * registry is the one place that puts them together.
 *
 * <h2>Why this test exists at all</h2>
 *
 * <p>The tempting shortcut is to let the selection answer {@code available()}. That works right
 * up to the moment a user switches a page <em>on</em> that their role forbids — and then a
 * preference decides an access question. The pairing here is asymmetric on purpose, and the
 * counter-check below is the part that actually proves it: with the selection saying "on" and
 * the role saying "no", the answer has to stay "no".</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchSeitenauswahlTest {

    private static WatchPage seite(String id, int order, boolean erlaubt) {
        return seite(id, order, erlaubt, true);
    }

    private static WatchPage seite(String id, int order, boolean erlaubt, boolean imUmlauf) {
        return new WatchPage() {
            @Override
            public String id() { return id; }
            @Override
            public String title() { return id; }
            @Override
            public String view() { return "/watch/" + id + ".xhtml"; }
            @Override
            public int order() { return order; }
            @Override
            public boolean available() { return erlaubt; }
            @Override
            public boolean imUmlauf() { return imUmlauf; }
        };
    }

    @Test
    @DisplayName("Eine abgeschaltete Seite ist zusaetzlich unsichtbar")
    void abgeschalteteSeiteVerschwindet() {
        WatchStateService zustand = mock(WatchStateService.class);
        when(zustand.seiteAktiv("home")).thenReturn(true);
        when(zustand.seiteAktiv("zeit")).thenReturn(false);
        var r = new WatchPageRegistry(List.of(seite("home", 0, true), seite("zeit", 10, true)), zustand);

        assertEquals(List.of("home"), r.verfuegbare().stream().map(WatchPage::id).toList());
        assertFalse(r.sichtbar(r.byId("zeit").orElseThrow()));
    }

    @Test
    @DisplayName("GEGENPROBE: dieselbe Seite bleibt sichtbar, sobald sie eingeschaltet ist")
    void gegenprobeEingeschaltet() {
        // Ohne diese Messung belegt der Test oben nichts: eine Pruefung, die IMMER false
        // liefert, waere ebenso gruen.
        WatchStateService zustand = mock(WatchStateService.class);
        when(zustand.seiteAktiv("home")).thenReturn(true);
        when(zustand.seiteAktiv("zeit")).thenReturn(true);
        var r = new WatchPageRegistry(List.of(seite("home", 0, true), seite("zeit", 10, true)), zustand);

        assertEquals(List.of("home", "zeit"), r.verfuegbare().stream().map(WatchPage::id).toList());
    }

    @Test
    @DisplayName("Eine rollenmaessig gesperrte Seite bleibt gesperrt, auch wenn sie eingeschaltet ist")
    void rollensperreSchlaegtAuswahl() {
        WatchStateService zustand = mock(WatchStateService.class);
        when(zustand.seiteAktiv("geheim")).thenReturn(true);
        var r = new WatchPageRegistry(List.of(seite("geheim", 10, false)), zustand);

        assertFalse(r.sichtbar(r.byId("geheim").orElseThrow()),
                "die Auswahl des Benutzers darf keine Zugriffsregel aufheben");
        assertTrue(r.verfuegbare().isEmpty());
    }

    @Test
    @DisplayName("Wirft die Zugriffsregel, gilt die Seite als gesperrt — fail-closed")
    void zugriffsregelWirft() {
        WatchStateService zustand = mock(WatchStateService.class);
        when(zustand.seiteAktiv("kaputt")).thenReturn(true);
        WatchPage kaputt = new WatchPage() {
            @Override
            public String id() { return "kaputt"; }
            @Override
            public String title() { return "kaputt"; }
            @Override
            public String view() { return "/watch/kaputt.xhtml"; }
            @Override
            public boolean available() { throw new IllegalStateException("Absicht"); }
        };

        assertFalse(new WatchPageRegistry(List.of(kaputt), zustand).sichtbar(kaputt));
    }

    @Test
    @DisplayName("Ohne Zustandsdienst gilt, was die Zugriffsregel sagt — der Stand vor der Karte")
    void ohneZustandsdienst() {
        var r = new WatchPageRegistry(List.of(seite("home", 0, true), seite("geheim", 5, false)), null);

        assertEquals(List.of("home"), r.verfuegbare().stream().map(WatchPage::id).toList());
    }

    @Test
    @DisplayName("Eine Seite ausserhalb des Umlaufs zaehlt nicht mit, bleibt aber erreichbar")
    void ausserhalbDesUmlaufs() {
        WatchStateService zustand = mock(WatchStateService.class);
        when(zustand.seiteAktiv("home")).thenReturn(true);
        when(zustand.seiteAktiv("elemente")).thenReturn(true);
        var r = new WatchPageRegistry(
                List.of(seite("home", 0, true), seite("elemente", 900, true, false)), zustand);

        assertEquals(List.of("home"), r.verfuegbare().stream().map(WatchPage::id).toList(),
                "die Uebersicht zaehlt nicht in x/n (Karte 1260)");
        assertTrue(r.sichtbar(r.byId("elemente").orElseThrow()),
                "erreichbar bleibt sie trotzdem — sonst kaeme niemand an die Schalter");
        assertEquals("home", r.naechste("home").orElseThrow().id(),
                "beim Blaettern wird sie uebersprungen");
    }
}
