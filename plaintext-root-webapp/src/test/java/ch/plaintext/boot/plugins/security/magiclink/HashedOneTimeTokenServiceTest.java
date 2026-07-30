/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.magiclink;

import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HashedOneTimeTokenServiceTest {

    @Mock
    private MagicLinkTokenRepository tokenRepository;
    @Mock
    private MyUserRepository userRepository;
    @Mock
    private ISetupConfigService setupConfigService;
    @Mock
    private MagicLinkProperties properties;

    @InjectMocks
    private HashedOneTimeTokenService service;

    private MyUserEntity testUser;

    @BeforeEach
    void setUp() {
        testUser = new MyUserEntity();
        testUser.setUsername("user@example.com");
        testUser.setMandat("testmandat");
        testUser.addRole("PROPERTY_MANDAT_TESTMANDAT");

        when(properties.getTokenTtl()).thenReturn(Duration.ofMinutes(10));
    }

    @Test
    void generate_speichertTokenHashInDb_wennAktiviert() {
        when(userRepository.findByUsername("user@example.com")).thenReturn(testUser);
        when(setupConfigService.isMagicLinkEnabled("testmandat")).thenReturn(true);
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OneTimeToken token = service.generate(new GenerateOneTimeTokenRequest("user@example.com"));

        assertThat(token).isNotNull();
        assertThat(token.getUsername()).isEqualTo("user@example.com");
        assertThat(token.getTokenValue()).isNotBlank();
        assertThat(token.getExpiresAt()).isAfter(Instant.now());

        ArgumentCaptor<MagicLinkToken> captor = ArgumentCaptor.forClass(MagicLinkToken.class);
        verify(tokenRepository).save(captor.capture());
        MagicLinkToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).isNotBlank();
        assertThat(saved.getTokenHash()).isNotEqualTo(token.getTokenValue());
        assertThat(saved.getUsername()).isEqualTo("user@example.com");
        assertThat(saved.getMandat()).isEqualTo("testmandat");
    }

    @Test
    void generate_speichertNichtsInDb_wennFeatureDeaktiviert() {
        when(userRepository.findByUsername("user@example.com")).thenReturn(testUser);
        when(setupConfigService.isMagicLinkEnabled("testmandat")).thenReturn(false);

        OneTimeToken token = service.generate(new GenerateOneTimeTokenRequest("user@example.com"));

        // Immer gleiches Verhalten nach aussen – kein Enumeration
        assertThat(token).isNotNull();
        assertThat(token.getUsername()).isEqualTo("user@example.com");
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void generate_speichertNichtsInDb_wennUserUnbekannt() {
        when(userRepository.findByUsername("unbekannt@example.com")).thenReturn(null);

        OneTimeToken token = service.generate(new GenerateOneTimeTokenRequest("unbekannt@example.com"));

        assertThat(token).isNotNull();
        assertThat(token.getUsername()).isEqualTo("unbekannt@example.com");
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void consume_gibtUserZurueck_beiGueltigemToken() {
        when(userRepository.findByUsername("user@example.com")).thenReturn(testUser);
        when(setupConfigService.isMagicLinkEnabled("testmandat")).thenReturn(true);
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OneTimeToken generated = service.generate(new GenerateOneTimeTokenRequest("user@example.com"));
        String rawToken = generated.getTokenValue();
        String hash = HashedOneTimeTokenService.hashToken(rawToken);

        MagicLinkToken dbToken = new MagicLinkToken();
        dbToken.setTokenHash(hash);
        dbToken.setUsername("user@example.com");
        dbToken.setMandat("testmandat");
        dbToken.setIssuedAt(Instant.now());
        dbToken.setExpiresAt(Instant.now().plusSeconds(600));

        when(tokenRepository.consumeToken(eq(hash), any(Instant.class))).thenReturn(1);
        when(tokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(dbToken));

        OneTimeToken consumed = service.consume(
                new OneTimeTokenAuthenticationToken(rawToken));

        assertThat(consumed.getUsername()).isEqualTo("user@example.com");
        assertThat(consumed.getExpiresAt()).isEqualTo(dbToken.getExpiresAt());
        verify(tokenRepository).consumeToken(eq(hash), any(Instant.class));
    }

    @Test
    void consume_wirftException_beiUnbekanntemToken() {
        when(tokenRepository.consumeToken(anyString(), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> service.consume(
                new OneTimeTokenAuthenticationToken("ungueltigerToken")))
                .isInstanceOf(InvalidOneTimeTokenException.class);
        // Kein SELECT nach fehlgeschlagenem UPDATE – keine Info-Leakage
        verify(tokenRepository, never()).findByTokenHash(anyString());
    }

    @Test
    void consume_wirftException_beiAbgelaufenenToken() {
        // Abgelaufener Token: das bedingte UPDATE (EXPIRES_AT > NOW) trifft keine Zeile
        when(tokenRepository.consumeToken(anyString(), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> service.consume(
                new OneTimeTokenAuthenticationToken("abgelaufenerToken")))
                .isInstanceOf(InvalidOneTimeTokenException.class);
    }

    @Test
    void consume_wirftException_beiVerbrauchemToken() {
        // Bereits eingeloester Token: das bedingte UPDATE (CONSUMED_AT IS NULL) trifft keine Zeile
        when(tokenRepository.consumeToken(anyString(), any(Instant.class))).thenReturn(0);

        OneTimeTokenAuthenticationToken token = new OneTimeTokenAuthenticationToken("verbrauchterToken");
        assertThatThrownBy(() -> service.consume(token))
                .isInstanceOf(InvalidOneTimeTokenException.class);
    }

    @Test
    void consume_verhindertDoppeleinloesung_beiRacecondition() {
        String rawToken = "racezustandToken";
        String hash = HashedOneTimeTokenService.hashToken(rawToken);

        MagicLinkToken dbToken = new MagicLinkToken();
        dbToken.setTokenHash(hash);
        dbToken.setUsername("user@example.com");
        dbToken.setMandat("testmandat");
        dbToken.setIssuedAt(Instant.now());
        dbToken.setExpiresAt(Instant.now().plusSeconds(600));

        // Atomares UPDATE: der erste Aufruf trifft die Zeile (1), der zweite nicht mehr (0)
        when(tokenRepository.consumeToken(eq(hash), any(Instant.class))).thenReturn(1, 0);
        when(tokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(dbToken));

        OneTimeToken erster = service.consume(new OneTimeTokenAuthenticationToken(rawToken));
        assertThat(erster.getUsername()).isEqualTo("user@example.com");

        assertThatThrownBy(() -> service.consume(
                new OneTimeTokenAuthenticationToken(rawToken)))
                .isInstanceOf(InvalidOneTimeTokenException.class);

        verify(tokenRepository, times(2)).consumeToken(eq(hash), any(Instant.class));
    }

    @Test
    void hashToken_istKonsistentUndNichtUmkehrbar() {
        String raw = "meinGeheimesToken";
        String hash1 = HashedOneTimeTokenService.hashToken(raw);
        String hash2 = HashedOneTimeTokenService.hashToken(raw);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 = 32 Bytes = 64 Hex-Zeichen
        assertThat(hash1).isNotEqualTo(raw);
    }
}
