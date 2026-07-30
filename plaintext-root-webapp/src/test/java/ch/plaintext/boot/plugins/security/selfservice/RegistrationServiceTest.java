/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.selfservice;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.SystemMailSender;
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
import org.mockito.InjectMocks;
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
class RegistrationServiceTest {

    @Mock RegistrationTokenRepository tokenRepository;
    @Mock MyUserRepository userRepository;
    @Mock ISetupConfigService setupConfigService;
    @Mock ObjectProvider<SystemMailSender> systemMailSenderProvider;
    @Mock SystemMailSender systemMailSender;
    @Mock PasswordEncoder passwordEncoder;

    private final IMailTemplateProvider mailTemplateProvider = new MailTemplateService(mock(MailTemplateRepository.class));

    private SelfServiceProperties properties;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        properties = new SelfServiceProperties();
        properties.setRegistrationTokenTtl(Duration.ofHours(24));
        properties.setDefaultMandat("default");
        service = new RegistrationService(
                tokenRepository, userRepository, setupConfigService,
                systemMailSenderProvider, passwordEncoder, properties, mailTemplateProvider);

        when(systemMailSenderProvider.getIfAvailable()).thenReturn(systemMailSender);
        when(setupConfigService.getSystemMailAccountId()).thenReturn(1L);
        when(systemMailSender.sendSystemMail(anyLong(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(true);
    }

    @Test
    void startRegistration_returnsDisabledWhenMandantOptedOut() {
        when(setupConfigService.isSelfRegistrationEnabled("default")).thenReturn(false);

        RegistrationService.RegistrationOutcome outcome = service.startRegistration(
                "user@example.com", "default", "https://example.com");

        assertEquals(RegistrationService.RegistrationOutcome.DISABLED, outcome);
        verify(tokenRepository, never()).save(any());
        verify(systemMailSender, never()).sendSystemMail(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void startRegistration_acceptedWhenAddressAlreadyTaken_butCreatesNoToken() {
        when(setupConfigService.isSelfRegistrationEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("taken@example.com")).thenReturn(new MyUserEntity());

        RegistrationService.RegistrationOutcome outcome = service.startRegistration(
                "TAKEN@example.com", "default", "https://example.com");

        // Same outcome as "accepted" so the form does not leak account presence.
        assertEquals(RegistrationService.RegistrationOutcome.ACCEPTED, outcome);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void startRegistration_persistsTokenAndQueuesEmail() {
        when(setupConfigService.isSelfRegistrationEnabled("default")).thenReturn(true);
        when(userRepository.findByUsername("new@example.com")).thenReturn(null);
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegistrationService.RegistrationOutcome outcome = service.startRegistration(
                "new@example.com", "default", "https://example.com");

        assertEquals(RegistrationService.RegistrationOutcome.ACCEPTED, outcome);
        ArgumentCaptor<RegistrationToken> tokenCaptor = ArgumentCaptor.forClass(RegistrationToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        RegistrationToken saved = tokenCaptor.getValue();
        assertEquals("new@example.com", saved.getEmail());
        assertEquals("default", saved.getMandat());
        // Karte 307, K2.3: in der DB liegt der 64-stellige SHA-256-Hex, nicht der Klartext-Token.
        assertNotNull(saved.getTokenHash());
        assertEquals(64, saved.getTokenHash().length());
        assertTrue(saved.getTokenHash().matches("[0-9a-f]{64}"));
        assertTrue(saved.getExpiresAt().isAfter(Instant.now()));
        verify(systemMailSender).sendSystemMail(eq(1L), eq("new@example.com"), anyString(), anyString(), eq(false));
    }

    @Test
    void completeRegistration_rejectsShortPassword() {
        RegistrationService.RegistrationResult result = service.completeRegistration("any-token", "short");
        assertFalse(result.ok());
        verify(userRepository, never()).save(any());
    }

    @Test
    void completeRegistration_rejectsExpiredToken() {
        RegistrationToken expired = newToken("u@x", "default", Duration.ofHours(-1));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));
        // Abgelaufen -> das bedingte UPDATE trifft 0 Zeilen (Karte 307, K2.3).
        when(tokenRepository.consumeToken(anyString(), any(Instant.class))).thenReturn(0);

        RegistrationService.RegistrationResult result = service.completeRegistration("expired", "long-enough-1");

        assertFalse(result.ok());
        verify(userRepository, never()).save(any());
    }

    @Test
    void completeRegistration_rejectsAlreadyConsumedToken() {
        RegistrationToken consumed = newToken("u@x", "default", Duration.ofHours(1));
        consumed.setConsumedAt(Instant.now());
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(consumed));
        // Bereits verbraucht -> bedingtes UPDATE trifft 0 Zeilen.
        when(tokenRepository.consumeToken(anyString(), any(Instant.class))).thenReturn(0);

        RegistrationService.RegistrationResult result = service.completeRegistration("consumed", "long-enough-1");

        assertFalse(result.ok());
        verify(userRepository, never()).save(any());
    }

    @Test
    void completeRegistration_createsUserAndConsumesToken() {
        RegistrationToken token = newToken("u@example.com", "tenantA", Duration.ofHours(1));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(tokenRepository.consumeToken(anyString(), any(Instant.class))).thenReturn(1);
        when(userRepository.findByUsername("u@example.com")).thenReturn(null);
        when(passwordEncoder.encode("hunter2-strong")).thenReturn("BCRYPT::hunter2-strong");

        RegistrationService.RegistrationResult result = service.completeRegistration("good", "hunter2-strong");

        assertTrue(result.ok());
        assertEquals("u@example.com", result.username());
        ArgumentCaptor<MyUserEntity> userCaptor = ArgumentCaptor.forClass(MyUserEntity.class);
        verify(userRepository).save(userCaptor.capture());
        MyUserEntity user = userCaptor.getValue();
        assertEquals("u@example.com", user.getUsername());
        assertEquals("BCRYPT::hunter2-strong", user.getPassword());
        // Karte 306: nackter Rollenname "user" (MyUserDetailsService praefixt beim Login zu ROLE_USER);
        // frueher faelschlich "ROLE_USER" -> Authority "ROLE_ROLE_USER" (wirkungslos).
        assertTrue(user.getRoles().contains("user"));
        assertFalse(user.getRoles().contains("ROLE_USER"),
                "Der praefixte Name darf NICHT gespeichert werden (sonst entsteht ROLE_ROLE_USER)");
        assertTrue(user.getRoles().contains("PROPERTY_MANDAT_tenanta"));
        // Atomar eingeloest (consumeToken == 1) — kein manuelles setConsumedAt mehr.
        verify(tokenRepository).consumeToken(anyString(), any(Instant.class));
    }

    private static RegistrationToken newToken(String email, String mandat, Duration ttl) {
        RegistrationToken t = new RegistrationToken();
        t.setTokenHash("anyhash");
        t.setEmail(email);
        t.setMandat(mandat);
        t.setIssuedAt(Instant.now());
        t.setExpiresAt(Instant.now().plus(ttl));
        return t;
    }
}
