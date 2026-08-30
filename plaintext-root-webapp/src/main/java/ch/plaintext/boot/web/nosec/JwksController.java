/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.nosec;

import ch.plaintext.apitoken.JwtTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the public signature keys of this instance as a <b>JWK set</b>
 * (RFC 7517) under {@code /.well-known/jwks.json} — card 635.
 *
 * <p><b>What for.</b> Until now a counterpart could not verify a token we had issued
 * without somebody handing it the public key by hand. That left a <em>shared secret</em> as the
 * only credential: whoever knows the value counts as authorized — and whoever forgets it
 * (after a restart, say) is locked out. Exactly that case is described in card 556: the
 * label printer holds an exclusive session, and guild loses the token for it after a restart.
 *
 * <p>With a published key the caller <b>identifies himself</b> instead of presenting a memorized
 * secret. After a restart he simply signs anew. The secret — the private
 * key — never leaves the application.
 *
 * <p><b>Why exactly this path.</b> {@code /.well-known/jwks.json} is the location that RFC 8414 and
 * OpenID Connect Discovery prescribe; every library looks there by itself. A home-grown path under
 * {@code /nosec} would have been more convenient (everything is already permitted there), but then it
 * would no longer be a standard, just a JSON document at an address of our own.
 *
 * <p><b>What does not belong in here.</b> Exclusively public keys. An RSA JWK with a
 * private part would additionally carry {@code d}, {@code p}, {@code q}, {@code dp}, {@code dq},
 * {@code qi} — that none of these ever appears is pinned down by a test of its own. That promise is
 * the whole reason why the endpoint may be reachable without authentication.
 */
@RestController
@ConditionalOnWebApplication
@Slf4j
@Tag(name = "JWKS", description = "Public signing keys of this instance (RFC 7517, no authentication required)")
public class JwksController {

    private final JwtTokenService jwtTokenService;

    public JwksController(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Operation(summary = "JSON Web Key Set",
               description = "Public RSA signing keys of this instance in JWK Set format (RFC 7517). "
                           + "Contains public key material only and is publicly accessible.")
    @ApiResponse(responseCode = "200", description = "JWK Set returned (possibly empty while keys are still loading)")
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        List<Map<String, Object>> keys = new ArrayList<>();
        for (PublicKey pk : jwtTokenService.getPublicKeysForPublication()) {
            if (pk instanceof RSAPublicKey rsa) {
                keys.add(toJwk(rsa));
            } else {
                // No error towards the outside: a key that we cannot map is
                // left out. A 500 would drag down the retrieval of the others with it.
                log.warn("Signaturschluessel vom Typ {} wird im JWK Set ausgelassen — nur RSA wird abgebildet.",
                        pk.getAlgorithm());
            }
        }
        // No caching: after a key rotation the counterpart has to see the new key immediately.
        // With a handful of keys that costs nothing.
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(Map.of("keys", keys));
    }

    /**
     * RSA key as a JWK (RFC 7517, section 6.3.1).
     *
     * <p>The order of the fields is <b>not</b> arbitrary: the thumbprint according to RFC 7638 is
     * formed over the canonical form, and that requires exactly {@code e}, {@code kty}, {@code n}
     * in lexicographic order. Hence {@link LinkedHashMap} instead of {@link Map#of}.
     */
    private static Map<String, Object> toJwk(RSAPublicKey rsa) {
        String n = b64url(rsa.getModulus());
        String e = b64url(rsa.getPublicExponent());

        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", thumbprint(n, e));
        jwk.put("n", n);
        jwk.put("e", e);
        return jwk;
    }

    /**
     * {@code kid} as a thumbprint according to RFC 7638: SHA-256 over the canonical JWK form.
     *
     * <p>Deliberately derived instead of configured. A freely assigned {@code kid} would have to be
     * maintained and carried along on every key rotation — a thumbprint follows from the key
     * itself, is stable, and the counterpart can recompute it independently.
     *
     * <p>The canonical form contains <b>only</b> {@code e}, {@code kty}, {@code n}, without spaces,
     * in this order — every deviation yields a different thumbprint and thereby a
     * {@code kid} that nobody can recompute.
     */
    static String thumbprint(String n, String e) {
        String kanonisch = "{\"e\":\"" + e + "\",\"kty\":\"RSA\",\"n\":\"" + n + "\"}";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(kanonisch.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is present in every JRE; ending up here would mean that the platform is broken.
            throw new IllegalStateException("SHA-256 nicht verfuegbar", ex);
        }
    }

    /**
     * Base64url without padding, as RFC 7518 requires for {@code n} and {@code e}.
     *
     * <p>For positive numbers with the highest bit set, {@link BigInteger#toByteArray()} prepends a
     * zero byte (two's complement). If it stays there, the modulus is 257 instead of 256 bytes long
     * — the signature check then fails with some libraries and not with others, and the
     * error looks like a wrong key.
     */
    static String b64url(BigInteger wert) {
        byte[] b = wert.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] ohne = new byte[b.length - 1];
            System.arraycopy(b, 1, ohne, 0, ohne.length);
            b = ohne;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
