/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Centralized Bearer token validation for all REST API controllers.
 * Extracts JWT from Authorization header, validates signature, expiry, and revocation.
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiTokenValidatorServiceImpl implements ApiTokenValidatorService {

    private final ApiTokenService apiTokenService;
    private final JwtTokenService jwtTokenService;

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public ITokenValidationOutcome validateRequest(String authorizationHeader, String requestPath) {
        String token = extractBearerToken(authorizationHeader);

        if (token == null || token.isEmpty()) {
            log.warn("API request without token to {}", requestPath);
            return errorOutcome(ApiErrorResponse.tokenMissing(requestPath));
        }

        // JWT-Signatur + Ablauf GENAU EINMAL prüfen; das Ergebnis wird an den
        // Revocation-Check weitergereicht (vorher wurde die Signatur doppelt validiert:
        // einmal implizit in apiTokenService.validateToken, einmal explizit hier).
        Optional<JwtTokenService.JwtValidationResult> jwtResult = jwtTokenService.validateToken(token);

        if (jwtResult.isEmpty()) {
            // Signatur ungültig ODER abgelaufen — echte exp-Prüfung unterscheidet die beiden.
            if (isTokenExpired(token)) {
                Instant expiredAt = getTokenExpiry(token);
                log.warn("API request with expired token to {} - expired at {}", requestPath, expiredAt);
                return errorOutcome(ApiErrorResponse.tokenExpired(expiredAt, requestPath));
            }
            log.warn("API request with invalid token to {}", requestPath);
            return errorOutcome(ApiErrorResponse.tokenInvalid(requestPath));
        }

        // Signatur gültig & nicht abgelaufen → Hash-Lookup in DB (Revocation-Check),
        // ohne die Signatur erneut zu validieren.
        Optional<ApiTokenValidationResult> result = apiTokenService.validateVerifiedToken(token, jwtResult.get());

        if (result.isPresent()) {
            return new TokenValidationOutcome(null, result.get());
        }

        // JWT ist gültig, aber nicht (mehr) in der DB bzw. invalidiert → revoked
        log.warn("API request with revoked token to {} for userId={}", requestPath, jwtResult.get().userId());
        return errorOutcome(ApiErrorResponse.tokenRevoked(requestPath));
    }

    private String extractBearerToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private ITokenValidationOutcome errorOutcome(ApiErrorResponse error) {
        return new TokenValidationOutcome(
                ResponseEntity.status(HttpStatus.valueOf(error.status())).body(error),
                null
        );
    }

    /**
     * Echte Ablauf-Prüfung: parst den {@code exp}-Claim aus dem (unverifizierten) JWT-Payload
     * und vergleicht ihn mit der aktuellen Zeit. Dient ausschliesslich der Fehler-KLASSIFIZIERUNG
     * nach bereits fehlgeschlagener Validierung (expired vs. invalid) — nie als Sicherheitsentscheid.
     * <p>
     * Token OHNE {@code exp}-Claim (oder mit unparsbarem Payload) gelten als NICHT abgelaufen —
     * bisherige Semantik beibehalten — und laufen damit in den Invalid-/Revoked-Pfad.
     */
    private boolean isTokenExpired(String token) {
        Instant expiry = getTokenExpiry(token);
        return expiry != null && expiry.isBefore(Instant.now());
    }

    private Instant getTokenExpiry(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            int expIdx = payload.indexOf("\"exp\"");
            if (expIdx >= 0) {
                int colonIdx = payload.indexOf(":", expIdx);
                int endIdx = payload.indexOf(",", colonIdx);
                if (endIdx < 0) endIdx = payload.indexOf("}", colonIdx);
                if (colonIdx >= 0 && endIdx >= 0) {
                    String expStr = payload.substring(colonIdx + 1, endIdx).trim();
                    long expSeconds = Long.parseLong(expStr);
                    return Instant.ofEpochSecond(expSeconds);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private record TokenValidationOutcome(
            ResponseEntity<?> errorResponse,
            ApiTokenValidationResult validation
    ) implements ITokenValidationOutcome {

        @Override
        public boolean hasError() {
            return errorResponse != null;
        }

        @Override
        public ResponseEntity<?> getErrorResponse() {
            return errorResponse;
        }

        @Override
        public ApiTokenValidationResult getValidation() {
            return validation;
        }
    }
}
