/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gallery's state. The interesting part is the step counter: it must not run past its
 * bounds, because on a watch there is no way to type a corrected value.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchElementeBeanTest {

    private WatchElementeBean bean;

    @BeforeEach
    void setUp() {
        bean = new WatchElementeBean();
    }

    @Test
    @DisplayName("Die Schrittknoepfe zaehlen in 15er-Schritten")
    void schritte() {
        assertEquals(15, bean.getZahl());
        bean.mehr();
        assertEquals(30, bean.getZahl());
        bean.weniger();
        assertEquals(15, bean.getZahl());
    }

    @Test
    @DisplayName("Nach unten ist bei null Schluss — kein negativer Wert")
    void untergrenze() {
        for (int i = 0; i < 5; i++) {
            bean.weniger();
        }
        assertEquals(0, bean.getZahl());
    }

    @Test
    @DisplayName("Nach oben ist bei acht Stunden Schluss")
    void obergrenze() {
        for (int i = 0; i < 40; i++) {
            bean.mehr();
        }
        assertEquals(480, bean.getZahl());
    }

    @Test
    @DisplayName("Die Rueckfrage oeffnet, bestaetigt und bricht ab")
    void rueckfrage() {
        assertFalse(bean.isBestaetigungOffen());

        bean.frageNach();
        assertTrue(bean.isBestaetigungOffen());

        bean.brichAb();
        assertFalse(bean.isBestaetigungOffen());
        assertEquals("abgebrochen", bean.getLetzteAktion());

        bean.frageNach();
        bean.bestaetige();
        assertFalse(bean.isBestaetigungOffen());
        assertEquals("bestaetigt", bean.getLetzteAktion());
    }

    @Test
    @DisplayName("Die Auswahlwerte sind kurz genug fuer die schmale Anzeige")
    void auswahlwerteSindKurz() {
        assertFalse(bean.getAuswahlWerte().isEmpty());
        bean.getAuswahlWerte().forEach(w ->
                assertTrue(w.length() <= 8, "Zu lang fuer eine 184-px-Anzeige: " + w));
    }

    @Test
    @DisplayName("Ein Tipp merkt sich, was getippt wurde")
    void tippMerktSich() {
        bean.tippe("Start");
        assertEquals("Start", bean.getLetzteAktion());
    }
}
