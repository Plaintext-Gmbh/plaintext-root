/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The login detour must not become an open redirect (card 345).
 */
class DeepLinkPendingStoreTest {

    @Test
    @DisplayName("Gemerkter Deep-Link wird nach dem Login wieder zum /deeplink-Aufruf")
    void merkenUndEntnehmen() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        DeepLinkPendingStore.merke(request, "auszahlung", "ALPHA", "42");

        DeepLinkPendingStore.PendingDeepLink pending = DeepLinkPendingStore.entnehme(request);

        assertNotNull(pending);
        assertEquals("alpha", pending.mandat(), "Mandat wird normalisiert gemerkt");
        assertEquals("/deeplink?type=auszahlung&mandat=alpha&id=42", DeepLinkPendingStore.alsPfad(pending));
    }

    @Test
    @DisplayName("Der gemerkte Link gilt genau einmal")
    void nurEinmalVerwendbar() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        DeepLinkPendingStore.merke(request, "auszahlung", "alpha", "42");

        assertNotNull(DeepLinkPendingStore.entnehme(request));
        assertNull(DeepLinkPendingStore.entnehme(request), "Zweiter Login darf nicht erneut umgeleitet werden");
    }

    @Test
    @DisplayName("Ohne Session gibt es nichts zu entnehmen")
    void ohneSession() {
        assertNull(DeepLinkPendingStore.entnehme(new MockHttpServletRequest()));
        assertNull(DeepLinkPendingStore.alsPfad(null));
    }

    @ParameterizedTest(name = "Ziel \"{0}\" wird nicht gemerkt")
    @ValueSource(strings = {
            "https://example.com",     // foreign jump-off address
            "//example.com",           // protocol-relative
            "/index.html",             // our own, but free-form path
            "42&x=y",
            "\r\nLocation: https://example.com"
    })
    @DisplayName("Kein Wert, der eine fremde Adresse werden koennte, landet in der Session")
    void keineUrlWirdGemerkt(String boesartig) {
        MockHttpServletRequest request = new MockHttpServletRequest();

        // No matter where the value stands — it fits no pattern and is discarded.
        DeepLinkPendingStore.merke(request, boesartig, "alpha", "42");
        DeepLinkPendingStore.merke(request, "auszahlung", boesartig, "42");
        DeepLinkPendingStore.merke(request, "auszahlung", "alpha", boesartig);

        assertNull(DeepLinkPendingStore.entnehme(request));
    }

    @Test
    @DisplayName("Ein von aussen manipulierter Session-Eintrag ergibt keinen Pfad")
    void manipulierterSessionEintrag() {
        assertNull(DeepLinkPendingStore.alsPfad(
                new DeepLinkPendingStore.PendingDeepLink("auszahlung", "alpha", "1&next=https://example.com")));
    }
}
