/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.magiclink;

import ch.plaintext.arch.AllowRawScheduled;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.settings.ISetupConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * OneTimeTokenService mit SHA-256-Hashing: Klartext-Token wird nie in der DB gespeichert.
 * Bei deaktiviertem Magic-Link-Feature wird ein Dummy-Token zurueckgegeben (kein DB-Eintrag),
 * der beim Einloesen sicher fehlschlaegt – ohne User-Enumeration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@AllowRawScheduled // System-Waechter: stuendlicher Cleanup abgelaufener Einmal-Token direkt am Token-Service
public class HashedOneTimeTokenService implements OneTimeTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MagicLinkTokenRepository tokenRepository;
    private final MyUserRepository userRepository;
    private final ISetupConfigService setupConfigService;
    private final MagicLinkProperties properties;

    @Override
    @Transactional
    public OneTimeToken generate(GenerateOneTimeTokenRequest request) {
        String username = request.getUsername();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getTokenTtl());
        String rawToken = generateRawToken();

        MyUserEntity user = userRepository.findByUsername(username);
        if (user == null) {
            log.debug("MagicLink: unbekannter Username '{}', gebe Dummy-Token zurueck", username);
            return new DefaultOneTimeToken(rawToken, username, expiresAt);
        }

        String mandat = user.getMandat();
        if (mandat == null || !setupConfigService.isMagicLinkEnabled(mandat)) {
            log.debug("MagicLink: Feature fuer Mandat '{}' deaktiviert, gebe Dummy-Token zurueck", mandat);
            return new DefaultOneTimeToken(rawToken, username, expiresAt);
        }

        String tokenHash = hashToken(rawToken);
        MagicLinkToken token = new MagicLinkToken();
        token.setTokenHash(tokenHash);
        token.setUsername(username);
        token.setMandat(mandat);
        token.setIssuedAt(now);
        token.setExpiresAt(expiresAt);
        tokenRepository.save(token);

        log.info("MagicLink: Token generiert fuer '{}' (Mandat: {}, ablaufend: {})", username, mandat, expiresAt);
        return new DefaultOneTimeToken(rawToken, username, expiresAt);
    }

    @Override
    @Transactional
    public OneTimeToken consume(OneTimeTokenAuthenticationToken authenticationToken) {
        String rawToken = authenticationToken.getTokenValue();
        String tokenHash = hashToken(rawToken);
        Instant now = Instant.now();

        // Atomares bedingtes UPDATE statt READ->CHECK->UPDATE: verhindert TOCTOU-Race
        // (Doppelklick/Replay) – bei parallelen Aufrufen loest genau einer den Token ein.
        int updated = tokenRepository.consumeToken(tokenHash, now);
        if (updated == 0) {
            // Bewusst keine Unterscheidung unbekannt/abgelaufen/verwendet – keine Info-Leakage
            throw new InvalidOneTimeTokenException("Ungueltiger, abgelaufener oder bereits verwendeter Magic-Link-Token");
        }

        MagicLinkToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidOneTimeTokenException("Magic-Link-Token nach Einloesung nicht auffindbar"));

        log.info("MagicLink: Token erfolgreich eingeloest fuer '{}'", token.getUsername());
        return new DefaultOneTimeToken(rawToken, token.getUsername(), token.getExpiresAt());
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        tokenRepository.deleteExpiredBefore(Instant.now().minusSeconds(3600));
        log.debug("MagicLink: abgelaufene Tokens bereinigt");
    }

    static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
