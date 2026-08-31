/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.boot.deeplink.DeepLinkPendingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redirect after the login to a remembered deep link (card 345).
 *
 * <p>The handler prepends {@code contextPath + "/"} to the returned page — the return value
 * must therefore not have a leading slash and has to stay a path of our own application.
 */
class PlaintextAuthenticationSuccessHandlerDeepLinkTest {

    @Test
    @DisplayName("Gemerkter Deep-Link wird zur Seiten-Angabe ohne fuehrenden Slash")
    void gemerkterDeepLinkWirdZiel() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        DeepLinkPendingStore.merke(request, "auszahlung", "alpha", "42");

        String ziel = PlaintextAuthenticationSuccessHandler.deepLinkZielAusSession(request);

        assertEquals("deeplink?type=auszahlung&mandat=alpha&id=42", ziel);
        assertTrue(!ziel.startsWith("/") && !ziel.contains("://"),
                "Das Ziel muss ein relativer Pfad der eigenen Anwendung bleiben");
    }

    @Test
    @DisplayName("Ohne gemerkten Deep-Link bleibt es bei der normalen Startseite")
    void ohneDeepLinkKeineUmleitung() {
        assertNull(PlaintextAuthenticationSuccessHandler.deepLinkZielAusSession(new MockHttpServletRequest()));
    }

    @Test
    @DisplayName("Der gemerkte Deep-Link wirkt nur beim naechsten Login")
    void nurEinmal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        DeepLinkPendingStore.merke(request, "auszahlung", "alpha", "42");

        assertEquals("deeplink?type=auszahlung&mandat=alpha&id=42",
                PlaintextAuthenticationSuccessHandler.deepLinkZielAusSession(request));
        assertNull(PlaintextAuthenticationSuccessHandler.deepLinkZielAusSession(request));
    }
}
