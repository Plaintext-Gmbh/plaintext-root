/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.totp;

import ch.plaintext.boot.plugins.security.PlaintextSecurityProperties;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests of the TOTP core logic: code verification (valid/invalid/time window) and
 * recovery code hashing.
 */
class TotpServiceTest {

    private TotpService totpService;
    private final DefaultCodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final SystemTimeProvider timeProvider = new SystemTimeProvider();

    @BeforeEach
    void setUp() {
        PlaintextSecurityProperties props = new PlaintextSecurityProperties();
        props.getTotp().setEnabled(true);
        props.getTotp().setAllowedTimePeriodDiscrepancy(1);
        props.getTotp().setRecoveryCodeCount(10);
        totpService = new TotpService(props);
    }

    /** Generates a valid TOTP code for the current 30s window (+ offset in windows). */
    private String codeForCurrentWindow(String secret, int windowOffset) throws Exception {
        long counter = timeProvider.getTime() / 30 + windowOffset;
        return codeGenerator.generate(secret, counter);
    }

    @Test
    void generateSecret_liefertNichtLeeresBase32() {
        String secret = totpService.generateSecret();
        assertNotNull(secret);
        assertFalse(secret.isBlank());
        // DefaultSecretGenerator returns base32 (A-Z2-7), default length 32.
        assertTrue(secret.matches("[A-Z2-7]+"), "Secret muss Base32 sein, war: " + secret);
    }

    @Test
    void verifyCode_akzeptiertGueltigenCode() throws Exception {
        String secret = totpService.generateSecret();
        String code = codeForCurrentWindow(secret, 0);
        assertTrue(totpService.verifyCode(secret, code), "Aktueller Code muss gueltig sein");
    }

    @Test
    void verifyCode_lehntFalschenCodeAb() {
        String secret = totpService.generateSecret();
        assertFalse(totpService.verifyCode(secret, "000000"));
        assertFalse(totpService.verifyCode(secret, "123456"));
    }

    @Test
    void verifyCode_lehntNullUndLeerAb() {
        String secret = totpService.generateSecret();
        assertFalse(totpService.verifyCode(secret, null));
        assertFalse(totpService.verifyCode(secret, ""));
        assertFalse(totpService.verifyCode(secret, "   "));
        assertFalse(totpService.verifyCode(null, "123456"));
    }

    @Test
    void verifyCode_akzeptiertBenachbartesFensterInnerhalbToleranz() throws Exception {
        String secret = totpService.generateSecret();
        // +/- 1 window is permitted (allowedTimePeriodDiscrepancy=1).
        assertTrue(totpService.verifyCode(secret, codeForCurrentWindow(secret, -1)),
                "Code aus dem vorherigen Fenster muss innerhalb der Toleranz gelten");
        assertTrue(totpService.verifyCode(secret, codeForCurrentWindow(secret, 1)),
                "Code aus dem naechsten Fenster muss innerhalb der Toleranz gelten");
    }

    @Test
    void verifyCode_lehntFensterAusserhalbToleranzAb() throws Exception {
        String secret = totpService.generateSecret();
        // 5 windows away lies far outside the +/-1 tolerance.
        assertFalse(totpService.verifyCode(secret, codeForCurrentWindow(secret, 5)),
                "Code weit ausserhalb des Zeitfensters muss abgelehnt werden");
    }

    @Test
    void recoveryCodes_werdenInKonfigurierterAnzahlErzeugt() {
        List<String> codes = totpService.generateRecoveryCodes();
        assertEquals(10, codes.size());
        // Format XXXX-XXXX-XXXX
        for (String c : codes) {
            assertTrue(c.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"), "Unerwartetes Format: " + c);
        }
    }

    @Test
    void hashRecoveryCode_istDeterministischUndNormalisiert() {
        String hashed = totpService.hashRecoveryCode("ABCD-EFGH-JKLM");
        assertEquals(64, hashed.length(), "SHA-256 Hex ist 64 Zeichen");
        // Hyphens/whitespace/case must not change the hash.
        assertEquals(hashed, totpService.hashRecoveryCode("abcdefghjklm"));
        assertEquals(hashed, totpService.hashRecoveryCode(" ABCD EFGH JKLM "));
        assertNotEquals(hashed, totpService.hashRecoveryCode("ZZZZ-ZZZZ-ZZZZ"));
    }

    @Test
    void hashRecoveryCodes_hashtGesamteListe() {
        List<String> plain = totpService.generateRecoveryCodes();
        Set<String> hashed = totpService.hashRecoveryCodes(plain);
        assertEquals(plain.size(), hashed.size());
        // No plain-text code appears in the hashed set.
        for (String p : plain) {
            assertFalse(hashed.contains(p));
            assertTrue(hashed.contains(totpService.hashRecoveryCode(p)));
        }
    }

    @Test
    void otpauthUri_enthaeltSecretUndIssuer() {
        String secret = totpService.generateSecret();
        String uri = totpService.buildOtpAuthUri(secret, "alice@example.com");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=" + secret));
        assertTrue(uri.contains("issuer=Plaintext"));
    }

    @Test
    void qrCode_istPngDataUri() {
        String secret = totpService.generateSecret();
        String dataUri = totpService.generateQrCodeDataUri(secret, "alice@example.com");
        assertNotNull(dataUri);
        assertTrue(dataUri.startsWith("data:image/png;base64,"), "War: " + dataUri.substring(0, Math.min(40, dataUri.length())));
    }
}
