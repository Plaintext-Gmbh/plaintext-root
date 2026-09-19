/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card 1260: the gallery page became the overview with the page switches. Two properties matter
 * and neither may be lost in a later rework.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchTestPageTest {

    @Test
    @DisplayName("Sie laeuft nicht im Umlauf mit — sonst zaehlt sie wieder in x/n")
    void nichtImUmlauf() {
        assertFalse(new WatchTestPage().imUmlauf(),
                "Die Uebersicht gehoert nicht in den taeglichen Umlauf (Karte 1260)");
    }

    @Test
    @DisplayName("Sie ist immer verfuegbar — eine abschaltbare Uebersicht waere eine Falle")
    void immerVerfuegbar() {
        // Gegenprobe zur Seitenauswahl: JEDE andere Seite laesst sich abschalten. Diese nicht,
        // weil sie der einzige Weg zu den Schaltern ist. Wer sie abschaltbar macht, sperrt den
        // Benutzer aus seiner eigenen Einstellung aus.
        assertTrue(new WatchTestPage().available());
    }

    @Test
    @DisplayName("Kennung, Adresse und Platz bleiben — die Kennung wird je Benutzer gespeichert")
    void kennung() {
        WatchTestPage seite = new WatchTestPage();

        assertEquals("elemente", seite.id(), "die Kennung steht in watch_user_state.aktuelle_seite");
        assertEquals("/watch/elemente.xhtml", seite.view());
        assertEquals(900, seite.order());
        assertEquals("Seiten", seite.title());
    }
}
