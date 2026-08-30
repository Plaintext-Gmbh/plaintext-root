/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.nosec;

import ch.plaintext.apitoken.JwtTokenService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JWK set according to RFC 7517 (card 635).
 *
 * <p>The most important test here is {@link #niemalsPrivateSchluesselImJwkSet()}. The endpoint is
 * reachable without a login — that promise carries the whole construction, and it has to be
 * checked, not asserted.
 */
class JwksControllerTest {

    private static KeyPair paar;

    @BeforeAll
    static void schluesselErzeugen() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        paar = g.generateKeyPair();
    }

    private JwksController controllerMit(PublicKey... keys) {
        JwtTokenService service = mock(JwtTokenService.class);
        when(service.getPublicKeysForPublication()).thenReturn(List.of(keys));
        return new JwksController(service);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> keysAus(JwksController c) {
        return (List<Map<String, Object>>) c.jwks().getBody().get("keys");
    }

    @Test
    void liefertDenOeffentlichenSchluesselAlsJwk() {
        List<Map<String, Object>> keys = keysAus(controllerMit(paar.getPublic()));

        assertThat(keys).hasSize(1);
        Map<String, Object> jwk = keys.get(0);
        assertThat(jwk).containsEntry("kty", "RSA")
                       .containsEntry("use", "sig")
                       .containsEntry("alg", "RS256")
                       .containsKeys("n", "e", "kid");
    }

    /**
     * The promise on which everything rests: an RSA JWK with a private part would additionally carry d, p, q,
     * dp, dq, qi. None of them may ever appear — the endpoint is unauthenticated.
     */
    @Test
    void niemalsPrivateSchluesselImJwkSet() {
        Map<String, Object> jwk = keysAus(controllerMit(paar.getPublic())).get(0);

        assertThat(jwk).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi", "oth");
        assertThat(jwk.toString()).doesNotContain("PRIVATE");
    }

    /**
     * The modulus has to be reassemblable into the original key. Without this test
     * the leading zero byte from {@link BigInteger#toByteArray()} would go unnoticed: the JWK would look
     * plausible, would be one byte too long, and the signature check would fail at the counterpart
     * — with a symptom that looks like a wrong key.
     */
    @Test
    void modulusLaesstSichZumOriginalSchluesselZurueckbauen() throws Exception {
        RSAPublicKey original = (RSAPublicKey) paar.getPublic();
        Map<String, Object> jwk = keysAus(controllerMit(original)).get(0);

        byte[] nBytes = Base64.getUrlDecoder().decode((String) jwk.get("n"));

        // THE LENGTH is the actual statement here, not the numeric value.
        //
        // A mutation probe showed that the value comparison below alone proves NOTHING:
        // `new BigInteger(1, ...)` reads the bytes as an unsigned number, and a
        // leading zero byte changes nothing about that -- the test stayed green even though the
        // JWK was one byte too long. A counterpart with a strict length check would have rejected the
        // key, and the defect would have looked like a wrong key.
        //
        // For `n` RFC 7518 demands the representation without leading zeros: with RSA-2048 that is
        // exactly 256 bytes.
        assertThat(nBytes).hasSize(original.getModulus().bitLength() / 8);

        BigInteger n = new BigInteger(1, nBytes);
        BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("e")));

        assertThat(n).isEqualTo(original.getModulus());
        assertThat(e).isEqualTo(original.getPublicExponent());

        PublicKey wiederhergestellt = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(n, e));
        assertThat(wiederhergestellt).isEqualTo(original);
    }

    /** Base64url without padding — with '=' at the end strict libraries reject the JWK. */
    @Test
    void base64urlOhnePaddingUndOhneStandardalphabet() {
        Map<String, Object> jwk = keysAus(controllerMit(paar.getPublic())).get(0);
        String n = (String) jwk.get("n");

        assertThat(n).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    /**
     * The kid is the thumbprint according to RFC 7638. Recomputed independently here — exactly that
     * is what a counterpart has to be able to do as well, otherwise the value is worthless.
     */
    @Test
    void kidIstDerNachrechenbareThumbprintNachRfc7638() throws Exception {
        Map<String, Object> jwk = keysAus(controllerMit(paar.getPublic())).get(0);

        String kanonisch = "{\"e\":\"" + jwk.get("e") + "\",\"kty\":\"RSA\",\"n\":\"" + jwk.get("n") + "\"}";
        String erwartet = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(kanonisch.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(jwk).containsEntry("kid", erwartet);
    }

    @Test
    void mehrereSchluesselErscheinenAlleUndMitVerschiedenenKids() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair zweites = g.generateKeyPair();

        List<Map<String, Object>> keys = keysAus(controllerMit(paar.getPublic(), zweites.getPublic()));

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).doesNotContainEntry("kid", keys.get(1).get("kid"));
    }

    /**
     * As long as the keys are not loaded yet (the startup waits for the vault), the
     * endpoint returns an empty set instead of an error. A half-started service is no reason
     * to report a malfunction to the caller — it should be able to try again right away.
     */
    @Test
    void ohneGeladeneSchluesselEinLeeresSetUndKeinFehler() {
        JwtTokenService service = mock(JwtTokenService.class);
        when(service.getPublicKeysForPublication()).thenReturn(List.of());

        var antwort = new JwksController(service).jwks();

        assertThat(antwort.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) antwort.getBody().get("keys")).isEmpty();
    }

    /** After a key rotation the counterpart has to see the new key immediately. */
    @Test
    void antwortWirdNichtZwischengespeichert() {
        var antwort = controllerMit(paar.getPublic()).jwks();

        assertThat(antwort.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }
}
