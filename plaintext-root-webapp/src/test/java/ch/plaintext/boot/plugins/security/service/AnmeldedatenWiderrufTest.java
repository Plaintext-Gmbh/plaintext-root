/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.service;

import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.sessions.service.HttpSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card 1495 (S-5): a credential change ends the remember-me tokens and every other session of the
 * account, keeps the session it was made from and leaves other accounts alone.
 */
class AnmeldedatenWiderrufTest {

    private static final String KONTO = "opfer@example.invalid";

    private final MyRememberMeRepository rememberMeRepository = mock(MyRememberMeRepository.class);
    private final HttpSessionRegistry registry = new HttpSessionRegistry();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<HttpSessionRegistry> provider = mock(ObjectProvider.class);
    private final AnmeldedatenWiderruf widerruf = new AnmeldedatenWiderruf(rememberMeRepository, provider);

    @AfterEach
    void aufraeumen() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MockHttpSession angemeldet(String benutzer) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(benutzer, "n/a", Collections.emptyList())));
        registry.registerSession(session.getId(), session);
        return session;
    }

    @Test
    void beendetRememberMeUndFremdeSitzungen_behaeltDieEigene() {
        when(provider.getIfAvailable()).thenReturn(registry);
        MockHttpSession eigene = angemeldet(KONTO);
        MockHttpSession angreifer = angemeldet(KONTO);
        MockHttpSession anderesKonto = angemeldet("andere@example.invalid");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(eigene);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        widerruf.widerrufe(KONTO);

        verify(rememberMeRepository).deleteAllByUsername(KONTO);
        assertTrue(angreifer.isInvalid(), "die zweite Sitzung des Kontos muss enden");
        assertFalse(eigene.isInvalid(), "die Sitzung, aus der geaendert wurde, bleibt");
        assertFalse(anderesKonto.isInvalid(), "fremde Konten bleiben unberuehrt");
    }

    @Test
    void ohneRequest_endenAlleSitzungenDesKontos() {
        when(provider.getIfAvailable()).thenReturn(registry);
        MockHttpSession a = angemeldet(KONTO);
        MockHttpSession b = angemeldet(KONTO);

        widerruf.widerrufe(KONTO);

        assertTrue(a.isInvalid());
        assertTrue(b.isInvalid());
    }

    @Test
    void ohneSessionRegistry_werdenTrotzdemDieTokensGeloescht() {
        when(provider.getIfAvailable()).thenReturn(null);

        widerruf.widerrufe(KONTO);

        verify(rememberMeRepository).deleteAllByUsername(KONTO);
    }

    @Test
    void leererBenutzername_tutNichts() {
        widerruf.widerrufe(" ");
        widerruf.widerrufe(null);

        verify(rememberMeRepository, never()).deleteAllByUsername(anyString());
    }
}
