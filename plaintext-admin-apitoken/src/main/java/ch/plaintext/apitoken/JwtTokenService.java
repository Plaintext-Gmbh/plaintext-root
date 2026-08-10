/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
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
 * <p><b>Graceful (dual-key) Signaturschlüssel-Rotation:</b> Neue Tokens werden mit
 * genau EINEM privaten Schlüssel ({@link #privateKey}) signiert. Bei der Validierung
 * wird gegen eine LISTE von öffentlichen Schlüsseln ({@link #publicKeys}) geprüft, sodass
 * während einer Rotation Tokens aus zwei Schlüssel-Generationen gleichzeitig gültig
 * bleiben. Der private Signaturschlüssel kann in PROD aus Vaultwarden bezogen werden,
 * damit er NICHT im Artefakt/Image liegt.</p>
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@Service
@Slf4j
public class JwtTokenService {

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
     * Trennt die Token-Klassen (Karte 635). API-Tokens tragen den Claim <b>nicht</b>;
     * {@link #signServiceToken} setzt ihn auf {@link #TOKEN_USE_SERVICE}.
     *
     * <p><b>Warum das sein muss.</b> {@link #validateToken} prüft die Signatur — und ein fehlender
     * {@link #CLAIM_SCOPE} gilt als {@code ADMIN} (sanfte Migration alter API-Tokens). Ohne diese
     * Trennung wäre jedes von uns signierte Maschinen-Token automatisch ein ADMIN-API-Token. Der
     * Drucker-Session-Token aus Karte 556 geht als Header über die Leitung und liegt im Speicher
     * eines Raspberry Pi: Er darf hier nichts öffnen.
     */
    private static final String CLAIM_TOKEN_USE = "token_use";

    /** Wert von {@link #CLAIM_TOKEN_USE} für Maschinen-Ausweise — nie ein API-Token. */
    public static final String TOKEN_USE_SERVICE = "service";

    /** Untergrenze für {@link #signServiceToken}: kürzer als eine Minute ist praktisch unbenutzbar. */
    public static final Duration SERVICE_TOKEN_MIN_VALIDITY = Duration.ofMinutes(1);

    /**
     * Obergrenze für {@link #signServiceToken}. Ein Maschinen-Ausweis ist kurzlebig; wer länger
     * braucht, weist sich neu aus — genau das ist der Sinn des veröffentlichten Schlüssels.
     */
    public static final Duration SERVICE_TOKEN_MAX_VALIDITY = Duration.ofDays(2);

    private static final String VAULT_FIELD_PRIVATE_KEY = "private_key_pem";
    /** Feld im Vault-Item mit dem instanz-eigenen Public-Key (X.509/SPKI-PEM) — Karte 347. */
    private static final String VAULT_FIELD_PUBLIC_KEY = "public_key_pem";

    /**
     * Dev/Test-Public-Key vom Classpath. <b>Liegt seit Karte 305 in KEINEM Repo mehr</b> — weder unter
     * {@code src/main/resources} (landete im ausgelieferten JAR) noch unter {@code src/test/resources}.
     * Die Konstante bleibt als Einhängepunkt für lokale Entwicklung: Wer selbst ein Schlüsselpaar unter
     * {@code /keys/} ablegt, benutzt es weiterhin; sonst erzeugt {@link #loadPrivateKey()} ausserhalb von
     * PROD ein flüchtiges Paar. Unter dem {@code prod}-Profil wird dieser Key nie zur Validierung geladen
     * (siehe {@link #loadPublicKeys()}).
     */
    static final String DEV_PUBLIC_KEY_RESOURCE = "/keys/public.pem";

    /**
     * Karte 347: In PROD validiert jede Instanz NUR mit ihrem EIGENEN Public-Key aus dem Vault-Item
     * ({@link #VAULT_FIELD_PUBLIC_KEY}) — kein gemeinsamer/Legacy-Key mehr, kein Classpath-Fallback.
     * Diese Liste ist ausschliesslich Dev/Test-Fallback; die frueheren gemeinsamen PROD-Keys
     * (public-prod.pem/-legacy) wurden aus dem Artefakt entfernt, der Dev-Key aus dem Repo (Karte 305).
     */
    private static final List<String> DEV_PUBLIC_KEY_RESOURCES = List.of(DEV_PUBLIC_KEY_RESOURCE);

    /**
     * Pfad zu einer extern gemounteten PEM-Datei mit dem privaten Signaturschlüssel
     * (z.B. Docker-/File-Secret). Alternative zum Vault-Item; in PROD muss eines von beiden gesetzt
     * sein. Leer => ausserhalb von PROD Classpath ({@code /keys/private.pem}, falls lokal abgelegt),
     * sonst ein flüchtiges Dev-Paar; in PROD schlägt der Start fehl (fail-closed, Karte 347).
     */
    @Value("${plaintext.jwt.private-key-file:}")
    private String privateKeyFile;

    /**
     * Name des Vaultwarden-Items, aus dessen hidden Field {@code private_key_pem} der private
     * Signaturschlüssel bezogen wird. Höchste Priorität — so liegt der PROD-Private NIE im
     * Artefakt. Leer => Fallback auf {@link #privateKeyFile} bzw. Classpath.
     */
    @Value("${plaintext.jwt.private-key-vault-item:}")
    private String privateKeyVaultItem;

    /**
     * {@code iss} der von {@link #signServiceToken} ausgestellten Maschinen-Ausweise — üblicherweise
     * die öffentliche Basis-URL dieser Instanz, unter der auch {@code /.well-known/jwks.json} liegt
     * (Karte 635).
     *
     * <p>Leer lässt den Claim weg. Das ist bewusst erlaubt, aber schlechter: Eine Gegenstelle, die
     * mehrere Instanzen kennt (INT und PROD teilen sich das {@code prod}-Profil), kann sie dann nicht
     * auseinanderhalten. Die Prüfung des Werts ist Sache der Gegenstelle — sie muss ihn gegen ihre
     * eigene Erwartung halten und darf daraus <b>nicht</b> den Schlüssel-Abrufort ableiten, sonst
     * bestimmt der Aussteller, wem geglaubt wird.
     *
     * <p>Package-private, damit Tests den Wert setzen können — wie {@link #activeProfiles}.
     */
    @Value("${plaintext.jwt.issuer:}")
    String issuer;

    /**
     * Aktive Spring-Profile (kommasepariert, z. B. {@code prod} oder {@code prod,green}). Unter
     * {@code prod} wird der Dev-Public-Key {@link #DEV_PUBLIC_KEY_RESOURCE} NICHT zur Validierung
     * geladen (Karte 305). Package-private, damit Tests das Profil setzen koennen.
     */
    @Value("${spring.profiles.active:}")
    String activeProfiles;

    /** Fail-safe Zugriff auf Vaultwarden; die Bean ist immer registriert, kann aber deaktiviert sein. */
    private final ObjectProvider<VaultwardenSecretService> vaultProvider;

    private PrivateKey privateKey;
    private List<PublicKey> publicKeys;

    /**
     * Die öffentlichen Schlüssel dieser Instanz — für die Veröffentlichung als JWK Set
     * (Karte 635, {@code /.well-known/jwks.json}).
     *
     * <p>Gibt <b>nur</b> die öffentlichen Schlüssel heraus, nie {@link #privateKey}. Der Rückgabewert
     * ist unveränderlich: Ein Aufrufer, der die Liste bearbeiten könnte, würde damit stillschweigend
     * ändern, womit diese Instanz Tokens prüft.
     *
     * <p>Leer, solange die Schlüssel noch nicht geladen sind (der Start wartet auf den Vault, siehe
     * {@code loadKeys}) — der Endpunkt liefert dann ein leeres Set statt eines Fehlers, denn ein
     * halb gestarteter Dienst ist kein Grund, dem Abrufer eine Störung zu melden.
     */
    public List<PublicKey> getPublicKeysForPublication() {
        return publicKeys == null ? List.of() : List.copyOf(publicKeys);
    }

    /**
     * Nur Dev/Test: das zur Laufzeit erzeugte Ersatz-Schlüsselpaar, falls weder Vault-Item, noch
     * Key-Datei, noch ein Classpath-Key vorhanden ist (siehe {@link #loadPrivateKey()}). Wird von
     * {@link #loadPublicKeys()} gebraucht, damit Signier- und Prüfschlüssel zusammenpassen.
     */
    private KeyPair ephemeresDevKeyPair;

    public JwtTokenService(ObjectProvider<VaultwardenSecretService> vaultProvider) {
        this.vaultProvider = vaultProvider;
    }

    /**
     * Wie oft beim Start auf den Vault gewartet wird, wenn er als Schlüsselquelle konfiguriert ist,
     * aber gerade nicht liefert. 0 schaltet das Warten ab (bisheriges Verhalten).
     *
     * <p>Der Default deckt den langen Vaultwarden-Backoff nach einem HTTP 429 ab (300 s,
     * {@code VaultwardenClient}) mit Reserve: 8 Versuche à 60 s.</p>
     */
    @Value("${plaintext.jwt.vault-wait-attempts:8}")
    int vaultWaitAttempts;

    /** Abstand zwischen zwei Startversuchen in Sekunden. Siehe {@link #vaultWaitAttempts}. */
    @Value("${plaintext.jwt.vault-wait-seconds:60}")
    int vaultWaitSeconds;

    /**
     * Lädt die Schlüssel und wartet dabei auf einen vorübergehend nicht erreichbaren Vault.
     *
     * <p><b>Warum gewartet wird (Karte 632):</b> Ohne dieses Warten stirbt die Anwendung, sobald
     * Vaultwarden mit HTTP 429 abriegelt — und {@code restart: always} startet sie sofort neu, wo
     * sie erneut anklopft. <b>Jeder Neustart verlängert die Sperre, die er abwarten müsste.</b> Am
     * 08.08.2026 haben nach einem Docker-Neustart vier Anwendungen gleichzeitig angeklopft und sich
     * gegenseitig ausgesperrt; app kam auf {@code RestartCount 11} und war eine Stunde tot. Der
     * Backoff aus Karte 395 kann das nicht verhindern: Er lebt im Prozess, und der Prozess ist nach
     * jedem Fehlschlag ein neuer.</p>
     *
     * <p><b>Was NICHT aufgeweicht wird:</b> die fail-closed-Linie aus Karte 347. Gewartet wird nur,
     * wenn der Vault als Quelle <i>konfiguriert und aktiv</i> ist — also wenn berechtigte Aussicht
     * besteht, dass der Schlüssel gleich kommt. Fehlt die Konfiguration, scheitert der Start sofort
     * wie bisher; ein Classpath-Fallback entsteht dadurch nirgends.</p>
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
                boolean nochVersucheOffen = versuch < versuche;
                if (!nochVersucheOffen || !lohntWarten()) {
                    break;
                }
                log.warn("JWT RSA keys noch nicht ladbar (Versuch {}/{}): {} — der Vault ist als Quelle konfiguriert, "
                                + "also warte ich {} s statt den Start abzubrechen (ein Neustart würde nur erneut anklopfen)",
                        versuch, versuche, e.getMessage(), vaultWaitSeconds);
                if (!schlafe(vaultWaitSeconds)) {
                    break;
                }
            }
        }

        log.error("Failed to load JWT RSA keys: {}",
                letzterFehler != null ? letzterFehler.getMessage() : "unbekannt", letzterFehler);
        throw new IllegalStateException("Cannot initialize JWT service without RSA keys", letzterFehler);
    }

    /**
     * Ob es Aussicht gibt, dass ein weiterer Versuch gelingt: Der Vault ist als Schlüsselquelle
     * konfiguriert <b>und</b> grundsätzlich aktiv — dann fehlt nur die Antwort, nicht die Quelle.
     * Bei fehlender Konfiguration wäre Warten sinnlos und würde einen Startfehler bloss verzögern.
     */
    private boolean lohntWarten() {
        if (privateKeyVaultItem == null || privateKeyVaultItem.isBlank()) {
            return false;
        }
        VaultwardenSecretService vault = vaultProvider.getIfAvailable();
        return vault != null && vault.isEnabled();
    }

    /** @return false, wenn der Schlaf unterbrochen wurde (dann nicht weiter warten). */
    private boolean schlafe(int sekunden) {
        try {
            Thread.sleep(Duration.ofSeconds(sekunden).toMillis());
            return true;
        } catch (InterruptedException ie) {
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
     * Generate a new JWT token for a user, mit optionalem {@code scope}-Claim (Werte {@code READ}/
     * {@code EINTRAGEN}/{@code ADMIN}, siehe {@link McpBearerTokenFilter}). {@code null}/blank lässt
     * den Claim weg — Validierung behandelt einen fehlenden Claim als {@code ADMIN} (sanfte Migration,
     * bestehende Tokens ohne Scope-Claim bleiben voll funktionsfähig).
     *
     * <p>Setzt IMMER einen {@code jti}-Claim (für die optionale Token-Revocation, siehe
     * {@link JtiRevocationChecker}) — auch wenn kein Scope übergeben wird.</p>
     *
     * @param userId       User ID
     * @param mandat       Mandat identifier
     * @param email        User's email address
     * @param tokenName    User-defined name for this token
     * @param validityDays Token validity in days (7-90)
     * @param scope        {@code READ}/{@code EINTRAGEN}/{@code ADMIN}, oder {@code null} (kein Claim)
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
     * Signiert einen <b>Maschinen-Ausweis</b>: ein kurzlebiges JWT, mit dem sich diese Instanz bei
     * einer Gegenstelle ausweist, die den öffentlichen Schlüssel über
     * {@code /.well-known/jwks.json} beziehen kann (Karte 635).
     *
     * <p><b>Wofür.</b> Ersetzt geteilte Geheimnisse zwischen Diensten. Der Fall aus Karte 556: Der
     * Label-Drucker hält eine exklusive Session; bisher bewies ein gemerkter Zufallswert die
     * Berechtigung, und nach einem Neustart war er weg — die Session blieb bis zum Neustart des
     * Geräts blockiert. Mit einem signierten Ausweis stellt der Dienst nach dem Neustart einfach
     * einen neuen aus; der private Schlüssel verlässt die Anwendung nie.
     *
     * <p><b>Das ist kein API-Token.</b> Der Claim {@code token_use=service} sorgt dafür, dass
     * {@link #validateToken} es ablehnt. Ohne diese Trennung wäre der Ausweis hier ein
     * vollprivilegiertes API-Token, denn ein fehlender {@code scope} gilt als {@code ADMIN}.
     *
     * <p>Die Gültigkeit wird auf {@link #SERVICE_TOKEN_MIN_VALIDITY}…{@link #SERVICE_TOKEN_MAX_VALIDITY}
     * begrenzt — geklemmt statt abgelehnt, wie bei {@link #generateToken(Long, String, String, String, int)}.
     *
     * @param subject     wer sich ausweist, z. B. {@code guild-checkin-desk} (Pflicht)
     * @param audience    für wen der Ausweis gilt, z. B. {@code guild42-label-printer}; die Gegenstelle
     *                    prüft ihn und weist fremde Ausweise ab (leer = kein {@code aud}-Claim)
     * @param gueltigkeit Laufzeit ab jetzt
     * @return signiertes JWT (RS256)
     * @throws IllegalArgumentException wenn {@code subject} fehlt
     * @throws IllegalStateException    wenn die Schlüssel noch nicht geladen sind (Start wartet auf den Vault)
     */
    public String signServiceToken(String subject, String audience, Duration gueltigkeit) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("signServiceToken: subject ist Pflicht — "
                    + "ein Ausweis ohne Aussteller-Kennung ist fuer die Gegenstelle nicht zuzuordnen.");
        }
        if (privateKey == null) {
            // Nicht als Signaturfehler tarnen: Der Aufrufer soll unterscheiden koennen zwischen
            // "noch nicht bereit" (Vault-Wartezeit beim Start) und "kaputt".
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
     * <p>Die Signatur wird gegen ALLE konfigurierten öffentlichen Schlüssel geprüft
     * ({@link #verifyWithAnyKey(String, List)}); der erste Schlüssel, der verifiziert,
     * gewinnt. So bleiben während einer Schlüssel-Rotation Tokens beider Generationen gültig.</p>
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

            // Karte 635: Ein Maschinen-Ausweis (signServiceToken) traegt dieselbe Signatur wie ein
            // API-Token und wuerde hier sonst durchgehen -- mit userId=null und ohne scope-Claim, was
            // als ADMIN gilt. Er wird deshalb ausdruecklich abgewiesen. Alte API-Tokens tragen den
            // Claim nicht und bleiben unveraendert gueltig.
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
     * Verifiziert die Signatur eines Tokens gegen eine Liste öffentlicher Schlüssel und liefert
     * beim ERSTEN Treffer die (unverfallenen) Claims. Package-private für Tests.
     *
     * <p>Ein Schlüssel, dessen Signatur zwar passt, dessen Token aber abgelaufen ist, wirft
     * eine {@link ExpiredJwtException} — diese wird nach oben durchgereicht, damit die
     * aufrufende {@link #validateToken(String)} die bestehende Ablauf-Behandlung/-Logging
     * unverändert übernimmt. Passt kein Schlüssel, wird {@link Optional#empty()} geliefert.</p>
     *
     * @param token JWT token string
     * @param keys  öffentliche Schlüssel, gegen die validiert wird (Reihenfolge egal)
     * @return Claims des ersten verifizierenden Schlüssels, sonst {@link Optional#empty()}
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
                // Signatur passte zu diesem Schlüssel, aber Token ist abgelaufen -> nach oben durchreichen.
                throw e;
            } catch (JwtException e) {
                // Signatur passte nicht zu diesem Schlüssel -> nächsten versuchen.
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

    private PrivateKey loadPrivateKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String pem = null;

        // 1) PRODUKTION (bevorzugt): privaten Schlüssel aus Vaultwarden beziehen — liegt so NIE im Artefakt.
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

        // 2) PRODUKTION (Alternative): privaten Schlüssel aus extern gemountetem Secret laden (nicht aus dem JAR).
        if (pem == null && privateKeyFile != null && !privateKeyFile.isBlank()) {
            pem = Files.readString(Path.of(privateKeyFile.trim()), StandardCharsets.UTF_8);
            log.info("JWT private key loaded from external secret file");
        }

        // 3) Dev/Test-Fallback: Classpath. In PROD MUSS plaintext.jwt.private-key-vault-item oder
        // plaintext.jwt.private-key-file gesetzt sein — ein privater Schlüssel im JAR/Image ist
        // kompromittiert (jeder mit Artefakt-Zugriff kann Tokens fälschen).
        if (pem == null) {
            // Karte 347: In PROD KEIN Classpath-Fallback mehr (fail-closed). Ein privater Schluessel im
            // JAR/Image ist kompromittiert; PROD MUSS den Key aus dem Vault-Item beziehen.
            if (istProd()) {
                throw new IllegalStateException("PROD: JWT private key muss aus dem Vault-Item "
                        + "(plaintext.jwt.private-key-vault-item) kommen — kein Classpath-Fallback (Karte 347).");
            }
            // Der Dev-Key liegt seit Karte 347 nur noch im test-Scope des apitoken-Moduls. Andere Module
            // (z. B. plaintext-root-webapp) sehen fremde test-resources NICHT — dort gibt es also gar
            // keinen Classpath-Key mehr, und die Bean käme ohne diesen Zweig nicht hoch. Statt Key-Material
            // ins Repo zu duplizieren, wird ausserhalb von PROD ein flüchtiges Paar erzeugt: es lebt nur in
            // dieser JVM, signiert und validiert konsistent und verschwindet mit dem Prozess.
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
     * Lädt alle vorhandenen öffentlichen Schlüssel vom Classpath (siehe {@link #PUBLIC_KEY_RESOURCES}).
     * Fehlende Ressourcen werden übersprungen; nur wenn KEIN einziger Schlüssel gefunden wird, schlägt
     * die Initialisierung fehl.
     */
    private List<PublicKey> loadPublicKeys() throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
        boolean prod = istProd();

        // Karte 347: In PROD validiert jede Instanz NUR mit ihrem EIGENEN Public-Key aus dem Vault-Item
        // (dasselbe Item wie fuer den Private-Key, Feld public_key_pem). Ein von einer ANDEREN Instanz
        // signierter Token wird damit abgelehnt (kein gemeinsamer Key mehr). Fail-closed: fehlt der
        // Vault-Key in PROD, startet die Instanz NICHT — statt still auf einen Classpath-Key zurueckzu-
        // fallen (genau dieser Fallback war die Ursache von Bug 347: guild signierte mit dem Classpath-
        // Dev-Key, den 305 aus der PROD-Validierung nahm -> eigene Tokens 401).
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

        // Wurde der private Schlüssel flüchtig erzeugt (kein Key im Classpath, s. loadPrivateKey), muss
        // exakt dessen Gegenstück validieren — sonst schlüge jede selbst signierte Prüfung fehl.
        if (ephemeresDevKeyPair != null) {
            return List.of(ephemeresDevKeyPair.getPublic());
        }

        // Dev/Test-Fallback (nur ausserhalb PROD): Dev-Public-Key aus dem (Test-)Classpath.
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

    /** {@code true}, wenn die Classpath-Ressource existiert (ohne sie zu lesen). */
    private boolean resourceExists(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            return is != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Erzeugt ein flüchtiges RSA-2048-Paar für Dev/Test (nie in PROD, nie persistiert). */
    private static KeyPair generiereDevKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    /** Parst einen X.509/SPKI-PEM-Public-Key (aus Vault-Item oder Classpath). */
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

    /** {@code true}, wenn eines der aktiven Spring-Profile {@code prod} ist (kommasepariert, case-insensitive). */
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
     * @param scope {@code READ}/{@code EINTRAGEN}/{@code ADMIN}, oder {@code null} bei Alt-Tokens ohne
     *              Scope-Claim (Aufrufer wie {@link McpBearerTokenFilter} behandeln das als {@code ADMIN}).
     * @param jti   Token-ID für die optionale Revocation-Prüfung, oder {@code null} bei Alt-Tokens
     *              ohne {@code jti}-Claim (für diese ist keine Revocation vor Ablauf möglich).
     */
    public record JwtValidationResult(Long userId, String mandat, String email, String tokenName, Instant expiresAt,
                                       String scope, String jti) {}
}
