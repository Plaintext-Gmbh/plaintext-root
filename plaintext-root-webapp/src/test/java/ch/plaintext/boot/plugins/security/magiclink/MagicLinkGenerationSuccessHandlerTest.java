/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.magiclink;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.security.authentication.ott.OneTimeToken;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Der Handler delegiert das Bauen/Versenden des Magic-Links an {@link MagicLinkService}
 * und antwortet immer neutral – die Mail-/Link-Logik selbst wird in {@link MagicLinkServiceTest} geprueft.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MagicLinkGenerationSuccessHandlerTest {

    @Mock
    private MyUserRepository userRepository;
    @Mock
    private ISetupConfigService setupConfigService;
    @Mock
    private MagicLinkService magicLinkService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private MagicLinkGenerationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        when(request.getContextPath()).thenReturn("");
        when(setupConfigService.isMagicLinkEnabled(anyString())).thenReturn(true);
    }

    @Test
    void handle_delegiertAnServiceUndRedirects_beibekanntemUser() throws Exception {
        MyUserEntity user = new MyUserEntity();
        user.setUsername("user@example.com");
        user.addRole("PROPERTY_MANDAT_TEST");
        when(userRepository.findByUsername("user@example.com")).thenReturn(user);

        OneTimeToken token = new DefaultOneTimeToken("abc123", "user@example.com",
                Instant.now().plusSeconds(600));

        handler.handle(request, response, token);

        verify(magicLinkService).sendForExistingToken(user, "abc123", request);
        verify(response).sendRedirect("/login.xhtml?magic_link_sent=true");
    }

    @Test
    void handle_delegiertNicht_beiUnbekanntemUser() throws Exception {
        when(userRepository.findByUsername("unbekannt@example.com")).thenReturn(null);

        OneTimeToken token = new DefaultOneTimeToken("abc123", "unbekannt@example.com",
                Instant.now().plusSeconds(600));

        handler.handle(request, response, token);

        // Keine Delegation – kein Enumeration
        verify(magicLinkService, never()).sendForExistingToken(any(), any(), any());
        // Immer gleicher Redirect
        verify(response).sendRedirect("/login.xhtml?magic_link_sent=true");
    }

    @Test
    void handle_delegiertNicht_wennMagicLinkDeaktiviert() throws Exception {
        MyUserEntity user = new MyUserEntity();
        user.setUsername("user@example.com");
        user.addRole("PROPERTY_MANDAT_TEST");
        when(userRepository.findByUsername("user@example.com")).thenReturn(user);
        when(setupConfigService.isMagicLinkEnabled("test")).thenReturn(false);

        OneTimeToken token = new DefaultOneTimeToken("abc123", "user@example.com",
                Instant.now().plusSeconds(600));

        handler.handle(request, response, token);

        verify(magicLinkService, never()).sendForExistingToken(any(), any(), any());
        verify(response).sendRedirect("/login.xhtml?magic_link_sent=true");
    }
}
