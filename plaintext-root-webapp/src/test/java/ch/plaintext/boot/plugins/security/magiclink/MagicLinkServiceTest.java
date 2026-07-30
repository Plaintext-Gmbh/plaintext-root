/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.magiclink;

import ch.plaintext.SystemMailSender;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.mailtemplate.repository.MailTemplateRepository;
import ch.plaintext.mailtemplate.service.MailTemplateService;
import ch.plaintext.settings.ISetupConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeTokenService;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Prueft die aus dem {@link MagicLinkGenerationSuccessHandler} hierher verschobene Link-Bau- und
 * Mail-Versand-Logik sowie die neue Token-Generierung.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MagicLinkServiceTest {

    @Mock
    private OneTimeTokenService oneTimeTokenService;
    @Mock
    private MyUserRepository userRepository;
    @Mock
    private ObjectProvider<SystemMailSender> systemMailSenderProvider;
    @Mock
    private SystemMailSender systemMailSender;
    @Mock
    private ISetupConfigService setupConfigService;
    @Mock
    private MagicLinkProperties properties;
    @Mock
    private HttpServletRequest request;
    @Spy
    private MailTemplateService mailTemplateProvider = new MailTemplateService(mock(MailTemplateRepository.class));

    @InjectMocks
    private MagicLinkService service;

    @BeforeEach
    void setUp() {
        when(properties.getTokenTtl()).thenReturn(Duration.ofMinutes(10));
        when(properties.getPublicBaseUrl()).thenReturn("");
        when(request.getContextPath()).thenReturn("");
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("app.example.com");
        when(request.getServerPort()).thenReturn(443);

        when(systemMailSenderProvider.getIfAvailable()).thenReturn(systemMailSender);
        when(setupConfigService.isMagicLinkEnabled(anyString())).thenReturn(true);
        when(setupConfigService.getSystemMailAccountId()).thenReturn(1L);
        when(systemMailSender.sendSystemMail(anyLong(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(true);
    }

    private MyUserEntity userWithMandat() {
        MyUserEntity user = new MyUserEntity();
        user.setUsername("user@example.com");
        user.addRole("PROPERTY_MANDAT_TEST");
        return user;
    }

    @Test
    void sendForExistingToken_versendetMail_undLiefertTrue() {
        boolean result = service.sendForExistingToken(userWithMandat(), "abc123", request);

        assertThat(result).isTrue();
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(systemMailSender).sendSystemMail(eq(1L), eq("user@example.com"),
                anyString(), bodyCaptor.capture(), eq(false));
        assertThat(bodyCaptor.getValue()).contains("abc123");
        assertThat(bodyCaptor.getValue()).contains("/login/ott?token=abc123");
    }

    @Test
    void sendForExistingToken_liefertFalse_wennKeinSender() {
        when(systemMailSenderProvider.getIfAvailable()).thenReturn(null);

        boolean result = service.sendForExistingToken(userWithMandat(), "abc123", request);

        assertThat(result).isFalse();
        verify(systemMailSender, never()).sendSystemMail(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void sendForExistingToken_liefertFalse_wennKeinMailkonto() {
        when(setupConfigService.getSystemMailAccountId()).thenReturn(null);

        boolean result = service.sendForExistingToken(userWithMandat(), "abc123", request);

        assertThat(result).isFalse();
        verify(systemMailSender, never()).sendSystemMail(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void sendForExistingToken_liefertFalse_wennVersandFehlschlaegt() {
        when(systemMailSender.sendSystemMail(anyLong(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(false);

        boolean result = service.sendForExistingToken(userWithMandat(), "abc123", request);

        assertThat(result).isFalse();
    }

    @Test
    void sendForExistingToken_liefertFalse_wennVersandException() {
        when(systemMailSender.sendSystemMail(anyLong(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("SMTP Fehler"));

        boolean result = service.sendForExistingToken(userWithMandat(), "abc123", request);

        assertThat(result).isFalse();
    }

    @Test
    void sendForExistingToken_ignoriertXForwardedHost_ohneKonfigurierteBaseUrl() {
        // X-Forwarded-Header duerfen NICHT im Magic-Link landen (Phishing-Vektor)
        when(request.getHeader("X-Forwarded-Host")).thenReturn("evil.com");

        service.sendForExistingToken(userWithMandat(), "abc123", request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(systemMailSender).sendSystemMail(anyLong(), anyString(), anyString(), bodyCaptor.capture(), eq(false));
        assertThat(bodyCaptor.getValue()).doesNotContain("evil.com");
        assertThat(bodyCaptor.getValue()).contains("https://app.example.com/login/ott?token=abc123");
    }

    @Test
    void sendForExistingToken_nutztKonfiguriertePublicBaseUrl() {
        when(properties.getPublicBaseUrl()).thenReturn("https://richtig.example.com");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("evil.com");

        service.sendForExistingToken(userWithMandat(), "abc123", request);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(systemMailSender).sendSystemMail(anyLong(), anyString(), anyString(), bodyCaptor.capture(), eq(false));
        assertThat(bodyCaptor.getValue()).doesNotContain("evil.com");
        assertThat(bodyCaptor.getValue()).contains("https://richtig.example.com/login/ott?token=abc123");
    }

    @Test
    void generateAndSend_generiertTokenUndVersendet() {
        MyUserEntity user = userWithMandat();
        when(userRepository.findByUsername("user@example.com")).thenReturn(user);
        when(oneTimeTokenService.generate(any(GenerateOneTimeTokenRequest.class)))
                .thenReturn(new DefaultOneTimeToken("genToken", "user@example.com", Instant.now().plusSeconds(600)));

        boolean result = service.generateAndSend("user@example.com", request);

        assertThat(result).isTrue();
        verify(oneTimeTokenService).generate(any(GenerateOneTimeTokenRequest.class));
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(systemMailSender).sendSystemMail(anyLong(), eq("user@example.com"), anyString(),
                bodyCaptor.capture(), eq(false));
        assertThat(bodyCaptor.getValue()).contains("/login/ott?token=genToken");
    }

    @Test
    void generateAndSend_liefertFalse_wennFeatureDeaktiviert() {
        MyUserEntity user = userWithMandat();
        when(userRepository.findByUsername("user@example.com")).thenReturn(user);
        when(setupConfigService.isMagicLinkEnabled("test")).thenReturn(false);

        boolean result = service.generateAndSend("user@example.com", request);

        assertThat(result).isFalse();
        verify(oneTimeTokenService, never()).generate(any());
        verify(systemMailSender, never()).sendSystemMail(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void generateAndSend_liefertFalse_wennUnbekannterUser() {
        when(userRepository.findByUsername("unbekannt@example.com")).thenReturn(null);

        boolean result = service.generateAndSend("unbekannt@example.com", request);

        assertThat(result).isFalse();
        verify(oneTimeTokenService, never()).generate(any());
        verify(systemMailSender, never()).sendSystemMail(any(), any(), any(), any(), anyBoolean());
    }
}
