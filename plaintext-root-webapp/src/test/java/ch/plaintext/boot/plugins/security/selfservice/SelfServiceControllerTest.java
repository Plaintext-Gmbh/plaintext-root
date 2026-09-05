/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.selfservice;

import ch.plaintext.framework.EigeneAdresse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Karte 1068: the base URL of reset and verification links must never come from the request.
 *
 * <p>Why a test for something this small: the old code was correct in every single-host set-up
 * and only wrong behind a proxy that does not overwrite {@code X-Forwarded-Host}. That is the
 * kind of regression a reviewer does not see in a diff — a future "let's fall back to the request
 * when nothing is configured" would look reasonable and reopen the phishing path. These tests
 * pin the decision: configured address, or empty, but not the request.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfServiceControllerTest {

    @Mock RegistrationService registrationService;
    @Mock PasswordResetService passwordResetService;
    @Mock HttpServletRequest request;

    private SelfServiceProperties properties;
    private EigeneAdresse eigeneAdresse;

    @BeforeEach
    void setUp() {
        properties = new SelfServiceProperties();
        // Without a settings module the address comes from plaintext.app.ownhost or the given default.
        eigeneAdresse = new EigeneAdresse(null);
        // The request looks like an attack: a forged host, and the code must not even look at it.
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("pruefung.invalid");
        when(request.getServerPort()).thenReturn(443);
        when(request.getHeader("X-Forwarded-Host")).thenReturn("pruefung.invalid");
    }

    private SelfServiceController controller(String plaintextBaseurl) {
        return new SelfServiceController(registrationService, passwordResetService, properties,
                eigeneAdresse, plaintextBaseurl);
    }

    @Test
    void resetLinkNimmtDieKonfigurierteAdresse_nichtDenRequest() {
        ReflectionTestUtils.setField(eigeneAdresse, "ausKonfiguration", "https://app.plaintext.ch/");

        controller("https://fallback.example").resetSubmit("jemand", request);

        verify(passwordResetService).startReset(eq("jemand"), eq("default"), eq("https://app.plaintext.ch"));
        verify(request, never()).getServerName();
        verify(request, never()).getHeader("X-Forwarded-Host");
    }

    @Test
    void registrierungslinkNimmtPlaintextBaseurl_wennKeineEigeneAdresseGesetztIst() {
        controller("https://fallback.example/").registerSubmit("neu@example.org", request);

        verify(registrationService).startRegistration(eq("neu@example.org"), eq("default"),
                eq("https://fallback.example"));
        verify(request, never()).getServerName();
    }

    @Test
    void ohneJedeKonfigurationBleibtDieBasisLeer_stattAusDemRequestZuKommen() {
        SelfServiceController controller = controller("");

        String basis = controller.basisUrl();

        assertEquals("", basis);
        assertFalse(basis.contains("pruefung.invalid"));
        verify(request, never()).getServerName();
    }

    @Test
    void publicBaseUrlDerSelfServicePropertiesBleibtVorrangig_imService() {
        // Not this class's decision, but the contract the controller relies on: when
        // plaintext.selfservice.public-base-url is set, the services use it and ignore what the
        // controller passes. The controller therefore only has to guarantee that its value is
        // never request-derived — which the tests above pin.
        properties.setPublicBaseUrl("https://explizit.example");
        assertEquals("https://explizit.example", properties.getPublicBaseUrl());
    }
}
