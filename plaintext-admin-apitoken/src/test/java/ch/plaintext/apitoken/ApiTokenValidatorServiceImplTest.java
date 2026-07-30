/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
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
 * Tests für {@link ApiTokenValidatorServiceImpl#validateRequest(String, String)}.
 * <p>
 * Sichert insbesondere die Regression der drei Audit-Befunde:
 * <ul>
 *   <li>Echte exp-Prüfung (vorher nur {@code contains("exp")} — JEDER Token mit exp-Claim
 *       wurde als "expired" gemeldet, egal ob abgelaufen oder nicht).</li>
 *   <li>Der revoked-Zweig ist erreichbar (Signatur gültig, aber nicht in DB).</li>
 *   <li>Die JWT-Signatur wird genau EINMAL validiert (kein zweiter
 *       {@code validateToken}-Durchlauf).</li>
 * </ul>
 */
class ApiTokenValidatorServiceImplTest {

    private final ApiTokenService apiTokenService = mock(ApiTokenService.class);
    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final ApiTokenValidatorServiceImpl validator =
            new ApiTokenValidatorServiceImpl(apiTokenService, jwtTokenService);

    private static final String PATH = "/api/test";

    /** Baut einen JWT-förmigen Token (header.payload.sig) mit gegebenem Payload-JSON. */
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
        // Dedup-Regression: Signatur genau EINMAL validiert, kein zweiter Voll-Validierungslauf.
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
        // Regression zum contains("exp")-Bug: exp-Claim vorhanden, aber in der ZUKUNFT →
        // der Token ist nicht abgelaufen, sondern hat eine ungültige Signatur.
        String token = tokenWithPayload("{\"userId\":7,\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}");
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.empty());

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        assertEquals("TOKEN_INVALID", errorBody(outcome).error());
    }

    @Test
    void tokenOhneExpGiltAlsNichtAbgelaufen() {
        // Dokumentierte Semantik: ohne exp-Claim nie "expired" → Invalid-Pfad.
        String token = tokenWithPayload("{\"userId\":7}");
        when(jwtTokenService.validateToken(token)).thenReturn(Optional.empty());

        ITokenValidationOutcome outcome = validator.validateRequest("Bearer " + token, PATH);

        assertEquals("TOKEN_INVALID", errorBody(outcome).error());
    }

    @Test
    void gueltigeSignaturOhneDbEintragGibtTokenRevoked() {
        // Revoked-Zweig (vorher toter Code): Signatur gültig, aber Hash nicht (mehr) in der DB.
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
