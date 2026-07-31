/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.selfservice;

import ch.plaintext.SystemMailSender;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.mailtemplate.IMailTemplateProvider;
import ch.plaintext.mailtemplate.repository.MailTemplateRepository;
import ch.plaintext.mailtemplate.service.MailTemplateService;
import ch.plaintext.settings.ISetupConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

    @Mock PasswordResetTokenRepository tokenRepository;
    @Mock MyUserRepository userRepository;
    @Mock MyRememberMeRepository rememberMeRepository;
    @Mock ISetupConfigService setupConfigService;
    @Mock ObjectProvider<SystemMailSender> systemMailSenderProvider;
    @Mock SystemMailSender systemMailSender;
    @Mock PasswordEncoder passwordEncoder;

    private final IMailTemplateProvider mailTemplateProvider = new MailTemplateService(mock(MailTemplateRepository.class));

    private SelfServiceProperties properties;
    /** SECURITY (Karte 314, Punkt 9): Registry zum Beenden aktiver Sessions nach dem Reset. */
    @Mock
    private ObjectProvider<ch.plaintext.sessions.service.HttpSessionRegistry> sessionRegistryProvider;

    @Mock
    private ch.plaintext.sessions.service.HttpSessionRegistry sessionRegistry;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        properties = new SelfServiceProperties();
        properties.setPasswordResetTokenTtl(Duration.ofHours(1));
        properties.setDefaultMandat("default");
        service = new PasswordResetService(
                tokenRepository, userRepository, rememberMeRepository, setupConfigService,
                systemMailSenderProvider, passwordEncoder, properties, mailTemplateProvider,
                sessionRegistryProvider);

        when(systemMailSenderProvider.getIfAvailable()).thenReturn(systemMailSender);
        when(setupConfigService.getSystemMailAccountId()).thenReturn(1L);
        when(systemMailSender.sendSystemMail(anyLong(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(true);
    }

    @Test
    void startReset_returnsDisabledWhenMandantOptedOut() {
        when(setupConfigService.isPasswordResetLinkEnabled("default")).thenReturn(false);

        PasswordResetService.ResetOutcome outcome = service.startReset(
                "user@example.com", "default", "https://example.com");

        assertEquals(PasswordResetService.ResetOutcome.DISABLED, outcome);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void startReset_acceptedForUnknownUser_butCreatesNoToken() {
        when(setupConfigService.isPasswordResetLinkEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("ghost@example.com")).thenReturn(null);

        PasswordResetService.ResetOutcome outcome = service.startReset(
                "ghost@example.com", "default", "https://example.com");

        assertEquals(PasswordResetService.ResetOutcome.ACCEPTED, outcome);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void startReset_persistsTokenAndQueuesEmail() {
        when(setupConfigService.isPasswordResetLinkEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("u@example.com")).thenReturn(new MyUserEntity());
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PasswordResetService.ResetOutcome outcome = service.startReset(
                "u@example.com", "default", "https://example.com");

        assertEquals(PasswordResetService.ResetOutcome.ACCEPTED, outcome);
        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        assertEquals("u@example.com", tokenCaptor.getValue().getUsername());
        assertEquals("default", tokenCaptor.getValue().getMandat());
        verify(systemMailSender).sendSystemMail(eq(1L), eq("u@example.com"), anyString(), anyString(), eq(false));
    }

    @Test
    void completeReset_rejectsShortPassword() {
        PasswordResetService.ResetResult result = service.completeReset("any", "short");
        assertFalse(result.ok());
    }

    @Test
    void completeReset_updatesPasswordAndConsumesToken() {
        PasswordResetToken token = newToken("u@example.com", "default", Duration.ofHours(1));
        MyUserEntity user = new MyUserEntity();
        user.setUsername("u@example.com");
        user.setPassword("OLD");
        // Karte 307, K2.3: Lookup per Hash + ATOMARES Einloesen (consumeToken == 1).
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(tokenRepository.consumeToken(anyString(), any(Instant.class))).thenReturn(1);
        when(userRepository.findByUsername("u@example.com")).thenReturn(user);
        when(passwordEncoder.encode("new-strong-pw")).thenReturn("BCRYPT::new-strong-pw");

        PasswordResetService.ResetResult result = service.completeReset("good", "new-strong-pw");

        assertTrue(result.ok());
        assertEquals("BCRYPT::new-strong-pw", user.getPassword());
        verify(tokenRepository).consumeToken(anyString(), any(Instant.class));
        verify(userRepository).save(user);
        // Remember-Me / PERSISTENT_LOGINS des Users muss invalidiert werden.
        verify(rememberMeRepository).deleteAllByUsername("u@example.com");
    }

    /**
     * SECURITY (Karte 314, Punkt 9): der Reset muss auch die noch AKTIVEN HTTP-Sessions beenden.
     * Vorher wurden nur die persistenten Remember-Me-Tokens geloescht — wer bereits eine offene
     * Session hatte (genau der Fall, in dem jemand sein Passwort zuruecksetzt), behielt seinen
     * Zugriff bis zum Session-Timeout.
     */
    @Test
    void completeReset_invalidatesActiveSessions() {
        PasswordResetToken token = newToken("u@example.com", "default", Duration.ofHours(1));
        MyUserEntity user = new MyUserEntity();
        user.setUsername("u@example.com");
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(tokenRepository.consumeToken(anyString(), any(Instant.class))).thenReturn(1);
        when(userRepository.findByUsername("u@example.com")).thenReturn(user);
        when(passwordEncoder.encode(anyString())).thenReturn("BCRYPT::x");
        when(sessionRegistryProvider.getIfAvailable()).thenReturn(sessionRegistry);

        assertTrue(service.completeReset("good", "new-strong-pw").ok());

        verify(sessionRegistry).invalidateSessionsOfUser("u@example.com");
    }

    /**
     * SECURITY (Karte 314, Punkt 9): fehlt das optionale Sessions-Modul, muss der Reset trotzdem
     * durchlaufen — ein fehlender Baustein darf den Wiederherstellungsweg nicht blockieren.
     */
    @Test
    void completeReset_worksWithoutSessionRegistry() {
        PasswordResetToken token = newToken("u@example.com", "default", Duration.ofHours(1));
        MyUserEntity user = new MyUserEntity();
        user.setUsername("u@example.com");
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(tokenRepository.consumeToken(anyString(), any(Instant.class))).thenReturn(1);
        when(userRepository.findByUsername("u@example.com")).thenReturn(user);
        when(passwordEncoder.encode(anyString())).thenReturn("BCRYPT::x");
        when(sessionRegistryProvider.getIfAvailable()).thenReturn(null);

        assertTrue(service.completeReset("good", "new-strong-pw").ok());

        verify(rememberMeRepository).deleteAllByUsername("u@example.com");
    }

    @Test
    void completeReset_rejectsExpiredToken() {
        PasswordResetToken expired = newToken("u@example.com", "default", Duration.ofHours(-1));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));
        // Abgelaufen/verbraucht -> das bedingte UPDATE trifft 0 Zeilen.
        when(tokenRepository.consumeToken(anyString(), any(Instant.class))).thenReturn(0);

        PasswordResetService.ResetResult result = service.completeReset("expired", "new-strong-pw");

        assertFalse(result.ok());
        verify(userRepository, never()).save(any());
        verify(rememberMeRepository, never()).deleteAllByUsername(anyString());
    }

    @Test
    void startReset_speichertNurHash_nichtDenKlartextToken() {
        when(setupConfigService.isPasswordResetLinkEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("u@example.com")).thenReturn(new MyUserEntity());
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.startReset("u@example.com", "default", "https://example.com");

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(captor.capture());
        String stored = captor.getValue().getTokenHash();
        assertNotNull(stored);
        assertEquals(64, stored.length(), "In der DB liegt der 64-stellige SHA-256-Hex, nicht der Klartext-Token");
        assertTrue(stored.matches("[0-9a-f]{64}"));
    }

    private static PasswordResetToken newToken(String username, String mandat, Duration ttl) {
        PasswordResetToken t = new PasswordResetToken();
        t.setTokenHash("anyhash");
        t.setUsername(username);
        t.setMandat(mandat);
        t.setIssuedAt(Instant.now());
        t.setExpiresAt(Instant.now().plus(ttl));
        return t;
    }
}
