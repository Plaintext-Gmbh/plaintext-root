/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sessions.service;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * SECURITY (Karte 314, Punkt 9) — Sessions nach dem Passwort-Reset beenden.
 *
 * <p>Der Reset-Flow loeschte bisher nur die persistenten Remember-Me-Tokens. Wer bereits eine
 * offene HTTP-Session auf dem Konto hatte — genau der Fall, in dem ein Betroffener sein Passwort
 * zuruecksetzt — behielt seinen Zugriff bis zum Session-Timeout, der Reset war als
 * Wiederherstellungsmassnahme also wirkungslos.
 */
@DisplayName("HttpSessionRegistry: Session-Invalidierung")
class HttpSessionRegistryInvalidationTest {

    private static final String CONTEXT_KEY = "SPRING_SECURITY_CONTEXT";

    private HttpSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new HttpSessionRegistry();
    }

    private HttpSession sessionOf(String username) {
        HttpSession session = mock(HttpSession.class);
        SecurityContext ctx = new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                username, "n/a", Collections.emptyList()));
        when(session.getAttribute(CONTEXT_KEY)).thenReturn(ctx);
        return session;
    }

    @Test
    void invalidatesOnlySessionsOfTheGivenUser() {
        HttpSession victim = sessionOf("opfer@example.invalid");
        HttpSession other = sessionOf("andere@example.invalid");
        registry.registerSession("s1", victim);
        registry.registerSession("s2", other);

        int count = registry.invalidateSessionsOfUser("opfer@example.invalid");

        assertEquals(1, count);
        verify(victim).invalidate();
        verify(other, never()).invalidate();
    }

    @Test
    void matchesUsernameCaseInsensitively() {
        HttpSession session = sessionOf("Opfer@Example.Invalid");
        registry.registerSession("s1", session);

        assertEquals(1, registry.invalidateSessionsOfUser("opfer@example.invalid"));
        verify(session).invalidate();
    }

    @Test
    void ignoresSessionsWithoutSecurityContext() {
        HttpSession anonymous = mock(HttpSession.class);
        when(anonymous.getAttribute(CONTEXT_KEY)).thenReturn(null);
        registry.registerSession("s1", anonymous);

        assertEquals(0, registry.invalidateSessionsOfUser("opfer@example.invalid"));
        verify(anonymous, never()).invalidate();
    }

    @Test
    void survivesAlreadyInvalidatedSessions() {
        HttpSession stale = sessionOf("opfer@example.invalid");
        doThrow(new IllegalStateException("session already invalidated")).when(stale).invalidate();
        HttpSession live = sessionOf("opfer@example.invalid");
        registry.registerSession("s1", stale);
        registry.registerSession("s2", live);

        // Der Fehlschlag einer Session darf die uebrigen nicht stehen lassen.
        assertEquals(1, registry.invalidateSessionsOfUser("opfer@example.invalid"));
        verify(live).invalidate();
    }

    @Test
    void nullUsernameIsANoOp() {
        assertEquals(0, registry.invalidateSessionsOfUser(null));
        assertEquals(0, registry.invalidateSessionsOfUser("  "));
    }
}
