/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.apitoken.IApiTokenService.ApiTokenValidationResult;
import ch.plaintext.apitoken.JwtTokenService.JwtValidationResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ApiTokenValidatorServiceImpl#validateRequest(String, String)}.
 * <p>
 * In particular it secures the regression of the three audit findings:
 * <ul>
 *   <li>A real exp check (previously only {@code contains("exp")} — EVERY token with an exp claim
 *       was reported as "expired", whether it had actually expired or not).</li>
 *   <li>The revoked branch is reachable (signature valid, but not in the DB).</li>
 *   <li>The JWT signature is validated exactly ONCE (no second
 *       {@code validateToken} run).</li>
 * </ul>
 */
class ApiTokenValidatorServiceImplTest {

    private final ApiTokenService apiTokenService = mock(ApiTokenService.class);
    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final ApiTokenValidatorServiceImpl validator =
            new ApiTokenValidatorServiceImpl(apiTokenService, jwtTokenService);

    private static final String PATH = "/api/test";

    /** Builds a JWT-shaped token (header.payload.sig) with the given payload JSON. */
    private static String tokenWithPayload(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    private static ApiErrorResponse errorBody(ITokenValidationOutcome outcome) {
        assertTrue(outcome.hasError());
        return (ApiErrorResponse) outcome.getErrorResponse().getBody();
    }

    @Test
    void fehlenderTokenGibtTokenMissing() {
        ITokenValidationOutcome outcome = validator.validateRequest(null, PATH);

        ApiErrorResponse error = errorBody(outcome);
        assertEquals(401, error.status());
        assertEquals("TOKEN_MISSING", error.error());
        verify(jwtTokenService, never()).validateToken(any());
    }

    @Test
    void gueltigerTokenGibtValidationOhneError() {
        String token = tokenWithPayload("{\"userId\":7,\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}");
        JwtValidationResult jwt = new JwtValidationResult(7L, "plaintext", "u@x.ch", "cli", Instant.now().plusSeconds(3600), null, null);
        ApiTokenValidationResult result = new ApiTokenValidationResult(7L, "plaintext", "u@x.ch", "cli", jwt.expiresAt());
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.of(jwt));
        when(apiTokenService.validateVerifiedToken(token, jwt)).thenReturn(Optional.of(result));

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        assertFalse(outcome.hasError());
        assertNull(outcome.getErrorResponse());
        assertEquals(result, outcome.getValidation());
        // Dedup regression: signature validated exactly ONCE, no second full validation run.
        verify(jwtTokenService, times(1)).validateToken(anyString());
        verify(apiTokenService, never()).validateToken(anyString());
    }

    @Test
    void abgelaufenerTokenGibtTokenExpiredMitAblaufzeit() {
        Instant expiredAt = Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String token = tokenWithPayload("{\"userId\":7,\"exp\":" + expiredAt.getEpochSecond() + "}");
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.empty());

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        ApiErrorResponse error = errorBody(outcome);
        assertEquals("TOKEN_EXPIRED", error.error());
        assertEquals(expiredAt, error.expiredAt());
        verify(apiTokenService, never()).validateVerifiedToken(anyString(), any());
    }

    @Test
    void ungueltigeSignaturMitZukunftsExpGibtTokenInvalidNichtExpired() {
        // Regression for the contains("exp") bug: exp claim present, but in the FUTURE →
        // the token has not expired, it has an invalid signature.
        String token = tokenWithPayload("{\"userId\":7,\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}");
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.empty());

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        assertEquals("TOKEN_INVALID", errorBody(outcome).error());
    }

    @Test
    void tokenOhneExpGiltAlsNichtAbgelaufen() {
        // Documented semantics: without an exp claim never "expired" → invalid path.
        String token = tokenWithPayload("{\"userId\":7}");
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.empty());

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        assertEquals("TOKEN_INVALID", errorBody(outcome).error());
    }

    @Test
    void gueltigeSignaturOhneDbEintragGibtTokenRevoked() {
        // Revoked branch (previously dead code): signature valid, but the hash is not (any longer) in the DB.
        String token = tokenWithPayload("{\"userId\":7,\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}");
        JwtValidationResult jwt = new JwtValidationResult(7L, "plaintext", "u@x.ch", "cli", Instant.now().plusSeconds(3600), null, null);
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.of(jwt));
        when(apiTokenService.validateVerifiedToken(token, jwt)).thenReturn(Optional.empty());

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        ApiErrorResponse error = errorBody(outcome);
        assertEquals(401, error.status());
        assertEquals("TOKEN_REVOKED", error.error());
        assertNotNull(error.timestamp());
    }

    @Test
    void headerOhneBearerPrefixGibtTokenMissing() {
        ITokenValidationOutcome outcome = validator.validateRequest("Basic abc123", PATH);

        assertEquals("TOKEN_MISSING", errorBody(outcome).error());
        verify(jwtTokenService, never()).validateToken(any());
    }
}
