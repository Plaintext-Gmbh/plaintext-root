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
 * Karte 596: Die Default-Methode {@link PlaintextSecurity#getEmailForUser(long)}.
 *
 * <p>Geprüft wird ausdrücklich, dass sie die Adresse über die <b>Benutzer-Id</b> auflöst und nicht
 * über den Sicherheitskontext — im Cron-Lauf liefert {@code getId()} dort {@code -1} (Karte 588),
 * und genau dieser Fehler soll hier auffallen.
 *
 * @author worker01
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
     * Die Mutationsprobe aus Karte 596 in ihrer root-Fassung: Würde man den Empfänger aus dem
     * Sicherheitskontext statt aus der übergebenen Id ziehen, müsste dieser Test rot werden.
     * Er hält fest, dass GENAU die übergebene Id nachgeschlagen wird.
     */
    @Test
    @DisplayName("Die Auflösung geht über die uebergebene Id, nicht über den Sicherheitskontext")
    void loestUeberDieUebergebeneIdAuf() {
        PlaintextSecurity security = mock(PlaintextSecurity.class);
        when(security.getUsernameForUser(4711L)).thenReturn("owner@plaintext.ch");
        when(security.getEmailForUser(anyLong())).thenCallRealMethod();

        assertEquals(Optional.of("owner@plaintext.ch"), security.getEmailForUser(4711L));

        verify(security).getUsernameForUser(4711L);
        // getId() ist der Weg, der im Cron-Kontext -1 liefert — er darf hier nicht benutzt werden.
        verify(security, org.mockito.Mockito.never()).getId();
    }
}
