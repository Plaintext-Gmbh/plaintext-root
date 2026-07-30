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
 * Reine, testbare TOTP-Kernlogik (RFC 6238): Secret-Generierung, {@code otpauth://}-URI +
 * QR-Data-URI fuer die Einrichtung, zeitfenster-tolerante Code-Pruefung sowie Erzeugung und
 * Hashing von Einmal-Recovery-Codes.
 *
 * <p>Der Service haelt <b>keinen</b> User-Zustand und keine Session – er rechnet nur. Das
 * Einloesen eines Recovery-Codes (Entfernen aus dem gespeicherten Set) passiert bewusst in
 * {@link TotpAuthenticationService}, wo es transaktional/atomar gegen die Entity laeuft.
 *
 * <p><b>Recovery-Codes werden nie im Klartext gespeichert.</b> Analog zu
 * {@code HashedOneTimeTokenService} wird SHA-256 (Hex) verwendet; der Klartext wird dem User
 * genau einmal bei der Einrichtung angezeigt.
 */
@Service
@Slf4j
public class TotpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Zeichen fuer Recovery-Codes: ohne leicht verwechselbare Zeichen (0/O, 1/I/L). */
    private static final char[] RECOVERY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int RECOVERY_CODE_GROUP = 4;
    private static final int RECOVERY_CODE_GROUPS = 3; // -> Format XXXX-XXXX-XXXX

    private final PlaintextSecurityProperties.TotpProperties properties;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier codeVerifier;
    private final TimeProvider timeProvider = new SystemTimeProvider();

    public TotpService(PlaintextSecurityProperties securityProperties) {
        this.properties = securityProperties.getTotp();
        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        // Toleranz gegen Uhr-Drift: +/- N 30-Sekunden-Fenster (RFC 6238 empfiehlt 1).
        verifier.setAllowedTimePeriodDiscrepancy(Math.max(0, properties.getAllowedTimePeriodDiscrepancy()));
        this.codeVerifier = verifier;
    }

    /**
     * Erzeugt ein frisches Base32-TOTP-Secret. Wird bei der Einrichtung generiert und erst
     * nach erfolgreicher Code-Bestaetigung persistiert/aktiviert.
     */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    /**
     * Baut die {@code otpauth://}-URI (Standard-Format, das Authenticator-Apps per QR lesen).
     *
     * @param secret  Base32-Secret
     * @param account Kontobezeichnung (i.d.R. Username/E-Mail)
     */
    public String buildOtpAuthUri(String secret, String account) {
        QrData data = qrData(secret, account);
        return data.getUri();
    }

    /**
     * Erzeugt einen QR-Code als {@code data:image/png;base64,...}-URI, direkt in ein
     * {@code <img src="...">} einbettbar (kein separater Endpunkt, kein Ablegen auf Platte).
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
                .algorithm(HashingAlgorithm.SHA1) // Authenticator-App-Standard
                .digits(6)
                .period(30)
                .build();
    }

    /**
     * Prueft einen 6-stelligen TOTP-Code gegen das Secret innerhalb des konfigurierten
     * Zeitfensters. {@code false} bei null/leer/ungueltig – nie eine Exception nach aussen.
     */
    public boolean verifyCode(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        return codeVerifier.isValidCode(secret, code.trim());
    }

    /**
     * Generiert {@code n} Recovery-Codes im Klartext (Format {@code XXXX-XXXX-XXXX}).
     * Die Klartext-Liste wird dem User genau einmal angezeigt; gespeichert werden nur die
     * Hashes ({@link #hashRecoveryCode(String)}).
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
     * Hasht eine ganze Klartext-Liste in ein Set gehashter Codes (fuer die Persistenz).
     * Vergleich/Einloesung ist gross-/kleinschreibungs- und bindestrich-unabhaengig.
     */
    public Set<String> hashRecoveryCodes(List<String> plaintextCodes) {
        Set<String> hashed = new LinkedHashSet<>();
        for (String code : plaintextCodes) {
            hashed.add(hashRecoveryCode(code));
        }
        return hashed;
    }

    /**
     * Hasht einen einzelnen Recovery-Code (normalisiert: Bindestriche entfernt, uppercase)
     * mit SHA-256 (Hex). Deterministisch, damit ein eingegebener Code den gespeicherten
     * Hash treffen kann.
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

    /** Normalisiert Eingabe (Bindestriche/Whitespace weg, uppercase). */
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
