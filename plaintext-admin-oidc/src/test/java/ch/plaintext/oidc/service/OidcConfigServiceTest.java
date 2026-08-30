/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.oidc.service;

import ch.plaintext.oidc.entity.OidcConfig;
import ch.plaintext.oidc.repository.OidcConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Status report 29.08.2026, measure 13 (JaCoCo gate): the service behind the OIDC configuration was
 * untested. The login button (label/icon) and {@code isOidcEnabled()} drive the login page.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OidcConfigService")
class OidcConfigServiceTest {

    @Mock
    private OidcConfigRepository repository;

    @InjectMocks
    private OidcConfigService service;

    private static OidcConfig config(String name, boolean enabled) {
        OidcConfig c = new OidcConfig();
        c.setId(1L);
        c.setName(name);
        c.setEnabled(enabled);
        c.setButtonLabel("Mit " + name + " anmelden");
        c.setButtonIcon("pi pi-key");
        return c;
    }

    @Test
    void findAllDelegiert() {
        List<OidcConfig> alle = List.of(config("Keycloak", true));
        when(repository.findAll()).thenReturn(alle);
        assertSame(alle, service.findAll());
    }

    @Test
    void aktiveKonfigurationUndAbgeleiteteKnopfTexte() {
        OidcConfig aktiv = config("Keycloak", true);
        when(repository.findFirstByEnabledTrue()).thenReturn(Optional.of(aktiv));
        when(repository.findByEnabledTrue()).thenReturn(List.of(aktiv));

        assertEquals(Optional.of(aktiv), service.getActiveConfig());
        assertEquals(List.of(aktiv), service.getActiveConfigs());
        assertTrue(service.isOidcEnabled());
        assertEquals("Mit Keycloak anmelden", service.getActiveButtonLabel());
        assertEquals("pi pi-key", service.getActiveButtonIcon());
    }

    @Test
    void ohneAktiveKonfigurationGeltenDieStandardTexte() {
        when(repository.findFirstByEnabledTrue()).thenReturn(Optional.empty());
        when(repository.findByEnabledTrue()).thenReturn(List.of());

        assertFalse(service.isOidcEnabled());
        assertEquals("Login", service.getActiveButtonLabel());
        assertEquals("pi pi-sign-in", service.getActiveButtonIcon());
    }

    @Test
    void saveGibtDenGespeichertenEintragZurueck() {
        OidcConfig c = config("Keycloak", false);
        when(repository.save(c)).thenReturn(c);

        assertSame(c, service.save(c));
    }

    @Test
    void deleteNurMitPersistiertemEintrag() {
        service.delete(null);
        service.delete(new OidcConfig());
        verify(repository, never()).delete(any());

        OidcConfig c = config("Keycloak", false);
        service.delete(c);
        verify(repository).delete(c);
    }

    @Test
    void testConnectionMeldetLeereIssuerUrl() {
        assertEquals("Issuer-URL ist leer", service.testConnection(null));
        OidcConfig c = new OidcConfig();
        c.setIssuerUrl("   ");
        assertEquals("Issuer-URL ist leer", service.testConnection(c));
    }

    @Test
    void testConnectionMeldetNichtErreichbarenIssuerAlsFehlerStattAuszunehmen() {
        OidcConfig c = new OidcConfig();
        // Port 9 (discard) is not open on a developer machine: the connection is refused.
        c.setIssuerUrl("http://127.0.0.1:9/realms/test///");

        String ergebnis = service.testConnection(c);

        assertTrue(ergebnis.startsWith("Fehler: "), ergebnis);
    }
}
