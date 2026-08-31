/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.boot.plugins.secret.VaultwardenSecretService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT Token Service with RSA signing (RS256).
 * Tokens expire after 90 days.
 * Logs warning when token expires within 7 days.
 *
 * <p><b>Graceful (dual-key) signing key rotation:</b> New tokens are signed with
 * exactly ONE private key ({@link #privateKey}). Validation is performed against a
 * LIST of public keys ({@link #publicKeys}), so that during a rotation tokens from two
 * key generations remain valid at the same time. In PROD the private signing key can be
 * obtained from Vaultwarden, so that it is NOT contained in the artifact/image.</p>
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@Service
@Slf4j
public class JwtTokenService implements ch.plaintext.ServiceTokenIssuer {

    public static final int MIN_VALIDITY_DAYS = 7;
    public static final int MAX_VALIDITY_DAYS = 365;
    public static final int DEFAULT_VALIDITY_DAYS = 90;
    private static final Duration EXPIRY_WARNING_THRESHOLD = Duration.ofDays(7);
    private static final String CLAIM_MANDAT = "mandat";
    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TOKEN_NAME = "tokenName";
    private static final String CLAIM_SCOPE = "scope";

    /**
     * Separates the token classes (card 635). API tokens do <b>not</b> carry the claim;
     * {@link #signServiceToken} sets it to {@link #TOKEN_USE_SERVICE}.
     *
     * <p><b>Why this has to be.</b> {@link #validateToken} checks the signature — and a missing
     * {@link #CLAIM_SCOPE} counts as {@code ADMIN} (gentle migration of old API tokens). Without this
     * separation every machine token signed by us would automatically be an ADMIN API token. The
     * printer session token from card 556 travels over the wire as a header and sits in the memory
     * of a Raspberry Pi: it must not unlock anything here.
     */
    private static final String CLAIM_TOKEN_USE = "token_use";

    /** Value of {@link #CLAIM_TOKEN_USE} for machine credentials — never an API token. */
    public static final String TOKEN_USE_SERVICE = "service";

    /** Lower bound for {@link #signServiceToken}: shorter than a minute is practically unusable. */
    public static final Duration SERVICE_TOKEN_MIN_VALIDITY = Duration.ofMinutes(1);

    /**
     * Upper bound for {@link #signServiceToken}. A machine credential is short-lived; whoever needs
     * longer identifies itself anew — that is exactly the point of the published key.
     */
    public static final Duration SERVICE_TOKEN_MAX_VALIDITY = Duration.ofDays(2);

    private static final String VAULT_FIELD_PRIVATE_KEY = "private_key_pem";
    /** Field in the vault item holding this instance's own public key (X.509/SPKI PEM) — card 347. */
    private static final String VAULT_FIELD_PUBLIC_KEY = "public_key_pem";

    /**
     * Dev/test public key from the classpath. <b>Since card 305 it is no longer in ANY repo</b> — neither
     * under {@code src/main/resources} (it ended up in the shipped JAR) nor under {@code src/test/resources}.
     * The constant remains as a hook for local development: anyone who puts their own key pair under
     * {@code /keys/} keeps using it; otherwise {@link #loadPrivateKey()} generates an ephemeral pair outside
     * of PROD. Under the {@code prod} profile this key is never loaded for validation
     * (see {@link #loadPublicKeys()}).
     */
    static final String DEV_PUBLIC_KEY_RESOURCE = "/keys/public.pem";

    /**
     * Card 347: in PROD every instance validates ONLY with its OWN public key from the vault item
     * ({@link #VAULT_FIELD_PUBLIC_KEY}) — no shared/legacy key any more, no classpath fallback.
     * This list is exclusively a dev/test fallback; the former shared PROD keys
     * (public-prod.pem/-legacy) were removed from the artifact, the dev key from the repo (card 305).
     */
    private static final List<String> DEV_PUBLIC_KEY_RESOURCES = List.of(DEV_PUBLIC_KEY_RESOURCE);

    /**
     * Path to an externally mounted PEM file containing the private signing key
     * (e.g. a Docker/file secret). Alternative to the vault item; in PROD one of the two must be
     * set. Empty => outside of PROD the classpath ({@code /keys/private.pem}, if placed there locally),
     * otherwise an ephemeral dev pair; in PROD the startup fails (fail-closed, card 347).
     */
    @Value("${plaintext.jwt.private-key-file:}")
    private String privateKeyFile;

    /**
     * Name of the Vaultwarden item from whose hidden field {@code private_key_pem} the private
     * signing key is obtained. Highest priority — this way the PROD private key is NEVER in the
     * artifact. Empty => fallback to {@link #privateKeyFile} or the classpath.
     */
    @Value("${plaintext.jwt.private-key-vault-item:}")
    private String privateKeyVaultItem;

    /**
     * {@code iss} of the machine credentials issued by {@link #signServiceToken} — usually
     * the public base URL of this instance, which also hosts {@code /.well-known/jwks.json}
     * (card 635).
     *
     * <p>Empty omits the claim. That is deliberately allowed, but worse: a peer that knows
     * several instances (INT and PROD share the {@code prod} profile) can then no longer tell them
     * apart. Checking the value is the peer's business — it must hold it against its own
     * expectation and must <b>not</b> derive the key retrieval location from it, otherwise the
     * issuer decides who is believed.
     *
     * <p><b>Why the default comes from {@code plaintext.baseurl} (card 804).</b> Exactly the worse
     * case described above was the normal state: the property was not set anywhere, so INT and PROD
     * credentials carried the same content — with a shared {@code app.env} and therefore the same
     * signing key they can then no longer be told apart. The value this field wants is already
     * present in every environment as {@code plaintext.baseurl}; maintaining it a second time by
     * hand would only have created two sources that drift apart.
     *
     * <p>The inner default is deliberately <b>empty</b> and not {@code localhost}: where no base
     * address is configured, the claim is omitted as before. An
     * {@code iss=http://localhost:8080} in a production credential would be worse than none at all,
     * because it asserts an origin that is not true. An explicitly set
     * {@code plaintext.jwt.issuer} still wins.
     *
     * <p>Package-private so that tests can set the value — like {@link #activeProfiles}.
     */
    @Value("${plaintext.jwt.issuer:${plaintext.baseurl:}}")
    String issuer;

    /**
     * Active Spring profiles (comma-separated, e.g. {@code prod} or {@code prod,green}). Under
     * {@code prod} the dev public key {@link #DEV_PUBLIC_KEY_RESOURCE} is NOT loaded for validation
     * (card 305). Package-private so that tests can set the profile.
     */
    @Value("${spring.profiles.active:}")
    String activeProfiles;

    /** Fail-safe access to Vaultwarden; the bean is always registered, but can be disabled. */
    private final ObjectProvider<VaultwardenSecretService> vaultProvider;

    private PrivateKey privateKey;
    private List<PublicKey> publicKeys;

    /**
     * The public keys of this instance — for publication as a JWK Set
     * (card 635, {@code /.well-known/jwks.json}).
     *
     * <p>Hands out <b>only</b> the public keys, never {@link #privateKey}. The return value is
     * immutable: a caller who could edit the list would thereby silently change what this instance
     * validates tokens against.
     *
     * <p>Empty as long as the keys are not loaded yet (startup waits for the vault, see
     * {@code loadKeys}) — the endpoint then returns an empty set instead of an error, because a
     * half-started service is no reason to report a fault to the caller.
     */
    public List<PublicKey> getPublicKeysForPublication() {
        return publicKeys == null ? List.of() : List.copyOf(publicKeys);
    }

    /**
     * Dev/test only: the replacement key pair generated at runtime if neither a vault item, nor a
     * key file, nor a classpath key is present (see {@link #loadPrivateKey()}). Needed by
     * {@link #loadPublicKeys()} so that the signing and verification keys match.
     */
    private KeyPair ephemeresDevKeyPair;

    public JwtTokenService(ObjectProvider<VaultwardenSecretService> vaultProvider) {
        this.vaultProvider = vaultProvider;
    }

    /**
     * How often startup waits for the vault when it is configured as the key source but is not
     * delivering at the moment. 0 switches the waiting off (previous behaviour).
     *
     * <p>The default covers the long Vaultwarden backoff after an HTTP 429 (300 s,
     * {@code VaultwardenClient}) with reserve: 8 attempts of 60 s each.</p>
     */
    @Value("${plaintext.jwt.vault-wait-attempts:8}")
    int vaultWaitAttempts;

    /** Interval between two startup attempts, in seconds. See {@link #vaultWaitAttempts}. */
    @Value("${plaintext.jwt.vault-wait-seconds:60}")
    int vaultWaitSeconds;

    /**
     * Loads the keys, waiting for a temporarily unreachable vault while doing so.
     *
     * <p><b>Why it waits (card 632):</b> without this waiting the application dies as soon as
     * Vaultwarden shuts it out with HTTP 429 — and {@code restart: always} restarts it immediately,
     * whereupon it knocks again. <b>Every restart extends the lockout it would have to wait out.</b>
     * On 08.08.2026, after a Docker restart, four applications knocked at the same time and locked
     * each other out; app reached {@code RestartCount 11} and was dead for an hour. The backoff from
     * card 395 cannot prevent this: it lives in the process, and after every failure the process is
     * a new one.</p>
     *
     * <p><b>What is NOT softened:</b> the fail-closed line from card 347. It only waits if the vault
     * is <i>configured and active</i> as a source — that is, if there is a legitimate prospect that
     * the key will arrive shortly. If the configuration is missing, the startup fails immediately as
     * before; no classpath fallback arises from this anywhere.</p>
     */
    @PostConstruct
    public void init() {
        Exception letzterFehler = null;
        int versuche = Math.max(1, vaultWaitAttempts);

        for (int versuch = 1; versuch <= versuche; versuch++) {
            try {
                this.privateKey = loadPrivateKey();
                this.publicKeys = loadPublicKeys();
                if (versuch > 1) {
                    log.info("JWT RSA keys im {}. Versuch geladen — der Vault war beim Start vorübergehend nicht erreichbar",
                            versuch);
                }
                log.info("JWT RSA keys loaded successfully ({} public key(s) for validation)", publicKeys.size());
                return;
            } catch (Exception e) {
                letzterFehler = e;
                if (!weiterWarten(versuch, versuche, e)) {
                    break;
                }
            }
        }

        log.error("Failed to load JWT RSA keys: {}",
                letzterFehler != null ? letzterFehler.getMessage() : "unbekannt", letzterFehler);
        throw new IllegalStateException("Cannot initialize JWT service without RSA keys", letzterFehler);
    }

    /**
     * Whether another attempt follows a failed one — and, in that case, also waits right away.
     *
     * <p>Gathers both abort reasons in one place (no attempts left, or the vault is unsuitable as a
     * source; and: the sleep was interrupted), so that the loop in {@link #init()} has exactly one
     * exit. Pure extraction — the order of the checks and what is logged remain unchanged.</p>
     */
    private boolean weiterWarten(int versuch, int versuche, Exception fehler) {
        if (versuch >= versuche || !lohntWarten()) {
            return false;
        }
        log.warn("JWT RSA keys noch nicht ladbar (Versuch {}/{}): {} — der Vault ist als Quelle konfiguriert, "
                        + "also warte ich {} s statt den Start abzubrechen (ein Neustart würde nur erneut anklopfen)",
                versuch, versuche, fehler.getMessage(), vaultWaitSeconds);
        return schlafe(vaultWaitSeconds);
    }

    /**
     * Whether there is any prospect that a further attempt will succeed: the vault is configured as
     * the key source <b>and</b> is active in principle — then only the answer is missing, not the
     * source. With missing configuration, waiting would be pointless and would merely delay a
     * startup failure.
     */
    private boolean lohntWarten() {
        if (privateKeyVaultItem == null || privateKeyVaultItem.isBlank()) {
            return false;
        }
        VaultwardenSecretService vault = vaultProvider.getIfAvailable();
        return vault != null && vault.isEnabled();
    }

    /** @return false if the sleep was interrupted (then do not wait any further). */
    private boolean schlafe(int sekunden) {
        try {
            Thread.sleep(Duration.ofSeconds(sekunden).toMillis());
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Generate a new JWT token for a user.
     *
     * @param userId       User ID
     * @param mandat       Mandat identifier
     * @param email        User's email address
     * @param tokenName    User-defined name for this token
     * @param validityDays Token validity in days (7-90)
     * @return Signed JWT token string
     */
    public String generateToken(Long userId, String mandat, String email, String tokenName, int validityDays) {
        return generateToken(userId, mandat, email, tokenName, validityDays, null);
    }

    /**
     * Generate a new JWT token for a user, with an optional {@code scope} claim (values {@code READ}/
     * {@code EINTRAGEN}/{@code ADMIN}, see {@link McpBearerTokenFilter}). {@code null}/blank omits
     * the claim — validation treats a missing claim as {@code ADMIN} (gentle migration, existing
     * tokens without a scope claim remain fully functional).
     *
     * <p>ALWAYS sets a {@code jti} claim (for the optional token revocation, see
     * {@link JtiRevocationChecker}) — even if no scope is passed.</p>
     *
     * @param userId       User ID
     * @param mandat       Mandat identifier
     * @param email        User's email address
     * @param tokenName    User-defined name for this token
     * @param validityDays Token validity in days (7-90)
     * @param scope        {@code READ}/{@code EINTRAGEN}/{@code ADMIN}, or {@code null} (no claim)
     * @return Signed JWT token string
     */
    public String generateToken(Long userId, String mandat, String email, String tokenName, int validityDays, String scope) {
        // Enforce bounds
        if (validityDays < MIN_VALIDITY_DAYS) validityDays = MIN_VALIDITY_DAYS;
        if (validityDays > MAX_VALIDITY_DAYS) validityDays = MAX_VALIDITY_DAYS;

        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(validityDays));

        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_MANDAT, mandat)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry));

        if (email != null && !email.isBlank()) {
            builder.claim(CLAIM_EMAIL, email);
        }
        if (tokenName != null && !tokenName.isBlank()) {
            builder.claim(CLAIM_TOKEN_NAME, tokenName);
        }
        if (scope != null && !scope.isBlank()) {
            builder.claim(CLAIM_SCOPE, scope);
        }

        String token = builder.signWith(privateKey, Jwts.SIG.RS256).compact();

        log.info("Generated JWT token for userId={}, mandat={}, tokenName={}, validityDays={}, scope={}, expires={}",
                userId, mandat, tokenName, validityDays, scope, expiry);

        return token;
    }

    /**
     * Generate a new JWT token with default validity (90 days).
     */
    public String generateToken(Long userId, String mandat, String email, String tokenName) {
        return generateToken(userId, mandat, email, tokenName, DEFAULT_VALIDITY_DAYS);
    }

    /**
     * Generate a new JWT token for a user (without email/tokenName).
     */
    public String generateToken(Long userId, String mandat) {
        return generateToken(userId, mandat, null, null, DEFAULT_VALIDITY_DAYS);
    }

    /**
     * Signs a <b>machine credential</b>: a short-lived JWT with which this instance identifies itself
     * to a peer that can obtain the public key via
     * {@code /.well-known/jwks.json} (card 635).
     *
     * <p><b>What for.</b> Replaces shared secrets between services. The case from card 556: the
     * label printer holds an exclusive session; until now a remembered random value proved the
     * permission, and after a restart it was gone — the session stayed blocked until the device was
     * restarted. With a signed credential the service simply issues a new one after the restart;
     * the private key never leaves the application.
     *
     * <p><b>This is not an API token.</b> The claim {@code token_use=service} makes sure that
     * {@link #validateToken} rejects it. Without this separation the credential here would be a
     * fully privileged API token, because a missing {@code scope} counts as {@code ADMIN}.
     *
     * <p>The validity is limited to {@link #SERVICE_TOKEN_MIN_VALIDITY}…{@link #SERVICE_TOKEN_MAX_VALIDITY}
     * — clamped instead of rejected, as in {@link #generateToken(Long, String, String, String, int)}.
     *
     * @param subject     who identifies itself, e.g. {@code guild-checkin-desk} (mandatory)
     * @param audience    for whom the credential is valid, e.g. {@code guild42-label-printer}; the peer
     *                    checks it and rejects foreign credentials (empty = no {@code aud} claim)
     * @param gueltigkeit lifetime from now
     * @return signed JWT (RS256)
     * @throws IllegalArgumentException if {@code subject} is missing
     * @throws IllegalStateException    if the keys are not loaded yet (startup is waiting for the vault)
     */
    @Override
    public String signServiceToken(String subject, String audience, Duration gueltigkeit) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("signServiceToken: subject ist Pflicht — "
                    + "ein Ausweis ohne Aussteller-Kennung ist fuer die Gegenstelle nicht zuzuordnen.");
        }
        if (privateKey == null) {
            // Do not disguise this as a signing error: the caller should be able to distinguish
            // between "not ready yet" (vault wait time at startup) and "broken".
            throw new IllegalStateException("signServiceToken: privater Signaturschluessel ist noch nicht "
                    + "geladen (Vault-Wartezeit beim Start) — spaeter erneut versuchen.");
        }

        Duration laufzeit = gueltigkeit == null ? SERVICE_TOKEN_MIN_VALIDITY : gueltigkeit;
        if (laufzeit.compareTo(SERVICE_TOKEN_MIN_VALIDITY) < 0) laufzeit = SERVICE_TOKEN_MIN_VALIDITY;
        if (laufzeit.compareTo(SERVICE_TOKEN_MAX_VALIDITY) > 0) laufzeit = SERVICE_TOKEN_MAX_VALIDITY;

        Instant now = Instant.now();
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .claim(CLAIM_TOKEN_USE, TOKEN_USE_SERVICE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(laufzeit)));

        if (issuer != null && !issuer.isBlank()) {
            builder.issuer(issuer.trim());
        }
        if (audience != null && !audience.isBlank()) {
            builder.audience().add(audience.trim()).and();
        }

        String token = builder.signWith(privateKey, Jwts.SIG.RS256).compact();
        log.info("Maschinen-Ausweis signiert: subject={}, audience={}, gueltig {} (iss={})",
                subject, audience, laufzeit, issuer == null || issuer.isBlank() ? "<nicht gesetzt>" : issuer);
        return token;
    }

    /**
     * Validate a JWT token and extract claims.
     *
     * <p>The signature is checked against ALL configured public keys
     * ({@link #verifyWithAnyKey(String, List)}); the first key that verifies wins.
     * That way tokens of both generations remain valid during a key rotation.</p>
     *
     * @param token JWT token string
     * @return Validation result with claims, or empty if invalid
     */
    public Optional<JwtValidationResult> validateToken(String token) {
        if (token == null || token.isBlank()) {
            log.debug("Token validation failed: token is null or empty");
            return Optional.empty();
        }

        try {
            Optional<Claims> verified = verifyWithAnyKey(token, publicKeys);
            if (verified.isEmpty()) {
                return Optional.empty();
            }
            Claims claims = verified.get();

            // Card 635: a machine credential (signServiceToken) carries the same signature as an
            // API token and would otherwise pass here -- with userId=null and without a scope claim,
            // which counts as ADMIN. It is therefore explicitly rejected. Old API tokens do not carry
            // the claim and remain valid unchanged.
            String tokenUse = claims.get(CLAIM_TOKEN_USE, String.class);
            if (tokenUse != null && !tokenUse.isBlank()) {
                log.warn("JWT abgewiesen: token_use='{}' ist kein API-Token (jti={}). Ein Maschinen-Ausweis "
                        + "gibt keinen API-Zugriff.", tokenUse, claims.getId());
                return Optional.empty();
            }

            Long userId = claims.get(CLAIM_USER_ID, Long.class);
            String mandat = claims.get(CLAIM_MANDAT, String.class);
            String email = claims.get(CLAIM_EMAIL, String.class);
            String tokenName = claims.get(CLAIM_TOKEN_NAME, String.class);
            String scope = claims.get(CLAIM_SCOPE, String.class);
            String jti = claims.getId();
            Instant expiry = claims.getExpiration().toInstant();

            // Check if token expires soon (within 7 days)
            Duration timeUntilExpiry = Duration.between(Instant.now(), expiry);
            if (timeUntilExpiry.compareTo(EXPIRY_WARNING_THRESHOLD) <= 0) {
                log.warn("JWT token for userId={}, mandat={} expires in {} days - renewal recommended",
                        userId, mandat, timeUntilExpiry.toDays());
            }

            log.debug("JWT token validated successfully for userId={}, mandat={}, email={}", userId, mandat, email);
            return Optional.of(new JwtValidationResult(userId, mandat, email, tokenName, expiry, scope, jti));

        } catch (ExpiredJwtException e) {
            Claims claims = e.getClaims();
            Long userId = claims != null ? claims.get(CLAIM_USER_ID, Long.class) : null;
            String mandat = claims != null ? claims.get(CLAIM_MANDAT, String.class) : null;
            Instant expiredAt = claims != null ? claims.getExpiration().toInstant() : null;

            log.warn("JWT token expired for userId={}, mandat={}, expiredAt={}",
                    userId, mandat, expiredAt);
            return Optional.empty();

        } catch (JwtException e) {
            log.warn("JWT token validation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Verifies the signature of a token against a list of public keys and returns the
     * (non-expired) claims on the FIRST match. Package-private for tests.
     *
     * <p>A key whose signature does match but whose token has expired throws an
     * {@link ExpiredJwtException} — this is passed upwards so that the calling
     * {@link #validateToken(String)} keeps the existing expiry handling/logging
     * unchanged. If no key matches, {@link Optional#empty()} is returned.</p>
     *
     * @param token JWT token string
     * @param keys  public keys to validate against (order does not matter)
     * @return claims of the first verifying key, otherwise {@link Optional#empty()}
     */
    Optional<Claims> verifyWithAnyKey(String token, List<PublicKey> keys) {
        JwtException lastSignatureFailure = null;
        for (PublicKey pk : keys) {
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(pk)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
                return Optional.of(claims);
            } catch (ExpiredJwtException e) {
                // Signature matched this key, but the token has expired -> pass it upwards.
                throw e;
            } catch (JwtException e) {
                // Signature did not match this key -> try the next one.
                lastSignatureFailure = e;
            }
        }
        if (lastSignatureFailure != null) {
            log.warn("JWT token validation failed against all {} public key(s): {}",
                    keys.size(), lastSignatureFailure.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Check if a token is expired.
     *
     * @param token JWT token string
     * @return true if expired or invalid
     */
    public boolean isExpired(String token) {
        return validateToken(token).isEmpty();
    }

    /**
     * Get remaining validity duration.
     *
     * @param token JWT token string
     * @return Duration until expiry, or empty if invalid/expired
     */
    public Optional<Duration> getRemainingValidity(String token) {
        return validateToken(token)
                .map(result -> Duration.between(Instant.now(), result.expiresAt()));
    }

    /**
     * Reads the {@code jti} claim (JWT ID) of a token that has just been issued (card 664).
     *
     * <p>Issuance creates the jti internally via {@code UUID.randomUUID()} and returns only the
     * finished token string; {@link ApiTokenService} however needs it in order to store it in the
     * {@code api_token} row — otherwise an incoming token cannot be matched to its row later on
     * and {@code revoke_api_token} remains ineffective.</p>
     *
     * <p>Deliberately NOT via {@link #validateToken(String)}: that would reject service tokens
     * ({@code token_use=service}, card 635) and evaluates claims that nobody is interested in
     * here. The signature is checked nonetheless — a token whose signature does not match has no
     * business here.</p>
     *
     * @param token JWT string
     * @return jti, or {@link Optional#empty()} for an invalid signature or a missing claim
     */
    public Optional<String> extractJti(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return verifyWithAnyKey(token, publicKeys)
                    .map(Claims::getId)
                    .filter(jti -> !jti.isBlank());
        } catch (JwtException e) {
            log.warn("jti nicht lesbar: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private PrivateKey loadPrivateKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String pem = null;

        // 1) PRODUCTION (preferred): obtain the private key from Vaultwarden — this way it is NEVER in the artifact.
        if (privateKeyVaultItem != null && !privateKeyVaultItem.isBlank()) {
            VaultwardenSecretService vault = vaultProvider.getIfAvailable();
            if (vault != null && vault.isEnabled()) {
                Optional<String> vaultPem = vault.getField(privateKeyVaultItem.trim(), VAULT_FIELD_PRIVATE_KEY);
                if (vaultPem.isPresent()) {
                    pem = vaultPem.get();
                    log.info("JWT private key aus Vault-Item geladen");
                } else {
                    log.warn("JWT private key: Vault-Item '{}' gesetzt, aber Feld '{}' nicht verfügbar — Fallback auf Datei/Classpath",
                            privateKeyVaultItem, VAULT_FIELD_PRIVATE_KEY);
                }
            } else {
                log.warn("JWT private key: Vault-Item '{}' gesetzt, aber Vault nicht verfügbar/aktiv — Fallback auf Datei/Classpath",
                        privateKeyVaultItem);
            }
        }

        // 2) PRODUCTION (alternative): load the private key from an externally mounted secret (not from the JAR).
        if (pem == null && privateKeyFile != null && !privateKeyFile.isBlank()) {
            pem = Files.readString(Path.of(privateKeyFile.trim()), StandardCharsets.UTF_8);
            log.info("JWT private key loaded from external secret file");
        }

        // 3) Dev/test fallback: classpath. In PROD either plaintext.jwt.private-key-vault-item or
        // plaintext.jwt.private-key-file MUST be set — a private key in the JAR/image is
        // compromised (anyone with access to the artifact can forge tokens).
        if (pem == null) {
            // Card 347: in PROD NO classpath fallback any more (fail-closed). A private key in the
            // JAR/image is compromised; PROD MUST obtain the key from the vault item.
            if (istProd()) {
                throw new IllegalStateException("PROD: JWT private key muss aus dem Vault-Item "
                        + "(plaintext.jwt.private-key-vault-item) kommen — kein Classpath-Fallback (Karte 347).");
            }
            // Since card 347 the dev key only lives in the test scope of the apitoken module. Other modules
            // (e.g. plaintext-root-webapp) do NOT see foreign test resources — so there is no classpath key
            // there at all any more, and without this branch the bean would not come up. Instead of
            // duplicating key material into the repo, an ephemeral pair is generated outside of PROD: it
            // lives only in this JVM, signs and validates consistently and disappears with the process.
            if (!resourceExists("/keys/private.pem")) {
                ephemeresDevKeyPair = generiereDevKeyPair();
                log.info("JWT (Dev/Test): kein Schlüssel konfiguriert oder im Classpath — flüchtiges "
                        + "RSA-Paar für diese JVM erzeugt (nur ausserhalb von PROD).");
                return ephemeresDevKeyPair.getPrivate();
            }
            pem = loadResourceAsString("/keys/private.pem");
            log.info("JWT private key (Dev/Test) aus Classpath geladen");
        }

        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] decoded = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }

    /**
     * Loads all available public keys from the classpath (see {@link #DEV_PUBLIC_KEY_RESOURCES}).
     * Missing resources are skipped; the initialization only fails if NOT a single key is found.
     */
    private List<PublicKey> loadPublicKeys() throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
        boolean prod = istProd();

        // Card 347: in PROD every instance validates ONLY with its OWN public key from the vault item
        // (the same item as for the private key, field public_key_pem). A token signed by ANOTHER
        // instance is thereby rejected (no shared key any more). Fail-closed: if the vault key is
        // missing in PROD, the instance does NOT start — instead of silently falling back to a
        // classpath key (exactly that fallback was the cause of bug 347: guild signed with the
        // classpath dev key, which 305 had removed from PROD validation -> its own tokens got 401).
        if (privateKeyVaultItem != null && !privateKeyVaultItem.isBlank()) {
            VaultwardenSecretService vault = vaultProvider.getIfAvailable();
            if (vault != null && vault.isEnabled()) {
                Optional<String> pubPem = vault.getField(privateKeyVaultItem.trim(), VAULT_FIELD_PUBLIC_KEY);
                if (pubPem.isPresent()) {
                    PublicKey own = parsePublicKeyPem(pubPem.get());
                    log.info("JWT public key aus Vault-Item '{}' geladen (eigener Instanz-Key, Feld '{}')",
                            privateKeyVaultItem, VAULT_FIELD_PUBLIC_KEY);
                    return List.of(own);
                }
                log.error("JWT public key: Vault-Item '{}' gesetzt, aber Feld '{}' fehlt/leer.",
                        privateKeyVaultItem, VAULT_FIELD_PUBLIC_KEY);
            } else {
                log.error("JWT public key: Vault-Item '{}' gesetzt, aber Vault nicht verfuegbar/aktiv.", privateKeyVaultItem);
            }
            if (prod) {
                throw new IllegalStateException("PROD: eigener JWT-Public-Key aus Vault-Item '" + privateKeyVaultItem
                        + "' (Feld " + VAULT_FIELD_PUBLIC_KEY + ") nicht ladbar — fail-closed (Karte 347).");
            }
        } else if (prod) {
            throw new IllegalStateException("PROD: plaintext.jwt.private-key-vault-item muss gesetzt sein "
                    + "(instanz-eigener Key aus Vault) — kein Classpath-Fallback in PROD (Karte 347).");
        }

        // If the private key was generated ephemerally (no key on the classpath, see loadPrivateKey),
        // exactly its counterpart must validate — otherwise every self-signed check would fail.
        if (ephemeresDevKeyPair != null) {
            return List.of(ephemeresDevKeyPair.getPublic());
        }

        // Dev/test fallback (only outside PROD): dev public key from the (test) classpath.
        List<PublicKey> keys = new ArrayList<>();
        for (String resourcePath : DEV_PUBLIC_KEY_RESOURCES) {
            PublicKey key = loadPublicKeyFromClasspath(resourcePath);
            if (key != null) {
                keys.add(key);
                log.info("JWT public key (Dev/Test-Classpath) geladen: {}", resourcePath);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("Kein JWT public key gefunden (Dev/Test-Classpath " + DEV_PUBLIC_KEY_RESOURCES + ")");
        }
        return keys;
    }

    /** {@code true} if the classpath resource exists (without reading it). */
    private boolean resourceExists(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            return is != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Generates an ephemeral RSA-2048 pair for dev/test (never in PROD, never persisted). */
    private static KeyPair generiereDevKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    /** Parses an X.509/SPKI PEM public key (from a vault item or the classpath). */
    private static PublicKey parsePublicKeyPem(String pem)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
        return KeyFactory.getInstance("RSA").generatePublic(keySpec);
    }

    /** {@code true} if one of the active Spring profiles is {@code prod} (comma-separated, case-insensitive). */
    private boolean istProd() {
        if (activeProfiles == null || activeProfiles.isBlank()) {
            return false;
        }
        for (String p : activeProfiles.split(",")) {
            if ("prod".equalsIgnoreCase(p.trim())) {
                return true;
            }
        }
        return false;
    }

    private PublicKey loadPublicKeyFromClasspath(String resourcePath)
            throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
        String pem = loadResourceAsStringOrNull(resourcePath);
        return pem == null ? null : parsePublicKeyPem(pem);
    }

    private String loadResourceAsString(String resourcePath) throws IOException {
        String content = loadResourceAsStringOrNull(resourcePath);
        if (content == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        return content;
    }

    private String loadResourceAsStringOrNull(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Result of JWT validation containing extracted claims.
     *
     * @param scope {@code READ}/{@code EINTRAGEN}/{@code ADMIN}, or {@code null} for legacy tokens without
     *              a scope claim (callers such as {@link McpBearerTokenFilter} treat this as {@code ADMIN}).
     * @param jti   token ID for the optional revocation check, or {@code null} for legacy tokens
     *              without a {@code jti} claim (for those no revocation before expiry is possible).
     */
    public record JwtValidationResult(Long userId, String mandat, String email, String tokenName, Instant expiresAt,
                                       String scope, String jti) {}
}
