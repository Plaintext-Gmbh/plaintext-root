/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.selfservice;

import ch.plaintext.SystemMailSender;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyRememberMeRepository;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.mailtemplate.IMailTemplateProvider;
import ch.plaintext.mailtemplate.IMailTemplateProvider.RenderedMail;
import ch.plaintext.settings.ISetupConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Token-based password reset flow.
 *
 * <p>Two phases mirror {@link RegistrationService}:
 * <ol>
 *   <li>{@link #startReset(String, String, String)} — issue a one-time token
 *       for an existing user and queue a reset e-mail. The response is the
 *       same regardless of whether the username exists, so the form does not
 *       leak account presence.</li>
 *   <li>{@link #completeReset(String, String)} — consume the token and
 *       replace the bcrypt password hash on the {@code MyUserEntity}.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepository;
    private final MyUserRepository userRepository;
    private final MyRememberMeRepository rememberMeRepository;
    private final ISetupConfigService setupConfigService;
    private final ObjectProvider<SystemMailSender> systemMailSenderProvider;
    private final PasswordEncoder passwordEncoder;
    private final SelfServiceProperties properties;
    private final IMailTemplateProvider mailTemplateProvider;
    /**
     * SECURITY (Karte 314, Punkt 9): Registry der aktiven HTTP-Sessions. Als
     * {@link ObjectProvider}, weil das Modul plaintext-admin-sessions optional ist — fehlt es,
     * bleibt der Reset funktionsfaehig und nur die Session-Invalidierung entfaellt.
     */
    private final ObjectProvider<ch.plaintext.sessions.service.HttpSessionRegistry> sessionRegistryProvider;

    @Transactional
    public ResetOutcome startReset(String username, String mandat, String publicBaseUrl) {
        if (username == null || username.isBlank() || mandat == null || mandat.isBlank()) {
            return ResetOutcome.INVALID_INPUT;
        }
        if (!setupConfigService.isPasswordResetLinkEnabled(mandat)) {
            return ResetOutcome.DISABLED;
        }
        String normalised = username.trim().toLowerCase();
        MyUserEntity user = userRepository.findByUsername(normalised);
        if (user == null) {
            log.info("Password-reset request for unknown username on mandat={}", mandat);
            return ResetOutcome.ACCEPTED;
        }

        // SECURITY (Karte 307, K2.3): Klartext-Token nur im Link, in der DB nur der SHA-256-Hash.
        String rawToken = generateToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(SelfServiceTokenHash.sha256Hex(rawToken));
        token.setUsername(normalised);
        token.setMandat(mandat);
        Instant now = Instant.now();
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(properties.getPasswordResetTokenTtl()));
        tokenRepository.save(token);

        String link = buildLink(publicBaseUrl, "/password-reset/confirm", rawToken);
        sendResetEmail(mandat, normalised, link);
        return ResetOutcome.ACCEPTED;
    }

    @Transactional
    public ResetResult completeReset(String tokenValue, String newPassword) {
        if (tokenValue == null || tokenValue.isBlank()
                || newPassword == null || newPassword.length() < 8) {
            return ResetResult.invalid();
        }
        String hash = SelfServiceTokenHash.sha256Hex(tokenValue);
        Optional<PasswordResetToken> tokenOpt = tokenRepository.findByTokenHash(hash);
        if (tokenOpt.isEmpty()) {
            return ResetResult.invalid();
        }
        PasswordResetToken token = tokenOpt.get();
        Instant now = Instant.now();
        // SECURITY (Karte 307, K2.3): ATOMAR einloesen (bedingtes UPDATE) statt check-then-set — verhindert
        // TOCTOU/Replay bei parallelen Aufrufen. n==1 => wir haben den Token exklusiv verbraucht.
        if (tokenRepository.consumeToken(hash, now) != 1) {
            return ResetResult.invalid();
        }
        MyUserEntity user = userRepository.findByUsername(token.getUsername());
        if (user == null) {
            return ResetResult.invalid();
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // SECURITY (Karte 307, K2.3): nach dem Passwort-Reset die persistenten Auto-Login-Tokens
        // (Remember-Me / PERSISTENT_LOGINS) des Users invalidieren, damit ein zuvor gestohlenes
        // Remember-Me-Cookie nicht ueber den Reset hinweg weiterlebt. (HTTP-Sessions sind kurzlebig
        // und ohne Session-Registry nicht enumerierbar; der persistente Vektor ist Remember-Me.)
        rememberMeRepository.deleteAllByUsername(token.getUsername());

        // SECURITY (Karte 314, Punkt 9): zusaetzlich die noch AKTIVEN HTTP-Sessions beenden.
        // Bisher wurden nur die persistenten Remember-Me-Tokens geloescht — wer bereits eine
        // offene Session auf dem Konto hatte (genau der Fall, in dem ein Betroffener sein
        // Passwort zuruecksetzt), behielt seinen Zugriff bis zum Session-Timeout und der Reset
        // war als Wiederherstellungsmassnahme wirkungslos.
        ch.plaintext.sessions.service.HttpSessionRegistry sessionRegistry =
                sessionRegistryProvider.getIfAvailable();
        if (sessionRegistry != null) {
            sessionRegistry.invalidateSessionsOfUser(token.getUsername());
        } else {
            log.warn("Session-Registry nicht verfuegbar — aktive Sessions von '{}' konnten nach dem "
                    + "Passwort-Reset nicht invalidiert werden.", token.getUsername());
        }

        log.info("Password reset completed for {} on mandat={}", token.getUsername(), token.getMandat());
        return ResetResult.success(token.getUsername());
    }

    private void sendResetEmail(String mandat, String to, String link) {
        SystemMailSender sender = systemMailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("Password-reset e-mail not sent (no SystemMailSender / Mailbox unavailable), mandat={}", mandat);
            return;
        }
        Long accountId = setupConfigService.getSystemMailAccountId();
        if (accountId == null) {
            log.warn("Password-reset e-mail not sent: no GLOBAL system mail account configured (mandat={})", mandat);
            return;
        }
        String defaultBetreff = "Passwort zurücksetzen";
        String defaultBody = """
                Hallo,

                jemand hat angefragt, das Passwort für dieses Konto zurückzusetzen.
                Verwenden Sie den folgenden Link (gültig für {stunden} Stunden):

                {link}

                Falls Sie das nicht waren, ignorieren Sie diese Nachricht — Ihr
                aktuelles Passwort bleibt unverändert.
                """;
        RenderedMail mail = mailTemplateProvider.render(mandat, "auth.password-reset", defaultBetreff, defaultBody,
                Map.of("stunden", String.valueOf(properties.getPasswordResetTokenTtl().toHours()), "link", link));
        try {
            if (!sender.sendSystemMail(accountId, to, mail.betreff(), mail.body(), false)) {
                log.warn("Password-reset e-mail could not be sent for mandat={}", mandat);
            }
        } catch (Exception e) {
            log.warn("Could not send password-reset e-mail for mandat={}: {}", mandat, e.getMessage());
        }
    }

    private String buildLink(String requestBaseUrl, String path, String token) {
        String base = properties.getPublicBaseUrl().isBlank()
                ? requestBaseUrl
                : properties.getPublicBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path + "?token=" + token;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public enum ResetOutcome {
        ACCEPTED, DISABLED, INVALID_INPUT
    }

    public record ResetResult(boolean ok, String username) {
        public static ResetResult success(String username) {
            return new ResetResult(true, username);
        }
        public static ResetResult invalid() {
            return new ResetResult(false, null);
        }
    }
}
