/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.page;

import ch.plaintext.watch.service.WatchStateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gallery sits in every user's page stack. If the state service stumbles it must vanish —
 * not take the stack with it.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class WatchTestPageTest {

    @Test
    @DisplayName("Sie erscheint nur, wenn der Benutzer sie eingeschaltet hat")
    void nurWennEingeschaltet() {
        WatchStateService z = mock(WatchStateService.class);
        WatchTestPage seite = new WatchTestPage(z);

        when(z.testseiteAktiv()).thenReturn(false);
        assertFalse(seite.available());

        when(z.testseiteAktiv()).thenReturn(true);
        assertTrue(seite.available());
    }

    @Test
    @DisplayName("Wirft der Dienst, gilt sie als nicht verfuegbar statt den Seitenstapel zu sprengen")
    void dienstWirft() {
        WatchStateService z = mock(WatchStateService.class);
        when(z.testseiteAktiv()).thenThrow(new IllegalStateException("Absicht"));

        assertFalse(new WatchTestPage(z).available());
    }

    @Test
    @DisplayName("Sie steht hinten im Stapel und zeigt auf ihre eigene Seite")
    void kennung() {
        WatchTestPage seite = new WatchTestPage(mock(WatchStateService.class));

        assertEquals("elemente", seite.id());
        assertEquals("/watch/elemente.xhtml", seite.view());
        assertEquals(900, seite.order());
    }
}
