/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.apitoken.JwtTokenService.JwtValidationResult;
import ch.plaintext.boot.plugins.secret.VaultwardenSecretService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests für die graceful (dual-key) Signaturschlüssel-Rotation in {@link JwtTokenService}.
 *
 * <ul>
 *   <li>{@link #verifyWithAnyKeyMitFrischenKeypairs()} prüft die reine Multi-Key-Verifikation
 *       ({@link JwtTokenService#verifyWithAnyKey(String, List)}) mit im Test frisch erzeugten
 *       RSA-Keypairs — es werden KEINE echten Prod-Schlüssel benötigt.</li>
 *   <li>{@link #classpathDevKeyRoundtrip()} sichert als Regression, dass der Classpath-Dev-Key
 *       (Signatur) + der Dev-Public in der Validierungsliste einen vollständigen Sign/Validate-
 *       Roundtrip erlauben (bestehendes Verhalten bleibt grün).</li>
 * </ul>
 */
class JwtTokenServiceTest {

    private static KeyPair keyA;
    private static KeyPair keyB;
    private static KeyPair keyC;

    @BeforeAll
    static void generateKeys() throws Exception {
        keyA = rsaKeyPair();
        keyB = rsaKeyPair();
        keyC = rsaKeyPair();
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    /** Signiert einen kurzlebigen, gültigen Token mit dem gegebenen privaten Schlüssel. */
    private static String sign(PrivateKey key) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("42")
                .claim("userId", 42L)
                .claim("mandat", "plaintext")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.RS256)
                .compact();
    }

    /** Instanz ohne Vault (getIfAvailable() -> null durch Mockito-Default). */
    private static JwtTokenService serviceWithoutVault() {
        @SuppressWarnings("unchecked")
        ObjectProvider<VaultwardenSecretService> provider = mock(ObjectProvider.class);
        return new JwtTokenService(provider);
    }

    @Test
    void verifyWithAnyKeyMitFrischenKeypairs() {
        JwtTokenService service = serviceWithoutVault();

        PublicKey pubA = keyA.getPublic();
        PublicKey pubB = keyB.getPublic();

        String tokenA = sign(keyA.getPrivate());
        String tokenB = sign(keyB.getPrivate());
        String tokenC = sign(keyC.getPrivate());

        // Token mit keyA validiert, wenn pubA in der Liste ist (allein und gemischt, Reihenfolge egal).
        Optional<Claims> a1 = service.verifyWithAnyKey(tokenA, List.of(pubA));
        assertTrue(a1.isPresent(), "Token mit keyA muss gegen pubA verifizieren");
        assertEquals("42", a1.get().getSubject());

        assertTrue(service.verifyWithAnyKey(tokenA, List.of(pubB, pubA)).isPresent(),
                "Token mit keyA muss auch verifizieren, wenn pubA nur EINER von mehreren Keys ist");

        // Token mit keyB validiert, wenn pubB in der Liste ist.
        assertTrue(service.verifyWithAnyKey(tokenB, List.of(pubA, pubB)).isPresent(),
                "Token mit keyB muss gegen pubB verifizieren");

        // Token mit keyC (nicht in der Liste) -> empty.
        assertTrue(service.verifyWithAnyKey(tokenC, List.of(pubA, pubB)).isEmpty(),
                "Token mit keyC darf NICHT verifizieren, wenn pubC nicht in der Liste ist");
    }

    @Test
    void classpathDevKeyRoundtrip() {
        JwtTokenService service = serviceWithoutVault();
        // Lädt Classpath-Dev-Private + alle vorhandenen Public-Keys (inkl. Dev-Public).
        service.init();

        String token = service.generateToken(7L, "plaintext", "u@x.ch", "cli", 90);

        Optional<JwtValidationResult> result = service.validateToken(token);
        assertTrue(result.isPresent(), "Mit Classpath-Dev-Key signierter Token muss validieren");
        assertEquals(7L, result.get().userId());
        assertEquals("plaintext", result.get().mandat());
        assertEquals("u@x.ch", result.get().email());
        assertEquals("cli", result.get().tokenName());
    }

    @Test
    void fremderKeyWirdAbgelehnt() {
        JwtTokenService service = serviceWithoutVault();
        service.init();

        // Token, das mit einem NICHT im Classpath hinterlegten Schlüssel signiert wurde.
        String foreignToken = sign(keyC.getPrivate());

        assertFalse(service.validateToken(foreignToken).isPresent(),
                "Ein mit fremdem Schlüssel signierter Token darf nicht validieren");
        assertTrue(service.isExpired(foreignToken),
                "isExpired() muss für einen ungültigen Token true liefern");
    }

    // ------------------------------------------------------------ Dev-Key unter PROD nicht laden (Karte 305)

    /**
     * Karte 347: In PROD bezieht jede Instanz ihren Schluessel ausschliesslich aus dem Vault-Item.
     * Ist kein {@code plaintext.jwt.private-key-vault-item} gesetzt (und kein File), MUSS die Instanz
     * fail-closed beim Init scheitern — statt still auf den Classpath-Dev-Key zurueckzufallen (genau
     * dieser Fallback war die Ursache von Bug 347: guild signierte mit dem Dev-Key, dessen Public 305
     * aus der PROD-Validierung nahm -> eigene Tokens 401).
     */
    @Test
    void prodOhneVaultItem_initSchlaegtFehlClosed() {
        JwtTokenService service = serviceWithoutVault();
        service.activeProfiles = "prod"; // PROD, aber kein Vault-Item konfiguriert
        assertThrows(IllegalStateException.class, service::init,
                "PROD ohne Vault-Item muss fail-closed scheitern (kein Classpath-Fallback, Karte 347)");
    }

    /** Gegenprobe: OHNE prod-Profil (Dev/Test) validiert der Dev-Key-Token weiterhin (Bestandsverhalten). */
    @Test
    void ohneProdProfil_devKeySignierterToken_validiertWeiterhin() {
        JwtTokenService service = serviceWithoutVault();
        service.activeProfiles = "dev";
        service.init();

        String devSignedToken = service.generateToken(7L, "plaintext", "u@x.ch", "cli", 90);

        assertTrue(service.validateToken(devSignedToken).isPresent(),
                "Ohne prod-Profil muss der Dev-Key-Token weiterhin validieren (kein Regressionsbruch)");
    }

    // ------------------------------------------------------------------ scope + jti (Task 006)

    @Test
    void scopeClaim_wirdGesetztUndZurueckgelesen() {
        JwtTokenService service = serviceWithoutVault();
        service.init();

        String token = service.generateToken(7L, "plaintext", "u@x.ch", "cli", 90, "EINTRAGEN");

        Optional<JwtValidationResult> result = service.validateToken(token);
        assertTrue(result.isPresent());
        assertEquals("EINTRAGEN", result.get().scope());
        assertTrue(result.get().jti() != null && !result.get().jti().isBlank(),
                "jti-Claim wird IMMER gesetzt, auch mit explizitem Scope");
    }

    @Test
    void fehlenderScopeClaim_gibtNull_jtiTrotzdemGesetzt() {
        JwtTokenService service = serviceWithoutVault();
        service.init();

        // Alter Aufrufer ohne scope-Argument (z.B. bestehende ApiTokenService-Callsite).
        String token = service.generateToken(7L, "plaintext", "u@x.ch", "cli", 90);

        Optional<JwtValidationResult> result = service.validateToken(token);
        assertTrue(result.isPresent());
        assertEquals(null, result.get().scope(), "Ohne scope-Argument wird kein Claim gesetzt");
        assertTrue(result.get().jti() != null && !result.get().jti().isBlank(),
                "jti wird auch ohne scope IMMER gesetzt (für künftige Revocation)");
    }

    @Test
    void jedesToken_bekommtEigeneJti() {
        JwtTokenService service = serviceWithoutVault();
        service.init();

        String jti1 = service.validateToken(service.generateToken(7L, "plaintext")).orElseThrow().jti();
        String jti2 = service.validateToken(service.generateToken(7L, "plaintext")).orElseThrow().jti();

        assertFalse(jti1.equals(jti2), "Zwei Tokens duerfen nicht dieselbe jti tragen");
    }
}
