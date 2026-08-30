/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The entry point {@code /deeplink} — above all the case "not logged in" (card 345). */
class DeepLinkControllerTest {

    private DeepLinkResolver resolver;
    private DeepLinkController controller;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        resolver = Mockito.mock(DeepLinkResolver.class);
        controller = new DeepLinkController(resolver);
        request = new MockHttpServletRequest("GET", "/deeplink");
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void angemeldet() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("benutzer@example.com", "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    private void anonym() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    }

    @Test
    @DisplayName("Nicht angemeldet: ab zur Anmeldung, Ziel wird in der Session gemerkt")
    void nichtAngemeldetFuehrtZumLogin() throws IOException {
        controller.oeffne("auszahlung", "alpha", "42", request, response);

        assertEquals("/login.html", response.getRedirectedUrl());
        assertNotNull(request.getSession(false).getAttribute(DeepLinkPendingStore.SESSION_ATTRIBUTE));
        // Without a login nothing is resolved at all — no information about the existence of data.
        verify(resolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("Anonymous zaehlt nicht als angemeldet")
    void anonymFuehrtZumLogin() throws IOException {
        anonym();

        controller.oeffne("auszahlung", "alpha", "42", request, response);

        assertEquals("/login.html", response.getRedirectedUrl());
        verify(resolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("Nicht angemeldet mit manipuliertem Ziel: nichts wird gemerkt (kein Open Redirect)")
    void manipuliertesZielWirdNichtGemerkt() throws IOException {
        controller.oeffne("auszahlung", "alpha", "42&next=https://example.com", request, response);

        assertEquals("/login.html", response.getRedirectedUrl());
        // Not even a session is created for it — there is simply nothing to remember.
        assertNull(request.getSession(false));
    }

    @Test
    @DisplayName("Angemeldet und berechtigt: Weiterleitung auf die Ziel-Seite")
    void erlaubterLinkLeitetWeiter() throws IOException {
        angemeldet();
        when(resolver.resolve("auszahlung", "alpha", "42"))
                .thenReturn(DeepLinkResolution.ok("/auszahlungen.html?id=42"));

        controller.oeffne("auszahlung", "alpha", "42", request, response);

        assertEquals("/auszahlungen.html?id=42", response.getRedirectedUrl());
    }

    @Test
    @DisplayName("Angemeldet, aber nicht berechtigt: Zugriffsfehler ohne Grund in der URL")
    void abgelehnterLinkFuehrtZurFehlerseite() throws IOException {
        angemeldet();
        when(resolver.resolve(any(), any(), any()))
                .thenReturn(DeepLinkResolution.abgelehnt(DeepLinkResolution.Ergebnis.MANDAT_VERWEIGERT));

        controller.oeffne("auszahlung", "fremd", "42", request, response);

        assertEquals("/access-denied.html", response.getRedirectedUrl());
    }

    @Test
    @DisplayName("Context-Path wird bei allen Weiterleitungen vorangestellt")
    void contextPathWirdBeruecksichtigt() throws IOException {
        angemeldet();
        request.setContextPath("/app");
        when(resolver.resolve(any(), any(), any()))
                .thenReturn(DeepLinkResolution.ok("/auszahlungen.html?id=1"));

        controller.oeffne("auszahlung", "alpha", "1", request, response);

        assertEquals("/app/auszahlungen.html?id=1", response.getRedirectedUrl());
    }
}
