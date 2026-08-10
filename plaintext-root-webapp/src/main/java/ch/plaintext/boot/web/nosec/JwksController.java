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
 * Veröffentlicht die öffentlichen Signaturschlüssel dieser Instanz als <b>JWK Set</b>
 * (RFC 7517) unter {@code /.well-known/jwks.json} — Karte 635.
 *
 * <p><b>Wozu.</b> Bisher konnte eine Gegenstelle ein von uns ausgestelltes Token nicht prüfen,
 * ohne dass ihr jemand den öffentlichen Schlüssel von Hand überreicht. Damit blieb als einziger
 * Ausweis ein <em>geteiltes Geheimnis</em>: Wer den Wert kennt, gilt als berechtigt — und wer ihn
 * vergisst (etwa nach einem Neustart), ist ausgesperrt. Genau dieser Fall steht in Karte 556: Der
 * Label-Drucker hält eine exklusive Session, und guild verliert nach einem Neustart den Token
 * dazu.
 *
 * <p>Mit veröffentlichtem Schlüssel <b>weist sich der Aufrufer aus</b>, statt ein gemerktes
 * Geheimnis vorzuzeigen. Nach einem Neustart signiert er einfach neu. Das Geheimnis — der private
 * Schlüssel — verlässt die Anwendung nie.
 *
 * <p><b>Warum genau dieser Pfad.</b> {@code /.well-known/jwks.json} ist der Ort, den RFC 8414 und
 * OpenID Connect Discovery vorsehen; dort sucht jede Bibliothek von selbst. Ein Hausweg unter
 * {@code /nosec} wäre bequemer gewesen (dort ist bereits alles freigegeben), aber dann wäre es
 * kein Standard mehr, sondern nur ein JSON an einer eigenen Adresse.
 *
 * <p><b>Was hier nicht hineingehört.</b> Ausschliesslich öffentliche Schlüssel. Ein RSA-JWK mit
 * privatem Anteil trüge zusätzlich {@code d}, {@code p}, {@code q}, {@code dp}, {@code dq},
 * {@code qi} — dass keines davon je erscheint, hält ein eigener Test fest. Diese Zusage ist der
 * ganze Grund, warum der Endpunkt ohne Anmeldung erreichbar sein darf.
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
                // Kein Fehler nach aussen: ein Schluessel, den wir nicht abbilden koennen, wird
                // ausgelassen. Ein 500 wuerde den Abruf der uebrigen mitreissen.
                log.warn("Signaturschluessel vom Typ {} wird im JWK Set ausgelassen — nur RSA wird abgebildet.",
                        pk.getAlgorithm());
            }
        }
        // Kein Caching: nach einem Schluesselwechsel muss die Gegenstelle den neuen sofort sehen.
        // Bei einer Handvoll Schluesseln kostet das nichts.
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(Map.of("keys", keys));
    }

    /**
     * RSA-Schlüssel als JWK (RFC 7517, Abschnitt 6.3.1).
     *
     * <p>Die Reihenfolge der Felder ist <b>nicht</b> beliebig: Der Thumbprint nach RFC 7638 wird
     * über die kanonische Form gebildet, und die verlangt genau {@code e}, {@code kty}, {@code n}
     * in lexikographischer Reihenfolge. Deshalb {@link LinkedHashMap} statt {@link Map#of}.
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
     * {@code kid} als Thumbprint nach RFC 7638: SHA-256 über die kanonische JWK-Form.
     *
     * <p>Bewusst abgeleitet statt konfiguriert. Ein frei vergebener {@code kid} müsste gepflegt und
     * bei jedem Schlüsselwechsel mitgezogen werden — ein Thumbprint ergibt sich aus dem Schlüssel
     * selbst, ist stabil, und die Gegenstelle kann ihn unabhängig nachrechnen.
     *
     * <p>Die kanonische Form enthält <b>nur</b> {@code e}, {@code kty}, {@code n}, ohne Leerzeichen,
     * in dieser Reihenfolge — jede Abweichung ergibt einen anderen Thumbprint und damit eine
     * {@code kid}, die niemand nachrechnen kann.
     */
    static String thumbprint(String n, String e) {
        String kanonisch = "{\"e\":\"" + e + "\",\"kty\":\"RSA\",\"n\":\"" + n + "\"}";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(kanonisch.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 ist in jeder JRE vorhanden; hier zu landen hiesse, dass die Plattform kaputt ist.
            throw new IllegalStateException("SHA-256 nicht verfuegbar", ex);
        }
    }

    /**
     * Base64url ohne Padding, wie RFC 7518 es für {@code n} und {@code e} verlangt.
     *
     * <p>{@link BigInteger#toByteArray()} stellt bei positiven Zahlen mit gesetztem höchstem Bit ein
     * Null-Byte voran (Zweierkomplement). Bleibt es stehen, ist der Modulus 257 statt 256 Byte lang
     * — die Signaturprüfung schlägt dann bei manchen Bibliotheken fehl, bei anderen nicht, und der
     * Fehler sieht aus wie ein falscher Schlüssel.
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
