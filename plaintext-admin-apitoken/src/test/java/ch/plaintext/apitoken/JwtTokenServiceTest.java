/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.apitoken.JwtTokenService.JwtValidationResult;
import ch.plaintext.boot.plugins.secret.VaultwardenSecretService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for the graceful (dual-key) signing key rotation in {@link JwtTokenService}.
 *
 * <ul>
 *   <li>{@link #verifyWithAnyKeyMitFrischenKeypairs()} checks the pure multi-key verification
 *       ({@link JwtTokenService#verifyWithAnyKey(String, List)}) with RSA key pairs generated
 *       freshly inside the test — NO real prod keys are needed.</li>
 *   <li>{@link #classpathDevKeyRoundtrip()} secures, as a regression, that the classpath dev key
 *       (signing) + the dev public key in the validation list allow a complete sign/validate
 *       roundtrip (existing behaviour stays green).</li>
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

    /** Signs a short-lived, valid token with the given private key. */
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

    /** Instance without a vault (getIfAvailable() -> null by Mockito default). */
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

        // Token with keyA validates if pubA is in the list (alone and mixed, order irrelevant).
        Optional<Claims> a1 = service.verifyWithAnyKey(tokenA, List.of(pubA));
        assertTrue(a1.isPresent(), "Token mit keyA muss gegen pubA verifizieren");
        assertEquals("42", a1.get().getSubject());

        assertTrue(service.verifyWithAnyKey(tokenA, List.of(pubB, pubA)).isPresent(),
                "Token mit keyA muss auch verifizieren, wenn pubA nur EINER von mehreren Keys ist");

        // Token with keyB validates if pubB is in the list.
        assertTrue(service.verifyWithAnyKey(tokenB, List.of(pubA, pubB)).isPresent(),
                "Token mit keyB muss gegen pubB verifizieren");

        // Token with keyC (not in the list) -> empty.
        assertTrue(service.verifyWithAnyKey(tokenC, List.of(pubA, pubB)).isEmpty(),
                "Token mit keyC darf NICHT verifizieren, wenn pubC nicht in der Liste ist");
    }

    @Test
    void classpathDevKeyRoundtrip() {
        JwtTokenService service = serviceWithoutVault();
        // Loads the classpath dev private key + all available public keys (incl. the dev public key).
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

        // Token that was signed with a key NOT stored on the classpath.
        String foreignToken = sign(keyC.getPrivate());

        assertFalse(service.validateToken(foreignToken).isPresent(),
                "Ein mit fremdem Schlüssel signierter Token darf nicht validieren");
        assertTrue(service.isExpired(foreignToken),
                "isExpired() muss für einen ungültigen Token true liefern");
    }

    // ------------------------------------------------------------ Do not load the dev key under PROD (card 305)

    /**
     * Card 347: In PROD every instance takes its key exclusively from the vault item.
     * If no {@code plaintext.jwt.private-key-vault-item} is set (and no file either), the instance
     * MUST fail closed during init — instead of silently falling back to the classpath dev key
     * (exactly that fallback was the cause of bug 347: guild signed with the dev key, whose public
     * key 305 had removed from PROD validation -> its own tokens got 401).
     */
    @Test
    void prodOhneVaultItem_initSchlaegtFehlClosed() {
        JwtTokenService service = serviceWithoutVault();
        service.activeProfiles = "prod"; // PROD, but no vault item configured
        assertThrows(IllegalStateException.class, service::init,
                "PROD ohne Vault-Item muss fail-closed scheitern (kein Classpath-Fallback, Karte 347)");
    }

    /** Counter-check: WITHOUT the prod profile (dev/test) the dev-key token still validates (as before). */
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

        // Old caller without a scope argument (e.g. the existing ApiTokenService call site).
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

    // ------------------------------------------------- Machine credential (card 635, signServiceToken)

    /** Claims of a service token, checked against the instance's own public keys. */
    private static Claims claimsOf(JwtTokenService service, String token) {
        return service.verifyWithAnyKey(token, service.getPublicKeysForPublication()).orElseThrow(
                () -> new AssertionError("Service-Token muss gegen den veroeffentlichten Public-Key verifizieren — "
                        + "sonst kann die Gegenstelle es ueber /.well-known/jwks.json nicht pruefen"));
    }

    @Test
    void serviceToken_istMitDemVeroeffentlichtenSchluesselPruefbar() {
        JwtTokenService service = serviceWithoutVault();
        service.init();

        String token = service.signServiceToken("guild-checkin-desk", "guild42-label-printer",
                java.time.Duration.ofMinutes(30));

        Claims claims = claimsOf(service, token);
        assertEquals("guild-checkin-desk", claims.getSubject());
        assertEquals(JwtTokenService.TOKEN_USE_SERVICE, claims.get("token_use", String.class));
        assertTrue(claims.getAudience().contains("guild42-label-printer"),
                "aud muss die Gegenstelle nennen, damit ein Ausweis nicht anderswo gilt");
        assertTrue(claims.getId() != null && !claims.getId().isBlank(), "jti wird immer gesetzt");
        assertTrue(claims.getExpiration().toInstant().isAfter(Instant.now()), "Ausweis darf nicht sofort abgelaufen sein");
    }

    /**
     * The security test of this change: a machine credential carries the same signature as an
     * API token, and it travels over the wire as a header — so it must not be usable as one.
     *
     * <p>The Javadoc used to say a missing {@code scope} claim would make it an ADMIN token. That
     * stopped being true with card 312: a missing claim is now fail-closed to READ
     * ({@code McpBearerTokenFilterTest.fehlenderScopeClaim_giltNurNochAlsRead_failClosed}), and
     * only the migration opt-out {@code legacy-scope-admin=true} restores the old behaviour.
     * Corrected on 30 August 2026; the test itself is unchanged and still asserts what matters —
     * a service token gets no API access at all.</p>
     */
    @Test
    void serviceToken_gibtKeinenApiZugriff() {
        JwtTokenService service = serviceWithoutVault();
        service.init();

        String ausweis = service.signServiceToken("guild-checkin-desk", "guild42-label-printer",
                java.time.Duration.ofMinutes(30));

        assertFalse(service.validateToken(ausweis).isPresent(),
                "Ein Maschinen-Ausweis darf NIE als API-Token validieren (sonst ADMIN ohne scope-Claim)");
    }

    @Test
    void serviceToken_gueltigkeitWirdNachUntenUndObenGeklemmt() {
        JwtTokenService service = serviceWithoutVault();
        service.init();

        Claims zuKurz = claimsOf(service, service.signServiceToken("desk", null, java.time.Duration.ofSeconds(1)));
        long sekundenKurz = zuKurz.getExpiration().toInstant().getEpochSecond()
                - zuKurz.getIssuedAt().toInstant().getEpochSecond();
        assertEquals(JwtTokenService.SERVICE_TOKEN_MIN_VALIDITY.toSeconds(), sekundenKurz,
                "Unter der Untergrenze wird auf die Untergrenze geklemmt, nicht abgelehnt");

        Claims zuLang = claimsOf(service, service.signServiceToken("desk", null, java.time.Duration.ofDays(30)));
        long sekundenLang = zuLang.getExpiration().toInstant().getEpochSecond()
                - zuLang.getIssuedAt().toInstant().getEpochSecond();
        assertEquals(JwtTokenService.SERVICE_TOKEN_MAX_VALIDITY.toSeconds(), sekundenLang,
                "Ueber der Obergrenze wird geklemmt — ein Maschinen-Ausweis bleibt kurzlebig");
    }

    @Test
    void serviceToken_ohneSubject_wirdAbgelehnt() {
        JwtTokenService service = serviceWithoutVault();
        service.init();

        java.time.Duration gueltigkeit = java.time.Duration.ofMinutes(5);

        assertThrows(IllegalArgumentException.class,
                () -> service.signServiceToken("  ", "printer", gueltigkeit),
                "Ohne subject kann die Gegenstelle den Aussteller nicht zuordnen");
    }

    @Test
    void serviceToken_issuerWirdGesetztWennKonfiguriert() {
        JwtTokenService service = serviceWithoutVault();
        service.issuer = "https://app.guild42.ch";
        service.init();

        Claims mitIssuer = claimsOf(service, service.signServiceToken("desk", null, java.time.Duration.ofMinutes(5)));
        assertEquals("https://app.guild42.ch", mitIssuer.getIssuer());

        JwtTokenService ohne = serviceWithoutVault();
        ohne.issuer = "";
        ohne.init();
        assertEquals(null, claimsOf(ohne, ohne.signServiceToken("desk", null, java.time.Duration.ofMinutes(5))).getIssuer(),
                "Leerer Wert laesst den Claim weg statt einen leeren iss zu schreiben");
    }

    /**
     * Card 804: Where the {@code iss} value comes from when nobody sets it explicitly.
     *
     * <p>The test checks the placeholder expression of the {@link Value} annotation itself, not the
     * field: the resolution happens in the Spring context, so a field that has been set would only
     * prove that {@code signServiceToken} writes a value that was set — which is already covered by
     * {@link #serviceToken_issuerWirdGesetztWennKonfiguriert()}. If someone changes the expression
     * back to {@code ${plaintext.jwt.issuer:}}, this test turns red (mutation check performed).
     */
    @Test
    void issuer_faelltAufDieBasisAdresseDerInstanzZurueck() throws Exception {
        String ausdruck = JwtTokenService.class.getDeclaredField("issuer")
                .getAnnotation(Value.class).value();

        assertEquals("https://app.guild42.ch",
                aufgeloest(ausdruck, Map.of("plaintext.baseurl", "https://app.guild42.ch")),
                "Ohne eigene Konfiguration ist der iss die oeffentliche Adresse dieser Instanz — "
                        + "nur so unterscheiden sich INT- und PROD-Ausweise, die denselben Schluessel teilen");

        assertEquals("http://192.168.1.224:1151",
                aufgeloest(ausdruck, Map.of("plaintext.baseurl", "http://192.168.1.224:1151")),
                "INT traegt seine eigene Adresse — der Fall, der ohne diesen Default nicht existierte");

        assertEquals("https://ausdruecklich.example",
                aufgeloest(ausdruck, Map.of("plaintext.baseurl", "https://app.guild42.ch",
                        "plaintext.jwt.issuer", "https://ausdruecklich.example")),
                "Ein ausdruecklich gesetzter Wert gewinnt weiterhin");

        assertEquals("", aufgeloest(ausdruck, Map.of()),
                "Ohne Basis-Adresse bleibt der Claim weg wie bisher — ein iss=localhost waere "
                        + "schlechter als gar keiner, weil er eine falsche Herkunft behauptet");
    }

    /** Resolves a property placeholder against exactly the values passed in. */
    private static String aufgeloest(String ausdruck, Map<String, Object> werte) {
        StandardEnvironment umgebung = new StandardEnvironment();
        umgebung.getPropertySources().addFirst(new MapPropertySource("test", werte));
        return umgebung.resolvePlaceholders(ausdruck);
    }

    /**
     * The contract through which business modules request the credential:
     * {@code ch.plaintext.ServiceTokenIssuer} from {@code plaintext-root-interfaces}. Without it a
     * module such as {@code plaintext-guild-events} would have to depend on
     * {@code plaintext-admin-apitoken} — that is, on the holder of the private key.
     */
    @Test
    void serviceToken_istUeberDenVertragAusRootInterfacesErreichbar() {
        JwtTokenService service = serviceWithoutVault();
        service.init();

        ch.plaintext.ServiceTokenIssuer vertrag = service;
        String token = vertrag.signServiceToken("guild-checkin-desk", "guild42-label-printer",
                java.time.Duration.ofMinutes(30));

        assertEquals("guild-checkin-desk", claimsOf(service, token).getSubject());
        assertFalse(service.validateToken(token).isPresent(),
                "Auch ueber den Vertrag ausgestellt bleibt der Ausweis kein API-Token");
    }

    @Test
    void serviceToken_ohneGeladeneSchluessel_meldetNichtBereit() {
        // deliberately NOT calling init(): the state during the vault wait at startup.
        JwtTokenService service = serviceWithoutVault();

        java.time.Duration gueltigkeit = java.time.Duration.ofMinutes(5);

        assertThrows(IllegalStateException.class,
                () -> service.signServiceToken("desk", "printer", gueltigkeit),
                "Ohne privaten Schluessel muss der Aufrufer 'noch nicht bereit' von 'kaputt' unterscheiden koennen");
    }
}
