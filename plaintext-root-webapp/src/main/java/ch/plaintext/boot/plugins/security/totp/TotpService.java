/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.totp;

import ch.plaintext.boot.plugins.security.PlaintextSecurityProperties;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, testable TOTP core logic (RFC 6238): secret generation, {@code otpauth://} URI +
 * QR data URI for the setup, time-window tolerant code checking as well as generation and
 * hashing of one-time recovery codes.
 *
 * <p>The service holds <b>no</b> user state and no session - it only computes. Redeeming
 * a recovery code (removing it from the stored set) deliberately happens in
 * {@link TotpAuthenticationService}, where it runs transactionally/atomically against the entity.
 *
 * <p><b>Recovery codes are never stored in clear text.</b> Analogously to
 * {@code HashedOneTimeTokenService} SHA-256 (hex) is used; the clear text is shown to the user
 * exactly once during the setup.
 */
@Service
@Slf4j
public class TotpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Characters for recovery codes: without easily confusable characters (0/O, 1/I/L). */
    private static final char[] RECOVERY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int RECOVERY_CODE_GROUP = 4;
    private static final int RECOVERY_CODE_GROUPS = 3; // -> format XXXX-XXXX-XXXX

    private final PlaintextSecurityProperties.TotpProperties properties;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier codeVerifier;
    private final TimeProvider timeProvider = new SystemTimeProvider();

    public TotpService(PlaintextSecurityProperties securityProperties) {
        this.properties = securityProperties.getTotp();
        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        // Tolerance against clock drift: +/- N 30-second windows (RFC 6238 recommends 1).
        verifier.setAllowedTimePeriodDiscrepancy(Math.max(0, properties.getAllowedTimePeriodDiscrepancy()));
        this.codeVerifier = verifier;
    }

    /**
     * Generates a fresh Base32 TOTP secret. It is generated during the setup and only
     * persisted/activated after a successful code confirmation.
     */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    /**
     * Builds the {@code otpauth://} URI (standard format that authenticator apps read via QR).
     *
     * @param secret  Base32 secret
     * @param account account designation (usually username/e-mail)
     */
    public String buildOtpAuthUri(String secret, String account) {
        QrData data = qrData(secret, account);
        return data.getUri();
    }

    /**
     * Generates a QR code as a {@code data:image/png;base64,...} URI, directly embeddable into an
     * {@code <img src="...">} (no separate endpoint, no storing on disk).
     */
    public String generateQrCodeDataUri(String secret, String account) {
        QrData data = qrData(secret, account);
        QrGenerator generator = new ZxingPngQrGenerator();
        try {
            byte[] imageData = generator.generate(data);
            return Utils.getDataUriForImage(imageData, generator.getImageMimeType());
        } catch (QrGenerationException e) {
            log.warn("TOTP: QR-Code-Generierung fehlgeschlagen: {}", e.getMessage());
            return null;
        }
    }

    private QrData qrData(String secret, String account) {
        return new QrData.Builder()
                .label(account)
                .secret(secret)
                .issuer(properties.getIssuer())
                .algorithm(HashingAlgorithm.SHA1) // authenticator app standard
                .digits(6)
                .period(30)
                .build();
    }

    /**
     * Checks a 6-digit TOTP code against the secret within the configured
     * time window. {@code false} on null/empty/invalid - never an exception to the outside.
     */
    public boolean verifyCode(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        return codeVerifier.isValidCode(secret, code.trim());
    }

    /**
     * Generates {@code n} recovery codes in clear text (format {@code XXXX-XXXX-XXXX}).
     * The clear-text list is shown to the user exactly once; only the hashes are stored
     * ({@link #hashRecoveryCode(String)}).
     */
    public List<String> generateRecoveryCodes() {
        int count = Math.max(1, properties.getRecoveryCodeCount());
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            codes.add(randomRecoveryCode());
        }
        return codes;
    }

    /**
     * Hashes a whole clear-text list into a set of hashed codes (for persistence).
     * Comparison/redemption is case- and hyphen-insensitive.
     */
    public Set<String> hashRecoveryCodes(List<String> plaintextCodes) {
        Set<String> hashed = new LinkedHashSet<>();
        for (String code : plaintextCodes) {
            hashed.add(hashRecoveryCode(code));
        }
        return hashed;
    }

    /**
     * Hashes a single recovery code (normalized: hyphens removed, uppercase)
     * with SHA-256 (hex). Deterministic, so that an entered code can match the stored
     * hash.
     */
    public String hashRecoveryCode(String code) {
        String normalized = normalizeRecoveryCode(code);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }

    /** Normalizes the input (hyphens/whitespace removed, uppercase). */
    public static String normalizeRecoveryCode(String code) {
        if (code == null) {
            return "";
        }
        return code.replaceAll("[\\s-]", "").toUpperCase();
    }

    private String randomRecoveryCode() {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < RECOVERY_CODE_GROUPS; g++) {
            if (g > 0) {
                sb.append('-');
            }
            for (int c = 0; c < RECOVERY_CODE_GROUP; c++) {
                sb.append(RECOVERY_ALPHABET[RANDOM.nextInt(RECOVERY_ALPHABET.length)]);
            }
        }
        return sb.toString();
    }
}
