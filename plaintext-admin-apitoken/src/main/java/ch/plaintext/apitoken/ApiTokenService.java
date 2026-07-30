/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing API tokens with JWT support.
 * Implements {@link IApiTokenService} for cross-module usage.
 * <p>
 * Tokens are RS256-signed JWTs. Only the SHA-256 hash of the JWT is stored in the database.
 * The actual JWT string is returned once at creation time and cannot be recovered.
 * <p>
 * Validation flow:
 * 1. Validate JWT signature (PKI/RS256) via {@link JwtTokenService}
 * 2. Compute SHA-256 hash of the JWT
 * 3. Look up hash in DB to check revocation/invalidation status
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiTokenService implements IApiTokenService {

    private final ApiTokenRepository apiTokenRepository;
    private final JwtTokenService jwtTokenService;

    private static final int MAX_TOKENS_PER_USER = 10;

    /**
     * {@inheritDoc}
     * <p>
     * Generates a JWT token, computes its SHA-256 hash, stores only the hash in the database,
     * and returns the JWT string (one-time, not recoverable).
     */
    @Override
    @Transactional
    public String createToken(Long userId, String mandat, String tokenName, String email, int validityDays) {
        return createToken(userId, mandat, tokenName, email, validityDays, null);
    }

    /**
     * Wie {@link #createToken(Long, String, String, String, int)}, jedoch mit explizitem Berechtigungs-
     * {@code scope} ({@code READ}/{@code EINTRAGEN}/{@code ADMIN}) im JWT.
     *
     * <p><b>Warum das nötig ist (Karte 312, H-7):</b> Bis hierher stellte die Ausstellung Tokens
     * grundsätzlich <em>ohne</em> {@code scope}-Claim aus, und {@code McpBearerTokenFilter} deutete einen
     * fehlenden Scope als {@code ADMIN}. Damit war faktisch jeder API-Token ein Vollzugriffs-Token. Der
     * Filter wird auf fail-closed ({@code READ}) umgestellt — das ist aber nur dann gefahrlos, wenn die
     * Ausstellung einen Scope überhaupt vergeben <em>kann</em>. Genau das leistet diese Überladung.</p>
     *
     * @param scope {@code READ}, {@code EINTRAGEN} oder {@code ADMIN}; {@code null}/leer lässt den Claim
     *              weg (Alt-Verhalten, dann greift im Filter der fail-closed-Default)
     */
    @Transactional
    public String createToken(Long userId, String mandat, String tokenName, String email, int validityDays,
                              String scope) {
        // Check max tokens limit
        long existingCount = apiTokenRepository.countByUserIdAndMandatAndDeleted(userId, mandat, false);
        if (existingCount >= MAX_TOKENS_PER_USER) {
            throw new IllegalStateException("Maximale Anzahl Tokens erreicht (" + MAX_TOKENS_PER_USER + ")");
        }

        // Check for duplicate name
        if (tokenName != null && !tokenName.isBlank()) {
            Optional<ApiToken> existing = apiTokenRepository
                    .findByUserIdAndMandatAndTokenNameAndDeleted(userId, mandat, tokenName, false);
            if (existing.isPresent()) {
                throw new IllegalStateException("Ein Token mit diesem Namen existiert bereits: " + tokenName);
            }
        }

        // Enforce bounds
        if (validityDays < JwtTokenService.MIN_VALIDITY_DAYS) validityDays = JwtTokenService.MIN_VALIDITY_DAYS;
        if (validityDays > JwtTokenService.MAX_VALIDITY_DAYS) validityDays = JwtTokenService.MAX_VALIDITY_DAYS;

        // Generate JWT token (mit explizitem scope-Claim, sofern gesetzt — siehe Javadoc/Karte 312)
        String jwtToken = jwtTokenService.generateToken(userId, mandat, email, tokenName, validityDays, scope);

        // Compute SHA-256 hash of the JWT - only the hash is stored
        String hash = sha256(jwtToken);

        Instant expiresAt = Instant.now().plus(Duration.ofDays(validityDays));

        ApiToken token = new ApiToken();
        token.setTokenHash(hash);
        token.setUserId(userId);
        token.setMandat(mandat);
        token.setTokenName(tokenName);
        token.setUserEmail(email);
        token.setDescription("JWT API Token" + (tokenName != null ? " - " + tokenName : ""));
        token.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()));
        token.setDeleted(false);

        apiTokenRepository.save(token);
        log.info("Created new JWT token '{}' for userId={}, mandat={}, validityDays={}, expiresAt={}",
                tokenName, userId, mandat, validityDays, token.getExpiresAt());

        // Return the JWT string - this is the only time it is available
        return jwtToken;
    }

    /**
     * Create a new named token with default validity (90 days).
     */
    @Transactional
    public String createToken(Long userId, String mandat, String tokenName, String email) {
        return createToken(userId, mandat, tokenName, email, JwtTokenService.DEFAULT_VALIDITY_DAYS);
    }

    /**
     * Stellt ein Token für einen <b>maschinellen</b> Ausstellungs-Flow aus und persistiert es wie ein
     * UI-Token (SHA-256-Hash in {@code api_token}) — Voraussetzung dafür, dass
     * {@code plaintext.mcp.bearer-filter.validation: DATABASE} solche Flows nicht aussperrt (Karte 349).
     *
     * <p><b>Warum nicht {@link #createToken(Long, String, String, String, int, String)}?</b> Dessen zwei
     * Schutzregeln sind für die UI richtig, für automatische Aussteller aber tödlich:</p>
     * <ul>
     *   <li><b>Duplikat-Namen-Prüfung</b> — der Juriwagen mintet bei <em>jedem</em> Öffnen der SPA ein
     *       Token; beim zweiten Öffnen würde „Ein Token mit diesem Namen existiert bereits" fliegen.</li>
     *   <li><b>{@code MAX_TOKENS_PER_USER}</b> — nach zehn Öffnungen wäre der Flow dauerhaft blockiert,
     *       mitten am Turniertag.</li>
     * </ul>
     *
     * <p>Deshalb: gleichnamige Service-Tokens sind hier <b>erlaubt</b> und das Limit greift nicht.
     * Damit die Tabelle trotzdem nicht unbegrenzt wächst, werden vor dem Anlegen die <b>abgelaufenen</b>
     * Tokens desselben Flows ({@code userId}/{@code mandat}/{@code tokenName}) soft-gelöscht.</p>
     *
     * <p>Bewusst wird <b>nicht</b> das jeweils vorherige Token ersetzt: Zwei Geräte bzw. Browser-Tabs
     * desselben Benutzers müssen gleichzeitig arbeiten können — ein „immer nur ein aktives Token" würde
     * dem Juri am Turniertag mitten im Betrieb den Zugang entziehen.</p>
     *
     * @param scope {@code READ}/{@code EINTRAGEN}/{@code ADMIN}, oder {@code null} (dann greift im
     *              Filter der fail-closed-Default, siehe Karte 312)
     */
    @Transactional
    public String createServiceToken(Long userId, String mandat, String tokenName, String email,
                                     int validityDays, String scope) {
        if (validityDays < JwtTokenService.MIN_VALIDITY_DAYS) {
            validityDays = JwtTokenService.MIN_VALIDITY_DAYS;
        }
        if (validityDays > JwtTokenService.MAX_VALIDITY_DAYS) {
            validityDays = JwtTokenService.MAX_VALIDITY_DAYS;
        }

        int aufgeraeumt = raeumeAbgelaufeneAuf(userId, mandat, tokenName);

        String jwtToken = jwtTokenService.generateToken(userId, mandat, email, tokenName, validityDays, scope);
        Instant expiresAt = Instant.now().plus(Duration.ofDays(validityDays));

        ApiToken token = new ApiToken();
        token.setTokenHash(sha256(jwtToken));
        token.setUserId(userId);
        token.setMandat(mandat);
        token.setTokenName(tokenName);
        token.setUserEmail(email);
        token.setDescription("Service-Token" + (tokenName != null ? " - " + tokenName : ""));
        token.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()));
        token.setDeleted(false);
        apiTokenRepository.save(token);

        log.info("Service-Token '{}' ausgestellt (userId={}, mandat={}, {} Tage, scope={}, "
                        + "{} abgelaufene aufgeraeumt)",
                tokenName, userId, mandat, validityDays, scope, aufgeraeumt);
        return jwtToken;
    }

    /**
     * Soft-löscht abgelaufene Tokens desselben Service-Flows. Hält {@code api_token} klein, ohne aktive
     * Tokens anzutasten — ein noch gültiges Token eines zweiten Geräts bleibt bestehen.
     *
     * @return Anzahl aufgeräumter Zeilen
     */
    private int raeumeAbgelaufeneAuf(Long userId, String mandat, String tokenName) {
        if (tokenName == null || tokenName.isBlank()) {
            return 0;
        }
        LocalDateTime jetzt = LocalDateTime.now();
        List<ApiToken> abgelaufen = apiTokenRepository
                .findByUserIdAndMandatAndDeletedOrderByCreatedAtDesc(userId, mandat, false).stream()
                .filter(t -> tokenName.equals(t.getTokenName()))
                .filter(t -> t.getExpiresAt() != null && t.getExpiresAt().isBefore(jetzt))
                .toList();
        abgelaufen.forEach(t -> t.setDeleted(true));
        if (!abgelaufen.isEmpty()) {
            apiTokenRepository.saveAll(abgelaufen);
        }
        return abgelaufen.size();
    }

    /**
     * Get all active (non-deleted) tokens for a user.
     */
    public List<ApiToken> getAllTokens(Long userId, String mandat) {
        return apiTokenRepository.findByUserIdAndMandatAndDeletedOrderByCreatedAtDesc(userId, mandat, false);
    }

    /**
     * Get all active (non-deleted) tokens for a mandat (admin view).
     */
    public List<ApiToken> getAllTokensByMandat(String mandat) {
        return apiTokenRepository.findByMandatAndDeletedOrderByCreatedAtDesc(mandat, false);
    }

    /**
     * Regenerate a specific token (by ID).
     * Invalidates the old token and creates a new one with the same name.
     *
     * @return The new JWT token string (one-time, not recoverable)
     */
    @Transactional
    public String regenerateToken(Long tokenId, Long userId, String mandat, String email, int validityDays) {
        Optional<ApiToken> existing = apiTokenRepository.findById(tokenId);

        if (existing.isEmpty() || existing.get().getDeleted() ||
                !existing.get().getUserId().equals(userId) ||
                !existing.get().getMandat().equals(mandat)) {
            throw new IllegalArgumentException("Token nicht gefunden");
        }

        ApiToken oldToken = existing.get();
        String tokenName = oldToken.getTokenName();

        // Invalidate the old token
        oldToken.setInvalidated(true);
        oldToken.setDeleted(true);
        apiTokenRepository.save(oldToken);

        // Create a new token with the same name
        return createToken(userId, mandat, tokenName, email, validityDays);
    }

    /**
     * Regenerate a specific token with default validity (90 days).
     *
     * @return The new JWT token string (one-time, not recoverable)
     */
    @Transactional
    public String regenerateToken(Long tokenId, Long userId, String mandat, String email) {
        return regenerateToken(tokenId, userId, mandat, email, JwtTokenService.DEFAULT_VALIDITY_DAYS);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Invalidates a token (soft-delete). The token record remains in the database for auditing
     * but is marked as invalidated and deleted.
     */
    @Override
    @Transactional
    public void invalidateToken(Long tokenId, Long userId, String mandat) {
        Optional<ApiToken> existing = apiTokenRepository.findById(tokenId);

        if (existing.isPresent() &&
                existing.get().getUserId().equals(userId) &&
                existing.get().getMandat().equals(mandat)) {
            ApiToken token = existing.get();
            token.setInvalidated(true);
            token.setDeleted(true);
            apiTokenRepository.save(token);
            log.info("Invalidated token '{}' (ID={}) for userId={}, mandat={}",
                    token.getTokenName(), tokenId, userId, mandat);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Invalidates a token by admin (any user in the mandat).
     */
    @Override
    @Transactional
    public void invalidateTokenByAdmin(Long tokenId, String mandat) {
        Optional<ApiToken> existing = apiTokenRepository.findById(tokenId);

        if (existing.isPresent() && existing.get().getMandat().equals(mandat)) {
            ApiToken token = existing.get();
            token.setInvalidated(true);
            token.setDeleted(true);
            apiTokenRepository.save(token);
            log.info("Admin invalidated token '{}' (ID={}) for userId={}, mandat={}",
                    token.getTokenName(), tokenId, token.getUserId(), mandat);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Invalidates a token by root (no mandat restriction).
     */
    @Override
    @Transactional
    public void invalidateTokenByRoot(Long tokenId) {
        Optional<ApiToken> existing = apiTokenRepository.findById(tokenId);

        if (existing.isPresent()) {
            ApiToken token = existing.get();
            token.setInvalidated(true);
            token.setDeleted(true);
            apiTokenRepository.save(token);
            log.info("Root invalidated token '{}' (ID={}) for userId={}, mandat={}",
                    token.getTokenName(), tokenId, token.getUserId(), token.getMandat());
        }
    }

    /**
     * Get all active (non-deleted) tokens across all mandats (root view).
     */
    public List<ApiToken> getAllTokensAllMandats() {
        return apiTokenRepository.findByDeletedOrderByCreatedAtDesc(false);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Validation flow:
     * 1. Validate JWT signature and expiry via JwtTokenService (PKI/RS256)
     * 2. Compute SHA-256 hash of the JWT
     * 3. Look up hash in DB to check if token was revoked/invalidated
     * 4. Update last-used timestamp (best effort)
     * <p>
     * <b>Deliberately NOT {@code @Transactional}.</b> This method is called from servlet
     * filters and non-MVC / reactive (Spring-AI MCP) request contexts that live outside the
     * DispatcherServlet / OpenSessionInView lifecycle. A method-level transaction bound to such
     * a thread never gets its {@code TransactionSynchronizationManager} thread-locals cleaned up,
     * so the Hikari connection is committed but never returned to the pool — the pool is exhausted
     * after {@code maxPoolSize} validations. The read ({@code findByTokenHash}) and the best-effort
     * last-used write ({@code save}) therefore each run in their own short Spring Data transaction
     * that releases its connection immediately. They need no shared transaction: the last-used
     * update is non-critical statistics, not atomic with the revocation check.
     */
    @Override
    public Optional<ApiTokenValidationResult> validateToken(String jwtToken) {
        if (jwtToken == null || jwtToken.isEmpty()) {
            log.debug("Token validation failed: token is null or empty");
            return Optional.empty();
        }

        // Step 1: Validate JWT signature and expiry (PKI check)
        Optional<JwtTokenService.JwtValidationResult> jwtResult = jwtTokenService.validateToken(jwtToken);
        if (jwtResult.isEmpty()) {
            return Optional.empty();
        }

        return validateVerifiedToken(jwtToken, jwtResult.get());
    }

    /**
     * Revocation-/DB-Teil der Validierung (Steps 2-3) für einen BEREITS signatur- und
     * ablauf-geprüften Token. Erlaubt Aufrufern wie {@link ApiTokenValidatorServiceImpl},
     * die JWT-Signatur genau EINMAL zu prüfen und das Ergebnis hierher weiterzureichen,
     * statt sie ein zweites Mal zu validieren. Gleiche Transaktions-Überlegungen wie bei
     * {@link #validateToken(String)} (bewusst nicht {@code @Transactional}).
     *
     * @param jwtToken der rohe JWT-String (für den Hash-Lookup)
     * @param jwt      Ergebnis der bereits erfolgten JWT-Signatur-/Ablauf-Validierung
     * @return Validation result, oder empty wenn revoked/invalidiert/gelöscht
     */
    public Optional<ApiTokenValidationResult> validateVerifiedToken(String jwtToken, JwtTokenService.JwtValidationResult jwt) {
        // Step 2: Compute SHA-256 hash and look up in DB for revocation check
        String hash = sha256(jwtToken);
        Optional<ApiToken> apiToken = apiTokenRepository.findByTokenHash(hash);
        if (apiToken.isEmpty()) {
            log.warn("JWT token hash not found in database for userId={}, mandat={} - possibly revoked",
                    jwt.userId(), jwt.mandat());
            return Optional.empty();
        }

        ApiToken t = apiToken.get();

        if (t.getDeleted()) {
            log.warn("JWT token was deleted for userId={}, mandat={}",
                    jwt.userId(), jwt.mandat());
            return Optional.empty();
        }

        if (t.isInvalidated()) {
            log.warn("JWT token was invalidated for userId={}, mandat={}",
                    jwt.userId(), jwt.mandat());
            return Optional.empty();
        }

        // Step 3: Update last used timestamp and use count
        t.setLastUsedAt(LocalDateTime.now());
        t.setUseCount(t.getUseCount() + 1);
        apiTokenRepository.save(t);

        log.debug("Token validated successfully for userId={}, mandat={}", jwt.userId(), jwt.mandat());
        return Optional.of(new ApiTokenValidationResult(jwt.userId(), jwt.mandat(), t.getUserEmail(), jwt.tokenName(), jwt.expiresAt()));
    }

    /**
     * Check if a token will expire within the given duration.
     */
    public boolean willExpireSoon(ApiToken token, Duration threshold) {
        if (token.getExpiresAt() == null) {
            return true;
        }
        LocalDateTime warningTime = LocalDateTime.now().plus(threshold);
        return token.getExpiresAt().isBefore(warningTime);
    }

    @Override
    public List<String> getActiveTokenNames(Long userId, String mandat) {
        return apiTokenRepository.findByUserIdAndMandatAndDeletedOrderByCreatedAtDesc(userId, mandat, false)
                .stream()
                .filter(t -> !t.isInvalidated() && !t.isExpired())
                .map(ApiToken::getTokenName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    @Override
    public boolean isTokenActiveByName(String tokenName, String mandat) {
        if (tokenName == null || tokenName.isBlank()) return false;
        return apiTokenRepository.findByMandatAndDeletedOrderByCreatedAtDesc(mandat, false)
                .stream()
                .anyMatch(t -> tokenName.equals(t.getTokenName()) && !t.isInvalidated() && !t.isExpired());
    }

    /**
     * Compute SHA-256 hash of the input string, returned as lowercase hex.
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) hexString.append(String.format("%02x", b));
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
