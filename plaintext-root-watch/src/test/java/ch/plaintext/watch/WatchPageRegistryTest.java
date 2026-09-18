/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page stack is what every watch view depends on: if moving between pages breaks, the user
 * is stuck on one screen with no way out — there is no menu to fall back on.
 */
class WatchPageRegistryTest {

    private static WatchPage seite(String id, int order, boolean verfuegbar) {
        return new WatchPage() {
            public String id() { return id; }
            public String title() { return id; }
            public String view() { return "/nosec/watch/" + id + ".xhtml"; }
            public int order() { return order; }
            public boolean available() { return verfuegbar; }
        };
    }

    @Test
    @DisplayName("Seiten werden nach order sortiert, bei Gleichstand nach id")
    void sortierung() {
        var r = new WatchPageRegistry(List.of(
                seite("zeit", 10, true), seite("home", 0, true), seite("alkohol", 10, true)));
        assertEquals(List.of("home", "alkohol", "zeit"), r.alle().stream().map(WatchPage::id).toList());
    }

    @Test
    @DisplayName("Vorwaerts laeuft am Ende auf die erste Seite zurueck")
    void vorwaertsWickeltUm() {
        var r = new WatchPageRegistry(List.of(seite("a", 1, true), seite("b", 2, true)));
        assertEquals("b", r.naechste("a").orElseThrow().id());
        assertEquals("a", r.naechste("b").orElseThrow().id(), "am Ende zurueck auf die erste");
    }

    @Test
    @DisplayName("Rueckwaerts laeuft am Anfang auf die letzte Seite")
    void rueckwaertsWickeltUm() {
        var r = new WatchPageRegistry(List.of(seite("a", 1, true), seite("b", 2, true)));
        assertEquals("a", r.vorherige("b").orElseThrow().id());
        assertEquals("b", r.vorherige("a").orElseThrow().id(), "am Anfang auf die letzte");
    }

    @Test
    @DisplayName("Abgeschaltete Seiten werden uebersprungen, nicht angezeigt")
    void abgeschalteteUebersprungen() {
        var r = new WatchPageRegistry(List.of(
                seite("a", 1, true), seite("test", 2, false), seite("c", 3, true)));
        assertEquals(List.of("a", "c"), r.verfuegbare().stream().map(WatchPage::id).toList());
        assertEquals("c", r.naechste("a").orElseThrow().id(), "die abgeschaltete wird uebersprungen");
    }

    @Test
    @DisplayName("Eine gemerkte, inzwischen verschwundene Seite faellt auf die erste zurueck")
    void unbekannteIdFaelltZurueck() {
        var r = new WatchPageRegistry(List.of(seite("a", 1, true), seite("b", 2, true)));
        assertEquals("a", r.naechste("gibtsnichtmehr").orElseThrow().id());
    }

    @Test
    @DisplayName("Ohne verfuegbare Seite wird nichts geliefert statt zu werfen")
    void keineSeiteKeinFehler() {
        var r = new WatchPageRegistry(List.of(seite("nur-aus", 1, false)));
        assertTrue(r.verfuegbare().isEmpty());
        assertTrue(r.naechste("nur-aus").isEmpty());
        assertTrue(r.erste().isEmpty());
    }

    @Test
    @DisplayName("Eine einzelne Seite bleibt bei sich selbst — kein Sprung ins Leere")
    void einzelneSeite() {
        var r = new WatchPageRegistry(List.of(seite("allein", 1, true)));
        assertEquals("allein", r.naechste("allein").orElseThrow().id());
        assertEquals("allein", r.vorherige("allein").orElseThrow().id());
    }
}
