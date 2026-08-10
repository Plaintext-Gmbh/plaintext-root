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
 * JWK Set nach RFC 7517 (Karte 635).
 *
 * <p>Der wichtigste Test hier ist {@link #niemalsPrivateSchluesselImJwkSet()}. Der Endpunkt ist
 * ohne Anmeldung erreichbar — diese Zusage trägt die ganze Konstruktion, und sie muss geprüft
 * sein, nicht behauptet.
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
     * Die Zusage, auf der alles beruht: Ein RSA-JWK mit privatem Anteil trüge zusätzlich d, p, q,
     * dp, dq, qi. Keines davon darf je erscheinen — der Endpunkt ist unauthentifiziert.
     */
    @Test
    void niemalsPrivateSchluesselImJwkSet() {
        Map<String, Object> jwk = keysAus(controllerMit(paar.getPublic())).get(0);

        assertThat(jwk).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi", "oth");
        assertThat(jwk.toString()).doesNotContain("PRIVATE");
    }

    /**
     * Der Modulus muss sich wieder zum Originalschlüssel zusammensetzen lassen. Ohne diesen Test
     * fiele das führende Null-Byte aus {@link BigInteger#toByteArray()} nicht auf: Das JWK sähe
     * plausibel aus, wäre ein Byte zu lang, und die Signaturprüfung schlüge bei der Gegenstelle
     * fehl — mit einem Fehlerbild, das nach falschem Schlüssel aussieht.
     */
    @Test
    void modulusLaesstSichZumOriginalSchluesselZurueckbauen() throws Exception {
        RSAPublicKey original = (RSAPublicKey) paar.getPublic();
        Map<String, Object> jwk = keysAus(controllerMit(original)).get(0);

        byte[] nBytes = Base64.getUrlDecoder().decode((String) jwk.get("n"));

        // DIE LAENGE ist hier die eigentliche Aussage, nicht der Zahlenwert.
        //
        // Eine Mutationsprobe hat gezeigt, dass der Wert-Vergleich unten allein NICHTS beweist:
        // `new BigInteger(1, ...)` liest die Bytes als vorzeichenlose Zahl, und ein
        // vorangestelltes Null-Byte aendert daran nichts -- der Test blieb gruen, obwohl das
        // JWK ein Byte zu lang war. Eine Gegenstelle mit strenger Laengenpruefung haette den
        // Schluessel abgelehnt, und der Fehler haette wie ein falscher Schluessel ausgesehen.
        //
        // RFC 7518 verlangt fuer `n` die Darstellung ohne fuehrende Nullen: bei RSA-2048 sind
        // das genau 256 Byte.
        assertThat(nBytes).hasSize(original.getModulus().bitLength() / 8);

        BigInteger n = new BigInteger(1, nBytes);
        BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("e")));

        assertThat(n).isEqualTo(original.getModulus());
        assertThat(e).isEqualTo(original.getPublicExponent());

        PublicKey wiederhergestellt = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(n, e));
        assertThat(wiederhergestellt).isEqualTo(original);
    }

    /** Base64url ohne Padding — mit '=' am Ende lehnen strenge Bibliotheken das JWK ab. */
    @Test
    void base64urlOhnePaddingUndOhneStandardalphabet() {
        Map<String, Object> jwk = keysAus(controllerMit(paar.getPublic())).get(0);
        String n = (String) jwk.get("n");

        assertThat(n).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    /**
     * Der kid ist der Thumbprint nach RFC 7638. Hier unabhängig nachgerechnet — genau das muss
     * eine Gegenstelle auch können, sonst ist der Wert wertlos.
     */
    @Test
    void kidIstDerNachrechenbareThumbprintNachRfc7638() throws Exception {
        Map<String, Object> jwk = keysAus(controllerMit(paar.getPublic())).get(0);

        String kanonisch = "{\"e\":\"" + jwk.get("e") + "\",\"kty\":\"RSA\",\"n\":\"" + jwk.get("n") + "\"}";
        String erwartet = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(kanonisch.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(jwk.get("kid")).isEqualTo(erwartet);
    }

    @Test
    void mehrereSchluesselErscheinenAlleUndMitVerschiedenenKids() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair zweites = g.generateKeyPair();

        List<Map<String, Object>> keys = keysAus(controllerMit(paar.getPublic(), zweites.getPublic()));

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).get("kid")).isNotEqualTo(keys.get(1).get("kid"));
    }

    /**
     * Solange die Schlüssel noch nicht geladen sind (der Start wartet auf den Vault), liefert der
     * Endpunkt ein leeres Set statt eines Fehlers. Ein halb gestarteter Dienst ist kein Grund,
     * dem Abrufer eine Störung zu melden — er soll es gleich noch einmal versuchen können.
     */
    @Test
    void ohneGeladeneSchluesselEinLeeresSetUndKeinFehler() {
        JwtTokenService service = mock(JwtTokenService.class);
        when(service.getPublicKeysForPublication()).thenReturn(List.of());

        var antwort = new JwksController(service).jwks();

        assertThat(antwort.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) antwort.getBody().get("keys")).isEmpty();
    }

    /** Nach einem Schlüsselwechsel muss die Gegenstelle den neuen sofort sehen. */
    @Test
    void antwortWirdNichtZwischengespeichert() {
        var antwort = controllerMit(paar.getPublic()).jwks();

        assertThat(antwort.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }
}
