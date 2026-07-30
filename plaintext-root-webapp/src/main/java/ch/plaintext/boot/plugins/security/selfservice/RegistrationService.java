/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.selfservice;

import ch.plaintext.SystemMailSender;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
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
 * Drives the e-mail-verified self-registration flow.
 *
 * <p>Two phases:
 * <ol>
 *   <li>{@link #startRegistration(String, String, String)} — create or refresh
 *       a {@link RegistrationToken} for the address and queue a verification
 *       e-mail. We do not create the {@code MyUserEntity} yet; doing so would
 *       expose username-existence to anyone who hits the form.</li>
 *   <li>{@link #completeRegistration(String, String)} — consume a token and
 *       persist a {@code MyUserEntity} with the chosen password and the
 *       per-mandant default role set.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RegistrationTokenRepository tokenRepository;
    private final MyUserRepository userRepository;
    private final ISetupConfigService setupConfigService;
    private final ObjectProvider<SystemMailSender> systemMailSenderProvider;
    private final PasswordEncoder passwordEncoder;
    private final SelfServiceProperties properties;
    private final IMailTemplateProvider mailTemplateProvider;

    /**
     * Initiate registration for {@code email} on {@code mandat}.
     *
     * <p>Returns silently if the mandant has self-registration disabled or if
     * a {@link MyUserEntity} with this username already exists, so the caller
     * cannot probe for existing accounts. The verification e-mail is sent via
     * the configured GLOBAL system mail account (Mailbox); delivery is out-of-band.
     */
    @Transactional
    public RegistrationOutcome startRegistration(String email, String mandat, String publicBaseUrl) {
        if (email == null || email.isBlank() || mandat == null || mandat.isBlank()) {
            return RegistrationOutcome.INVALID_INPUT;
        }
        if (!setupConfigService.isSelfRegistrationEnabled(mandat)) {
            return RegistrationOutcome.DISABLED;
        }
        String normalised = email.trim().toLowerCase();
        if (userRepository.findByUsername(normalised) != null) {
            log.info("Self-registration rejected: user already exists (mandat={})", mandat);
            return RegistrationOutcome.ACCEPTED;
        }

        // SECURITY (Karte 307, K2.3): Klartext-Token nur im Link, in der DB nur der SHA-256-Hash.
        String rawToken = generateToken();
        RegistrationToken token = new RegistrationToken();
        token.setTokenHash(SelfServiceTokenHash.sha256Hex(rawToken));
        token.setEmail(normalised);
        token.setMandat(mandat);
        Instant now = Instant.now();
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(properties.getRegistrationTokenTtl()));
        tokenRepository.save(token);

        String link = buildLink(publicBaseUrl, "/register/verify", rawToken);
        sendVerificationEmail(mandat, normalised, link);
        return RegistrationOutcome.ACCEPTED;
    }

    /**
     * Validate a token and create the {@link MyUserEntity}. The token is
     * marked consumed in the same transaction.
     */
    @Transactional
    public RegistrationResult completeRegistration(String tokenValue, String password) {
        if (tokenValue == null || tokenValue.isBlank()
                || password == null || password.length() < 8) {
            return RegistrationResult.invalid();
        }
        String hash = SelfServiceTokenHash.sha256Hex(tokenValue);
        Optional<RegistrationToken> tokenOpt = tokenRepository.findByTokenHash(hash);
        if (tokenOpt.isEmpty()) {
            return RegistrationResult.invalid();
        }
        RegistrationToken token = tokenOpt.get();
        Instant now = Instant.now();
        // SECURITY (Karte 307, K2.3): ATOMAR einloesen (bedingtes UPDATE) statt check-then-set —
        // verhindert TOCTOU/Replay bei parallelen Aufrufen. n==1 => Token exklusiv verbraucht.
        if (tokenRepository.consumeToken(hash, now) != 1) {
            return RegistrationResult.invalid();
        }
        if (userRepository.findByUsername(token.getEmail()) != null) {
            // Token ist bereits (atomar) verbraucht; ein Doppel-Konto entsteht nicht.
            return RegistrationResult.invalid();
        }
        MyUserEntity user = new MyUserEntity();
        user.setUsername(token.getEmail());
        user.setPassword(passwordEncoder.encode(password));
        // Konvention: der NACKTE Rollenname wird gespeichert; MyUserDetailsService praefixt beim
        // Login zu "ROLE_". Frueher stand hier faelschlich "ROLE_USER" -> die Authority wurde zu
        // "ROLE_ROLE_USER" (wirkungslos). Korrekt ist "user" (Karte 306, Bestand per Flyway migriert).
        user.addRole("user");
        user.addRole("PROPERTY_MANDAT_" + token.getMandat().toLowerCase());
        userRepository.save(user);

        log.info("Self-registration completed for {} on mandat={}", token.getEmail(), token.getMandat());
        return RegistrationResult.success(token.getEmail());
    }

    private void sendVerificationEmail(String mandat, String to, String link) {
        SystemMailSender sender = systemMailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("Verification e-mail not sent (no SystemMailSender / Mailbox unavailable), mandat={}", mandat);
            return;
        }
        Long accountId = setupConfigService.getSystemMailAccountId();
        if (accountId == null) {
            log.warn("Verification e-mail not sent: no GLOBAL system mail account configured (mandat={})", mandat);
            return;
        }
        String defaultBetreff = "Bitte bestätigen Sie Ihre E-Mail-Adresse";
        String defaultBody = """
                Hallo,

                jemand hat mit dieser E-Mail-Adresse ein Konto angefragt.
                Bestätigen Sie die Adresse über den folgenden Link
                (gültig für {stunden} Stunden):

                {link}

                Falls Sie das nicht waren, ignorieren Sie diese Nachricht.
                """;
        RenderedMail mail = mailTemplateProvider.render(mandat, "auth.registration", defaultBetreff, defaultBody,
                Map.of("stunden", String.valueOf(properties.getRegistrationTokenTtl().toHours()), "link", link));
        try {
            if (!sender.sendSystemMail(accountId, to, mail.betreff(), mail.body(), false)) {
                log.warn("Verification e-mail could not be sent for mandat={}", mandat);
            }
        } catch (Exception e) {
            log.warn("Could not send verification e-mail for mandat={}: {}", mandat, e.getMessage());
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

    public enum RegistrationOutcome {
        ACCEPTED, DISABLED, INVALID_INPUT
    }

    public record RegistrationResult(boolean ok, String username) {
        public static RegistrationResult success(String username) {
            return new RegistrationResult(true, username);
        }
        public static RegistrationResult invalid() {
            return new RegistrationResult(false, null);
        }
    }
}
