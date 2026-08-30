/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card 596: the default method {@link PlaintextSecurity#getEmailForUser(long)}.
 *
 * <p>What is explicitly checked is that it resolves the address via the <b>user id</b> and not via
 * the security context — in a cron run {@code getId()} returns {@code -1} there (Card 588), and it
 * is exactly that mistake this test is meant to catch.
 *
 * @author info@plaintext.ch
 * @since 07.08.2026
 */
class PlaintextSecurityEmailDefaultTest {

    private PlaintextSecurity mitUsername(String username) {
        PlaintextSecurity security = mock(PlaintextSecurity.class);
        when(security.getUsernameForUser(anyLong())).thenReturn(username);
        when(security.getEmailForUser(anyLong())).thenCallRealMethod();
        return security;
    }

    @Test
    @DisplayName("Benutzername in Mailform wird als Adresse geliefert")
    void mailformWirdGeliefert() {
        assertEquals(Optional.of("daniel@plaintext.ch"),
                mitUsername("daniel@plaintext.ch").getEmailForUser(42L));
    }

    @Test
    @DisplayName("Altbestands-Kürzel liefert leer statt einer unbrauchbaren Adresse")
    void kuerzelLiefertLeer() {
        assertTrue(mitUsername("plafferma").getEmailForUser(42L).isEmpty());
    }

    @Test
    @DisplayName("Maschineller Schreiber (anonymousUser) liefert leer")
    void anonymousUserLiefertLeer() {
        assertTrue(mitUsername("anonymousUser").getEmailForUser(42L).isEmpty());
    }

    @Test
    @DisplayName("Unbekannter Benutzer (null) liefert leer und wirft NICHT")
    void unbekannterBenutzerLiefertLeerOhneAusnahme() {
        assertTrue(mitUsername(null).getEmailForUser(999L).isEmpty());
    }

    /**
     * The mutation probe from Card 596 in its root form: if the recipient were taken from the
     * security context instead of from the id passed in, this test would have to turn red. It
     * pins down that EXACTLY the id passed in is looked up.
     */
    @Test
    @DisplayName("Die Auflösung geht über die uebergebene Id, nicht über den Sicherheitskontext")
    void loestUeberDieUebergebeneIdAuf() {
        PlaintextSecurity security = mock(PlaintextSecurity.class);
        when(security.getUsernameForUser(4711L)).thenReturn("owner@plaintext.ch");
        when(security.getEmailForUser(anyLong())).thenCallRealMethod();

        assertEquals(Optional.of("owner@plaintext.ch"), security.getEmailForUser(4711L));

        verify(security).getUsernameForUser(4711L);
        // getId() is the route that returns -1 in a cron context — it must not be used here.
        verify(security, org.mockito.Mockito.never()).getId();
    }
}
